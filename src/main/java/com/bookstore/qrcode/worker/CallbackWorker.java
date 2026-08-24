package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.wecom.*;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis Stream 消费者 —— 异步处理企业微信回调事件。
 *
 * <p><b>工作模式：</b>基于 Redis Stream 的消费者组 (Consumer Group) 模型实现。
 * 在 {@link jakarta.annotation.PostConstruct} 阶段使用独立的
 * {@code callbackExecutor} 线程启动一个常驻后台的消费循环
 * ({@link #consumeLoop()})，持续从 Stream 拉取事件并处理。</p>
 *
 * <p><b>事件路由：</b>每条消息的 {@code event} 字段包含 JSON 字符串，
 * 解析后根据 {@code event_type} 字段分发到对应的处理方法：</p>
 * <ul>
 *   <li>{@code change_external_contact} —— 进一步解析 XML 中的 ChangeType 子路由；</li>
 *   <li>{@code add_external_contact} —— 客户添加成功，执行速率检测、客户入库、日计数 3 步；
 *       自动打标以事件形式发布到独立 Stream 供 {@link TagWorker} 异步消费；
 *       在职继承改为管理员手动触发；</li>
 *   <li>{@code add_fail} —— 添加失败告警；</li>
 *   <li>{@code del_external_contact} —— 客户删除员工；</li>
 *   <li>{@code greeting_fail} —— 欢迎语发送失败告警。</li>
 * </ul>
 *
 * <p><b>ACK 机制：</b>每条消息处理完成后（无论成功或失败）都会立即调用
 * {@code acknowledge} 确认消费，避免阻塞 Stream 的 Pending 队列。
 * 处理单条消息的异常被 catch 后不会影响同批次其他消息的消费。</p>
 *
 * <p><b>优雅关闭：</b>通过 volatile {@code running} 标志控制循环退出。
 * 当线程被 {@link InterruptedException} 中断时，退出循环并记录警告日志。</p>
 *
 * <p><b>容错设计：</b>如果从 Stream 读取消息时发生异常，会休眠 5 秒后重试，
 * 避免在 Redis 连接异常时产生空转。</p>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackWorker {

    private final StringRedisTemplate redisTemplate;
    private final CustomerService customerService;
    private final AgentRotationService rotationService;
    private final AlertService alertService;
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;
    private final Executor callbackExecutor;
    private final com.bookstore.qrcode.service.MessageGuardService messageGuardService;
    private final WecomApiClient wecomApi;
    private final QrCodeRepository qrCodeRepo;

    private volatile boolean running = true;
    /** 回调消费线程数，可通过 app.worker.callback.threads 配置 */
    @Value("${app.worker.callback.threads:4}")
    private int consumerThreads;

    private static final String CONSUMER_PREFIX = "callback-worker";

    /**
     * 初始化启动方法，启动 4 个并行消费线程。
     *
     * <p>每个线程以独立消费者身份加入同一 Consumer Group，
     * Redis Stream 自动将消息分发到不同消费者，无需额外分片。</p>
     */
    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int threadId = i;
            final String consumerName = RedisConfig.consumerName(CONSUMER_PREFIX, threadId);
            callbackExecutor.execute(() -> consumeLoop(consumerName, threadId));
        }
        log.info("CallbackWorker 已启动 {} 个消费线程, Stream={}, Group={}",
            consumerThreads, RedisConfig.CALLBACK_STREAM_KEY,
            RedisConfig.CALLBACK_CONSUMER_GROUP);
    }

    /**
     * Redis Stream 常驻消费循环。
     *
     * <p><b>工作流程：</b>
     * <ol>
     *   <li>使用 {@code XREADGROUP} 以消费者组成员身份从 Stream 读取最多 50 条消息，
     *       阻塞等待最多 5 秒；</li>
     *   <li>如果无消息（超时返回空），短暂休眠 100ms 后继续轮询；</li>
     *   <li>对每条消息，提取 {@code event} 字段 JSON 并调用
     *       {@link #processEvent(String)} 处理；</li>
     *   <li>每条消息处理完成后（无论是否抛出异常）都执行
     *       {@code XACK} 确认，确保消息不会积压在 Pending 列表；</li>
     *   <li>当 {@link #running} 标志为 {@code false} 或线程被中断时退出循环。</li>
     * </ol>
     * </p>
     *
     * <p><b>错误处理：</b>读取 Stream 的网络异常会触发 5 秒休眠后重试；
     * 单条消息的处理异常只影响本条消息，不影响同批次其他消息。</p>
     */
    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        org.springframework.data.redis.connection.stream.Consumer.from(
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.CALLBACK_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    String msgId = record.getId().getValue();
                    Map<Object, Object> value = record.getValue();
                    String eventJson = (String) value.get("event");
                    if (eventJson == null) {
                        // _init=1 占位消息或空消息，静默 ACK 防止 PEL 泄漏
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            RedisConfig.CALLBACK_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    // 检查 _retry_at 时间戳（指数退避），未到时间则不 ACK、留在 PEL
                    // 由 MessageGuardService.recoverOrphanedPending 在 idle>30s 后重投
                    String retryAt = (String) value.get("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > java.time.Instant.now().getEpochSecond()) {
                                continue; // 不 ACK，留 PEL 等待延迟重投
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    try {
                        processEvent(eventJson);
                        // 成功 — ACK
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            RedisConfig.CALLBACK_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        // 企微异常 — 按 classifyWecomError 分类处理
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("回调处理失败 (动作={}): consumer={}, msgId={}", action, consumerName, msgId, e);
                        switch (action) {
                            case DLQ:
                                messageGuardService.sendToDlq(RedisConfig.CALLBACK_STREAM_KEY, fields);
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.CALLBACK_STREAM_KEY,
                                    RedisConfig.CALLBACK_CONSUMER_GROUP, msgId);
                                break;
                            case REFRESH_TOKEN_AND_RETRY:
                                wecomApi.refreshToken();
                                messageGuardService.markRetryOrDead(
                                    RedisConfig.CALLBACK_STREAM_KEY,
                                    RedisConfig.CALLBACK_CONSUMER_GROUP,
                                    msgId, fields, e.getMessage());
                                break;
                            case WAIT_AND_RETRY:
                                if (e instanceof WecomRateLimitException rle) {
                                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                messageGuardService.markRetryOrDead(
                                    RedisConfig.CALLBACK_STREAM_KEY,
                                    RedisConfig.CALLBACK_CONSUMER_GROUP,
                                    msgId, fields, e.getMessage());
                                break;
                            case RETRY:
                            default:
                                messageGuardService.markRetryOrDead(
                                    RedisConfig.CALLBACK_STREAM_KEY,
                                    RedisConfig.CALLBACK_CONSUMER_GROUP,
                                    msgId, fields, e.getMessage());
                                break;
                        }
                    } catch (Exception e) {
                        // 非企微异常 — 走正常重试流程
                        log.error("回调处理失败: consumer={}, msgId={}", consumerName, msgId, e);
                        messageGuardService.markRetryOrDead(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            msgId, fields, e.getMessage());
                    }
                }

                // 每批消费后 trim，防 Stream 无限增长（只在 ACK 后删除已消费消息）
                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.CALLBACK_STREAM_KEY,
                        RedisConfig.STREAM_MAXLEN, true);
                } catch (Exception e) {
                    log.debug("CALLBACK_STREAM trim 跳过: {}", e.getMessage());
                }

                log.debug("Consumer-{} 本批处理 {} 条", threadId, records.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // NOGROUP: 消费者组被删除后自动重建（与 TagWorker/DataFillWorker 一致的自愈逻辑）
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        // 占位消息确保 Stream 存在（Redis < 7.0 要求），
                        // 用 XDEL 精确删除占位消息，避免 trim(0) 误删未消费的合法消息
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.CALLBACK_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.CALLBACK_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.CALLBACK_STREAM_KEY, initId);
                    } catch (Exception e2) {
                        log.warn("CallbackWorker Stream/ConsumerGroup 创建异常: {}", e2.getMessage());
                    }
                    continue;
                }
                log.error("CallbackWorker-{} 消费异常, 5s 后重试", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("CallbackWorker-{} 已停止", threadId);
    }

    /**
     * 优雅关闭：通知所有消费线程退出循环。
     * Spring 容器销毁时自动调用，给线程最多 10 秒完成当前批次。
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        log.info("CallbackWorker 已发送关闭信号");
    }

    /**
     * 根据事件类型将回调事件路由到对应的处理器。
     *
     * <p>支持的事件类型：</p>
     * <ul>
     *   <li>{@code change_external_contact} —— 进一步解析 XML 获取 ChangeType 并分发；</li>
     *   <li>{@code add_external_contact} —— 直接处理添加成功情况；</li>
     *   <li>{@code add_fail} —— 客户添加失败告警；</li>
     *   <li>{@code del_external_contact} —— 客户删除员工处理；</li>
     *   <li>{@code greeting_fail} —— 欢迎语发送失败告警。</li>
     * </ul>
     *
     * @param eventJson 回调事件的完整 JSON 字符串
     * @throws Exception 当 JSON 解析失败或路由过程中发生异常时抛出，
     *                   由调用方 ({@link #consumeLoop()}) 捕获并记录日志
     */
    private void processEvent(String eventJson) throws Exception {
        JsonNode event = objectMapper.readTree(eventJson);
        String eventType = event.has("event_type") ? event.get("event_type").asText() : "";

        switch (eventType) {
            case "change_external_contact":
                // 外部联系人变更事件
                handleExternalContactEvent(event);
                break;
            case "add_external_contact":
                // 添加外部联系人成功（有些回调格式可能不同）
                handleAddSuccess(event);
                break;
            case "add_fail":
                // 添加失败
                alertService.handleAddFail(event);
                break;
            case "del_external_contact":
                // 客户删除员工
                customerService.handleDelete(event);
                break;
            case "greeting_fail":
                // 欢迎语发送失败
                alertService.handleGreetingFail(event);
                break;
            default:
                log.debug("未处理的事件类型: {}", eventType);
        }
    }

    /**
     * 处理 {@code change_external_contact} 类型的外部联系人变更事件。
     *
     * <p>企业微信回调的 {@code change_external_contact} 事件使用 XML 格式的
     * {@code raw_xml} 字段传递变更类型。本方法从 XML 中提取 {@code ChangeType}
     * 标签，然后按变更类型分发：</p>
     * <ul>
     *   <li>{@code add_external_contact} —— 客户添加员工；</li>
     *   <li>{@code del_external_contact} —— 客户删除员工；</li>
     *   <li>{@code add_fail} —— 添加失败；</li>
     *   <li>{@code greeting_fail} —— 欢迎语失败。</li>
     * </ul>
     *
     * @param event 解析后的 JSON 节点，需包含 {@code raw_xml} 字段
     * @throws Exception 解析或处理过程中的异常
     */
    private void handleExternalContactEvent(JsonNode event) throws Exception {
        String rawXml = event.has("raw_xml") ? event.get("raw_xml").asText() : "";
        // 从 XML 提取 ChangeType
        String changeType = extractXmlTag(rawXml, "ChangeType");

        if ("add_external_contact".equals(changeType)) {
            handleAddSuccess(event);
        } else if ("del_external_contact".equals(changeType)) {
            customerService.handleDelete(event);
        } else if ("add_fail".equals(changeType)) {
            alertService.handleAddFail(event);
        } else if ("greeting_fail".equals(changeType)) {
            alertService.handleGreetingFail(event);
        }
    }

    /**
     * 处理客户添加成功事件，执行精简的 3 步流程 + 打标事件发布。
     *
     * <p><b>执行顺序（每一步均有独立 try-catch，单步失败不影响后续）：</b>
     * <ol>
     *   <li><b>速率检测</b> —— 调用 {@link RateLimiterService#recordAdd} 记录本次添加；</li>
     *   <li><b>记录/更新客户信息</b> —— 调用 {@link CustomerService#upsertFromCallback}
     *       将客户信息写入数据库；</li>
     *   <li><b>发布打标事件</b> —— 将打标所需数据以 XADD 方式发布到
     *       {@link RedisConfig#TAG_STREAM_KEY}，由 {@link TagWorker} 异步消费；</li>
     *   <li><b>员工日计数 +1</b> —— 调用 {@link AgentRotationService#incrementDailyCount}
     *       累加该员工今日全局接待量，触发阈值检查与自动轮换。</li>
     * </ol>
     * </p>
     *
     * <p>在职继承已改为管理员手动触发，不再由回调自动执行。</p>
     *
     * @param event 添加成功事件的 JSON 节点，需包含 {@code external_userid}、
     *              {@code userid} 和 {@code state} 字段
     */
    private void handleAddSuccess(JsonNode event) {
        String externalUserId = getField(event, "external_userid");
        String userId = getField(event, "userid");
        String state = getField(event, "state");

        if (externalUserId == null || userId == null) {
            log.warn("添加成功事件缺少关键字段: external={}, userid={}", externalUserId, userId);
            return;
        }

        // ① 速率检测（仅对有机新增计数 — state 非空表示客户扫码添加）
        // 在职继承/离职继承等企微内部转移的回调 state 为空，不计入熔断
        if (state != null && !state.isBlank()) {
            try {
                rateLimiterService.recordAdd(userId);
            } catch (Exception e) {
                log.error("速率检测失败: userid={}", userId, e);
            }
        } else {
            log.debug("跳过熔断计数（state 为空，非有机新增）: userid={}, external={}",
                userId, externalUserId);
        }

        // ② 记录/更新客户信息 — 关键路径，失败必须向上传播以触发重试/DLQ
        Long customerId = customerService.upsertFromCallback(externalUserId, userId, state);

        // ②.5 标记该学校有新客户（供 InheritanceJob 增量扫描，避免每次遍历所有活跃活码）
        if (state != null && !state.isBlank()) {
            try {
                String dirtyKey = com.bookstore.qrcode.job.InheritanceJob.DIRTY_SCHOOLS_KEY;
                redisTemplate.opsForSet().add(dirtyKey, state);
                redisTemplate.expire(dirtyKey, java.time.Duration.ofMinutes(30));
            } catch (Exception e) {
                log.warn("标记脏学校失败（将回退全量扫描，不影响主流程）: state={}", state, e);
            }
        }

        // ③ 发布自动打标事件 → TagWorker 异步消费，失败传播
        if (state != null) {
            try {
                Map<String, Object> tagEvent = new java.util.LinkedHashMap<>();
                tagEvent.put("external_userid", externalUserId);
                tagEvent.put("userid", userId);
                tagEvent.put("state", state);
                redisTemplate.opsForStream().add(
                    RedisConfig.TAG_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(tagEvent)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("序列化打标事件失败", e);
            }
        }

        // ④ 员工日计数（仅对有机新增计数 — state 非空表示客户扫码添加）
        if (state != null && !state.isBlank()) {
            try {
                rotationService.incrementDailyCount(userId, state);
            } catch (Exception e) {
                log.error("日计数失败: userid={}, state={}", userId, state, e);
            }
        }

        // ⑤ 发布欢迎语+表单事件 → OutboundMsgWorker 异步发送
        try {
            String welcomeCode = getField(event, "welcome_code");
            QrCode qr = null;
            if (state != null) {
                qr = qrCodeRepo.findBySchoolId(state).orElse(null);
                if (qr == null) {
                    log.warn("回调 state={} 未匹配到活码, 将使用系统默认欢迎语: external={}, userid={}",
                        state, externalUserId, userId);
                }
            } else {
                log.debug("回调缺少 state 字段, 使用系统默认欢迎语: external={}, userid={}",
                    externalUserId, userId);
            }
            // 即使找不到活码或 state 为空，也发送系统默认欢迎语
            Map<String, Object> outEvent = new java.util.LinkedHashMap<>();
            outEvent.put("type", "welcome_and_form");
            outEvent.put("external_userid", externalUserId);
            outEvent.put("userid", userId);
            outEvent.put("state", state);
            if (qr != null) {
                outEvent.put("qr_code_id", qr.getId().toString());
            }
            if (customerId != null) {
                outEvent.put("customer_id", customerId.toString());
            }
            if (welcomeCode != null) {
                outEvent.put("welcome_code", welcomeCode);
            }
            redisTemplate.opsForStream().add(
                RedisConfig.OUTBOUND_STREAM_KEY,
                Map.of("event", objectMapper.writeValueAsString(outEvent)));
        } catch (Exception e) {
            log.error("发布欢迎语事件失败: external={}", externalUserId, e);
            // 不抛异常，不影响主流程（客户已入库+日计数已完成）
        }

        // ⑥ 在职继承已移至 InheritanceJob 定时任务：
        //    白天（默认 08:00-21:00）：每 15 分钟批量转移（延迟 15 分钟）
        //    夜间（默认 21:00-08:00）：次日 08:30 批量转移
        //    不再在此处立即 XADD，保证客户添加后延迟 15 分钟再转，避免打扰

        log.info("添加成功处理完成: external={}, userid={}, state={}, customerId={}",
            externalUserId, userId, state, customerId);
    }

    /**
     * 从 JSON 节点中安全提取字符串字段，如果字段缺失或为 null 则返回 null。
     *
     * @param event JSON 节点
     * @param field 字段名
     * @return 字段的字符串值，不存在或为 null 时返回 {@code null}
     */
    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }

    /**
     * 从企业微信回调的 XML 字符串中提取指定标签的值。
     *
     * <p>企业微信回调事件中的 {@code raw_xml} 字段使用 XML 格式传递数据。
     * 本方法兼容两种格式：</p>
     * <ol>
     *   <li><b>CDATA 包裹格式（优先）：</b>{@code <Tag><![CDATA[值]]></Tag>}
     *       —— 企业微信默认使用此格式；</li>
     *   <li><b>标准 XML 格式：</b>{@code <Tag>值</Tag>}
     *       —— 作为降级匹配。</li>
     * </ol>
     *
     * <p>如果标签不存在或 XML 为 null，返回 {@code null}。</p>
     *
     * @param xml 原始 XML 字符串
     * @param tag 要提取的标签名（不含尖括号）
     * @return 标签内的文本内容，如果未找到则返回 {@code null}
     */
    private String extractXmlTag(String xml, String tag) {
        if (xml == null) return null;
        // 优先匹配 CDATA 包裹格式（企业微信回调均使用 CDATA 防止转义问题）
        String startTag = "<" + tag + "><![CDATA[";
        String endTag = "]]></" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // 降级：标准 XML 标签格式
        startTag = "<" + tag + ">";
        endTag = "</" + tag + ">";
        start = xml.indexOf(startTag);
        end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            String content = xml.substring(start + startTag.length(), end);
            // 如果内容仍然包着 CDATA 标记（标准格式匹配误中），清理掉
            if (content.startsWith("<![CDATA[")) {
                content = content.substring(9, content.length() - 3);
            }
            return content;
        }
        return null;
    }
}
