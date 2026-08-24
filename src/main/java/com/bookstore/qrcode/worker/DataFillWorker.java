package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.bookstore.qrcode.wecom.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis Stream 消费者 —— 异步补全新客户的企微信息（name / avatar / unionid）。
 *
 * <p><b>背景：</b>CallbackWorker 新增客户时不调企微 API（Fast Ack 设计），
 * 只写入 name="未知" 的稀疏记录。本 Worker 独立消费来自
 * {@link RedisConfig#DATAFILL_STREAM_KEY} 的补全事件，调用
 * {@code GET /cgi-bin/externalcontact/get} 获取真实信息并回填。</p>
 *
 * <p><b>设计原则：</b>
 * <ul>
 *   <li>独立 Stream —— 不设 MAXLEN trim，补全指令不可丢失</li>
 *   <li>单线程消费 —— 避免对企微 API 造成并发限流压力</li>
 *   <li>幂等补全 —— 仅回填仍为默认值的字段，已补全的记录直接跳过</li>
 *   <li>失败降级 —— API 调用失败记日志后 ACK（不阻塞 PEL），
 *       由 {@link com.bookstore.qrcode.service.CustomerService#repairCustomerData()}
 *       兜底修复</li>
 * </ul>
 *
 * <p><b>线程隔离：</b>使用 {@code taskExecutor} 线程池，与 CallbackWorker
 * 和 TagWorker 共享资源但独立消费。</p>
 *
 * @author Bookstore Dev
 * @since 1.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFillWorker {

    private final StringRedisTemplate redisTemplate;
    private final CustomerRepository customerRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final com.bookstore.qrcode.service.MessageGuardService messageGuardService;

    private volatile boolean running = true;
    /** 客户信息补全消费线程数，可通过 app.worker.datafill.threads 配置 */
    @Value("${app.worker.datafill.threads:4}")
    private int consumerThreads;
    private static final String CONSUMER_PREFIX = "datafill-worker";

    /**
     * 启动 4 个并行客户信息补全消费线程。
     */
    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int threadId = i;
            final String consumerName = RedisConfig.consumerName(CONSUMER_PREFIX, threadId);
            taskExecutor.execute(() -> consumeLoop(consumerName, threadId));
        }
        log.info("DataFillWorker 已启动 {} 个消费线程, Stream={}, Group={}",
            consumerThreads, RedisConfig.DATAFILL_STREAM_KEY,
            RedisConfig.DATAFILL_CONSUMER_GROUP);
    }

    /**
     * Redis Stream 常驻消费循环。
     *
     * <p>每轮 XREADGROUP 最多拉取 10 条（减少单次 API 调用压力），
     * 无消息时休眠 500ms。每条消息处理完立即 ACK，不阻塞 PEL。</p>
     */
    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.DATAFILL_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(10)),
                        StreamOffset.create(RedisConfig.DATAFILL_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    String msgId = record.getId().getValue();
                    Map<Object, Object> value = record.getValue();
                    String eventJson = (String) value.get("event");
                    if (eventJson == null) {
                        // _init=1 占位消息或空消息，静默 ACK 防止 PEL 泄漏
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.DATAFILL_STREAM_KEY,
                            RedisConfig.DATAFILL_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    // 检查 _retry_at 时间戳（指数退避），未到时间则不 ACK、留在 PEL
                    // 由 MessageGuardService.recoverOrphanedPending 在 idle>120s 后重投
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
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.DATAFILL_STREAM_KEY,
                            RedisConfig.DATAFILL_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = com.bookstore.qrcode.service.MessageGuardService.classifyWecomError(e);
                        log.error("补全信息失败 (动作={}): msgId={}", action, msgId, e);
                        switch (action) {
                            case DLQ:
                                messageGuardService.sendToDlq(RedisConfig.DATAFILL_STREAM_KEY, fields);
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.DATAFILL_STREAM_KEY,
                                    RedisConfig.DATAFILL_CONSUMER_GROUP, msgId);
                                break;
                            case REFRESH_TOKEN_AND_RETRY:
                                wecomApi.refreshToken();
                                messageGuardService.markRetryOrDead(RedisConfig.DATAFILL_STREAM_KEY,
                                    RedisConfig.DATAFILL_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            case WAIT_AND_RETRY:
                                if (e instanceof WecomRateLimitException rle) {
                                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                messageGuardService.markRetryOrDead(RedisConfig.DATAFILL_STREAM_KEY,
                                    RedisConfig.DATAFILL_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            default:
                                messageGuardService.markRetryOrDead(RedisConfig.DATAFILL_STREAM_KEY,
                                    RedisConfig.DATAFILL_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                        }
                    } catch (Exception e) {
                        log.error("补全客户信息失败: msgId={}", msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.DATAFILL_STREAM_KEY,
                            RedisConfig.DATAFILL_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }

                    // 最小调用间隔 200ms，4 线程并发下约 20 QPS，防触达企微 API 限流
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }

                // DataFill Stream 设大 MAXLEN 兜底防 OOM，正常情况不会触发
                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.DATAFILL_STREAM_KEY,
                        RedisConfig.DATAFILL_STREAM_MAXLEN, true);
                } catch (Exception e) {
                    log.debug("DATAFILL_STREAM trim 跳过: {}", e.getMessage());
                }

                log.debug("DataFillConsumer-{} 本批处理 {} 条", threadId, records.size());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // NOGROUP: 干净 Redis 首次启动时 Stream/Group 尚未就绪，自动创建后继续
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.DATAFILL_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(
                            RedisConfig.DATAFILL_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.DATAFILL_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.DATAFILL_STREAM_KEY, initId);
                    } catch (Exception e2) {
                        log.warn("DataFillWorker Stream/ConsumerGroup 创建异常: {}", e2.getMessage());
                    }
                    continue;
                }
                log.error("DataFillWorker-{} 消费异常, 10s 后重试", threadId, e);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("DataFillWorker-{} 已停止", threadId);
    }

    /**
     * 优雅关闭：通知所有消费线程退出循环。
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        log.info("DataFillWorker 已发送关闭信号");
    }

    /**
     * 处理单条补全事件，调企微 API 获取客户详情并回填。
     *
     * <p>事件格式：
     * <pre>
     * {
     *   "external_userid": "wmxxxxxx",
     *   "customer_id": 123
     * }
     * </pre>
     *
     * @param eventJson 补全事件的 JSON 字符串
     */
    private void processEvent(String eventJson) throws Exception {
        if (eventJson == null) return;
        JsonNode event = objectMapper.readTree(eventJson);
        String externalUserId = event.has("external_userid")
            ? event.get("external_userid").asText() : null;
        Long customerId = event.has("customer_id")
            ? event.get("customer_id").asLong() : null;

        if (externalUserId == null) {
            log.warn("DataFill 事件缺少 external_userid");
            return;
        }

        // 查找客户记录
        Customer customer;
        if (customerId != null) {
            customer = customerRepo.findById(customerId).orElse(null);
        } else {
            customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        }

        if (customer == null) {
            // 可能 producer 事务尚未提交（visibility race），短暂等待后重试一次
            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            if (customerId != null) {
                customer = customerRepo.findById(customerId).orElse(null);
            } else {
                customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
            }
            if (customer == null) {
                log.warn("DataFill 客户不存在（重试后仍为空）: external={}", externalUserId);
                return;
            }
        }

        // 幂等检查：已补全的跳过
        if (!"未知".equals(customer.getName())
                && customer.getAvatar() != null
                && customer.getUnionid() != null) {
            return;
        }

        try {
            JsonNode detail = wecomApi.getExternalContact(externalUserId);
            if (!detail.has("external_contact")) {
                log.warn("DataFill API 返回无 external_contact: external={}", externalUserId);
                return;
            }

            JsonNode ec = detail.get("external_contact");
            boolean changed = false;

            if ("未知".equals(customer.getName()) && ec.has("name")) {
                customer.setName(ec.get("name").asText());
                changed = true;
            }
            if (customer.getAvatar() == null && ec.has("avatar")
                    && !ec.get("avatar").isNull()) {
                customer.setAvatar(ec.get("avatar").asText());
                changed = true;
            }
            if (customer.getUnionid() == null && ec.has("unionid")
                    && !ec.get("unionid").isNull()) {
                customer.setUnionid(ec.get("unionid").asText());
                changed = true;
            }
            if (ec.has("type")) {
                customer.setType(ec.get("type").asInt());
                changed = true;
            }

            if (changed) {
                customerRepo.save(customer);
                log.debug("客户信息补全: external={}, name={}", externalUserId,
                    customer.getName());
            }
        } catch (Exception e) {
            // API 调用失败不阻塞，依赖 repairCustomerData 兜底
            log.warn("DataFill API 失败（repairCustomerData 兜底）: external={}",
                externalUserId, e);
        }
    }
}
