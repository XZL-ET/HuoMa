package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>{@code add_external_contact} —— 客户添加成功，执行打标、计数、继承等 5 步；</li>
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
    private final TagService tagService;
    private final CustomerService customerService;
    private final AgentBindService agentBindService;
    private final TransferService transferService;
    private final AlertService alertService;
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;
    private final Executor callbackExecutor;

    private volatile boolean running = true;

    /**
     * 初始化启动方法，在 Spring 依赖注入完成后自动调用。
     *
     * <p>向 {@code callbackExecutor}（一个独立的线程池）提交消费循环任务，
     * 使回调处理与 Web 请求线程解耦。启动时打印 Stream 和消费者组名称以便运维确认。</p>
     */
    @PostConstruct
    public void start() {
        callbackExecutor.execute(this::consumeLoop);
        log.info("CallbackWorker 已启动, Stream={}, Group={}",
            RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP);
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
    private void consumeLoop() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        org.springframework.data.redis.connection.stream.Consumer.from(
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            RedisConfig.CALLBACK_CONSUMER_NAME),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.CALLBACK_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100); // 无消息时短暂休眠
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        Map<Object, Object> value = record.getValue();
                        String eventJson = (String) value.get("event");
                        processEvent(eventJson);
                    } catch (Exception e) {
                        log.error("处理回调事件失败", e);
                    } finally {
                        // ACK 每条消息
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            record.getId().getValue());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("CallbackWorker 消费异常, 5s 后重试", e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("CallbackWorker 已停止");
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
     * 处理客户添加成功事件，执行完整的 5 步处理流程。
     *
     * <p><b>执行顺序（每一步均有独立 try-catch，单步失败不影响后续）：</b>
     * <ol>
     *   <li><b>速率检测</b> —— 调用 {@link RateLimiterService#recordAdd} 记录本次添加，
     *       用于员工级别的频率控制，防止触发企业微信风控；</li>
     *   <li><b>记录/更新客户信息</b> —— 调用 {@link CustomerService#upsertFromCallback}
     *       将客户信息写入数据库（必须在打标之前执行，因为自动打标依赖客户记录）；</li>
     *   <li><b>自动打标</b> —— 通过场景值 {@code state} 解析市/区/学校标签，
     *       调用 {@link TagService#autoTag} 为客户打上标签；</li>
     *   <li><b>员工日计数 +1</b> —— 调用 {@link AgentBindService#incrementDailyCount}
     *       累加该员工今日接待量，为日上限检测提供数据；</li>
     *   <li><b>触发在职继承</b> —— 如果客户记录已成功创建，调用
     *       {@link TransferService#initiate} 发起在职继承流程。</li>
     * </ol>
     * </p>
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

        // ① 速率检测（防封）
        try {
            rateLimiterService.recordAdd(userId);
        } catch (Exception e) {
            log.error("速率检测失败: userid={}", userId, e);
        }

        // ② 记录/更新客户信息（必须在打标之前，autoTag 依赖客户记录）
        Long customerId = null;
        try {
            customerId = customerService.upsertFromCallback(externalUserId, userId, state);
        } catch (Exception e) {
            log.error("记录客户失败（非阻塞）: external={}", externalUserId, e);
        }

        // ③ 自动打标（市/区/学校）— 失败不阻塞后续
        if (state != null) {
            try {
                tagService.autoTag(externalUserId, userId, state);
            } catch (Exception e) {
                log.error("自动打标失败（非阻塞）: external={}, state={}", externalUserId, state, e);
            }
        }

        // ④ 员工日计数 +1
        try {
            agentBindService.incrementDailyCount(userId, state);
        } catch (Exception e) {
            log.error("日计数失败: userid={}, state={}", userId, state, e);
        }

        // ⑤ 触发在职继承
        if (customerId != null) {
            try {
                transferService.initiate(customerId, userId, externalUserId, state);
            } catch (Exception e) {
                log.error("在职继承失败（非阻塞）: customerId={}", customerId, e);
            }
        }

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
