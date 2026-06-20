package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.TagService;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.bookstore.qrcode.wecom.*;
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
 * Redis Stream 消费者 —— 异步处理客户自动打标事件。
 *
 * <p><b>工作模式：</b>基于 Redis Stream 的消费者组 (Consumer Group) 模型实现。
 * 在 {@link jakarta.annotation.PostConstruct} 阶段使用 {@code taskExecutor} 线程池
 * 启动一个常驻后台的消费循环 ({@link #consumeLoop()})，持续从
 * {@code wecom:tag:stream} 拉取打标事件并处理。</p>
 *
 * <p><b>事件来源：</b>打标事件由 {@link com.bookstore.qrcode.worker.CallbackWorker CallbackWorker}
 * 在处理完客户入库后通过 XADD 发布到 {@link RedisConfig#TAG_STREAM_KEY}。
 * 本 Worker 独立消费，与回调主链路完全解耦，打标失败不影响客户入库和日计数。</p>
 *
 * <p><b>ACK 机制：</b>每条消息处理完成后（无论成功或失败）都会立即调用
 * {@code acknowledge} 确认消费，避免阻塞 Stream 的 Pending 队列。
 * 处理单条消息的异常被 catch 后不会影响同批次其他消息的消费。</p>
 *
 * <p><b>优雅关闭：</b>通过 volatile {@code running} 标志控制循环退出。
 * 当线程被 {@link InterruptedException} 中断时，退出循环并记录警告日志。</p>
 *
 * <p><b>线程隔离：</b>使用 {@code taskExecutor} 线程池（而非 {@code callbackExecutor}），
 * 避免打标过程中企微 API 调用耗时较长时阻塞回调主消费线程。</p>
 *
 * @author Bookstore Dev
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagWorker {

    private final StringRedisTemplate redisTemplate;
    private final TagService tagService;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final WecomApiClient wecomApi;
    private final com.bookstore.qrcode.service.MessageGuardService messageGuardService;

    private volatile boolean running = true;
    /** 打标并发线程数，可通过 app.worker.tag.threads 配置 */
    @Value("${app.worker.tag.threads:8}")
    private int consumerThreads;
    private static final String CONSUMER_PREFIX = "tag-worker";
    /** 每条消息间的间隔 ms，可通过 app.worker.tag.delay-ms 配置，默认 50ms 防限流 */
    @Value("${app.worker.tag.delay-ms:50}")
    private long tagDelayMs;

    /**
     * 启动 4 个并行打标消费线程。
     */
    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int threadId = i;
            final String consumerName = RedisConfig.consumerName(CONSUMER_PREFIX, threadId);
            taskExecutor.execute(() -> consumeLoop(consumerName, threadId));
        }
        log.info("TagWorker 已启动 {} 个消费线程, Stream={}, Group={}",
            consumerThreads, RedisConfig.TAG_STREAM_KEY, RedisConfig.TAG_CONSUMER_GROUP);
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
                            RedisConfig.TAG_CONSUMER_GROUP,
                            consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.TAG_STREAM_KEY,
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
                        log.warn("跳过空消息(Tag): msgId={}, value={}", msgId, value);
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TAG_STREAM_KEY,
                            RedisConfig.TAG_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    // 检查 _retry_at 时间戳（指数退避），未到时间则跳过
                    String retryAt = (String) value.get("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > java.time.Instant.now().getEpochSecond()) {
                                // 尚未到重试时间，放回并 ACK（会在 PEL 回收时重新处理）
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.TAG_STREAM_KEY,
                                    RedisConfig.TAG_CONSUMER_GROUP, msgId);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    try {
                        processEvent(eventJson);
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TAG_STREAM_KEY,
                            RedisConfig.TAG_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("打标处理失败 (动作={}): consumer={}, msgId={}", action, consumerName, msgId, e);
                        switch (action) {
                            case DLQ:
                                messageGuardService.sendToDlq(RedisConfig.TAG_STREAM_KEY, fields);
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.TAG_STREAM_KEY,
                                    RedisConfig.TAG_CONSUMER_GROUP, msgId);
                                break;
                            case REFRESH_TOKEN_AND_RETRY:
                                wecomApi.refreshToken();
                                messageGuardService.markRetryOrDead(RedisConfig.TAG_STREAM_KEY,
                                    RedisConfig.TAG_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            case WAIT_AND_RETRY:
                                if (e instanceof WecomRateLimitException rle) {
                                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                messageGuardService.markRetryOrDead(RedisConfig.TAG_STREAM_KEY,
                                    RedisConfig.TAG_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            default:
                                messageGuardService.markRetryOrDead(RedisConfig.TAG_STREAM_KEY,
                                    RedisConfig.TAG_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                        }
                    } catch (Exception e) {
                        log.error("打标处理失败: consumer={}, msgId={}", consumerName, msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.TAG_STREAM_KEY,
                            RedisConfig.TAG_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }

                    // 最小调用间隔，可通过 worker.tag.delay-ms 配置（默认 50ms 防限流）
                    if (tagDelayMs > 0) {
                        try { Thread.sleep(tagDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }

                // 消费后 trim，扩容到 50000 防止高峰期丢打标事件
                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.TAG_STREAM_KEY,
                        RedisConfig.TAG_STREAM_MAXLEN, true);
                } catch (Exception e) {
                    log.debug("TAG_STREAM trim 跳过: {}", e.getMessage());
                }

                log.debug("TagConsumer-{} 本批处理 {} 条", threadId, records.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.TAG_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(RedisConfig.TAG_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.TAG_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.TAG_STREAM_KEY, initId);
                    } catch (Exception e2) {
                        log.warn("TagWorker Stream/ConsumerGroup 创建异常: {}", e2.getMessage());
                    }
                    continue;
                }
                log.error("TagWorker-{} 消费异常, 5s 后重试", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("TagWorker-{} 已停止", threadId);
    }

    /**
     * 优雅关闭：通知所有消费线程退出循环。
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        log.info("TagWorker 已发送关闭信号");
    }

    /**
     * 解析打标事件并调用 {@link TagService#autoTag} 执行自动打标。
     *
     * <p>事件 JSON 格式（由 {@link com.bookstore.qrcode.worker.CallbackWorker CallbackWorker}
     * 在客户入库后发布）：</p>
     * <pre>
     * {
     *   "external_userid": "wmxxxxxx",
     *   "userid": "zhangsan",
     *   "state": "school_123"
     * }
     * </pre>
     *
     * <p>字段说明：
     * <ul>
     *   <li>{@code external_userid} — 企微客户外部用户ID</li>
     *   <li>{@code userid} — 当前接待员工的企微用户ID（用于企微打标 API 鉴权）</li>
     *   <li>{@code state} — 活码标识（学校ID），用于反查活码和地域标签</li>
     * </ul>
     *
     * @param eventJson 打标事件的 JSON 字符串
     * @throws Exception 当 JSON 解析失败或打标过程发生异常时抛出，
     *                   由调用方 ({@link #consumeLoop()}) 捕获并记录日志
     */
    private void processEvent(String eventJson) throws Exception {
        if (eventJson == null) return;
        JsonNode event = objectMapper.readTree(eventJson);

        // Check for form_submit event type first
        if (event.has("type")) {
            String type = event.get("type").asText();
            if ("form_submit".equals(type)) {
                String externalUserId = getField(event, "external_userid");
                String userId = getField(event, "userid");
                Long formTemplateId = event.has("form_template_id")
                    ? Long.valueOf(event.get("form_template_id").asText()) : null;
                Long submissionId = event.has("submission_id")
                    ? Long.valueOf(event.get("submission_id").asText()) : null;
                String fieldData = event.has("field_data")
                    ? event.get("field_data").asText() : "{}";

                if (externalUserId == null || userId == null || formTemplateId == null) {
                    log.warn("form_submit 事件缺少关键字段");
                    return;
                }
                tagService.applyFormTags(externalUserId, userId,
                    formTemplateId, submissionId, fieldData);
                return;
            }
        }

        // 注意：Jackson NullNode.asText() 返回字符串 "null"，必须用 isNull() 判断
        String externalUserId = (event.has("external_userid") && !event.get("external_userid").isNull())
            ? event.get("external_userid").asText() : null;
        String userId = (event.has("userid") && !event.get("userid").isNull())
            ? event.get("userid").asText() : null;
        String state = (event.has("state") && !event.get("state").isNull())
            ? event.get("state").asText() : null;

        if (externalUserId == null || userId == null || state == null) {
            log.warn("打标事件缺少关键字段: external={}, userid={}, state={}",
                externalUserId, userId, state);
            return;
        }

        tagService.autoTag(externalUserId, userId, state);
    }

    private String getField(com.fasterxml.jackson.databind.JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
