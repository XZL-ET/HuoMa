package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.bookstore.qrcode.wecom.*;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
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
 * Redis Stream 消费者 —— 异步发送欢迎语与表单链接。
 *
 * <p><b>工作模式：</b>基于 Redis Stream 的消费者组 (Consumer Group) 模型实现。
 * 在 {@link jakarta.annotation.PostConstruct} 阶段使用 {@code taskExecutor} 线程池
 * 启动多个常驻后台的消费循环，持续从 {@code wecom:outbound:stream} 拉取出站事件并处理。</p>
 *
 * <p><b>事件来源：</b>出站事件由 {@link com.bookstore.qrcode.worker.CallbackWorker CallbackWorker}
 * 在处理完客户添加回调后通过 XADD 发布到 {@link RedisConfig#OUTBOUND_STREAM_KEY}。
 * 本 Worker 独立消费，与回调主链路完全解耦，消息发送失败不影响客户入库和日计数。</p>
 *
 * <p><b>消息处理流程：</b>
 * <ol>
 *   <li>解析事件 JSON，提取 external_userid、userid、state、qr_code_id</li>
 *   <li>解析欢迎语：活码 welcomeText -> 分组 defaultWelcomeText -> 系统默认</li>
 *   <li>模板变量替换：{{school_name}}、{{teacher_name}}</li>
 *   <li>发送欢迎语文本</li>
 *   <li>若有表单模板，间隔 300ms 后发送表单链接</li>
 * </ol>
 * </p>
 *
 * <p><b>ACK 机制：</b>每条消息处理完成后（无论成功或失败）都会立即调用
 * {@code acknowledge} 确认消费，避免阻塞 Stream 的 Pending 队列。
 * 处理单条消息的异常被 catch 后不会影响同批次其他消息的消费。</p>
 *
 * <p><b>优雅关闭：</b>通过 volatile {@code running} 标志控制循环退出。
 * 当线程被 {@link InterruptedException} 中断时，退出循环并记录警告日志。</p>
 *
 * @author Bookstore Dev
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundMsgWorker {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final WecomApiClient wecomApi;
    private final MessageGuardService messageGuardService;
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final CustomerRepository customerRepo;

    private volatile boolean running = true;
    @Value("${app.worker.outbound.threads:4}")
    private int consumerThreads;
    @Value("${app.base-url:https://your-domain.com}")
    private String baseUrl;
    private static final String CONSUMER_PREFIX = "outbound-worker";

    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int tid = i;
            final String name = RedisConfig.consumerName(CONSUMER_PREFIX, tid);
            taskExecutor.execute(() -> consumeLoop(name, tid));
        }
        log.info("OutboundMsgWorker started {} threads", consumerThreads);
    }

    @PreDestroy
    public void shutdown() { running = false; }

    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.OUTBOUND_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.OUTBOUND_STREAM_KEY, ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    String msgId = record.getId().getValue();
                    String eventJson = (String) record.getValue().get("event");
                    if (eventJson == null) {
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    String retryAt = (String) record.getValue().get("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > java.time.Instant.now().getEpochSecond()) {
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    try {
                        processEvent(eventJson);
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("OutboundMsg 失败 (action={}): msgId={}", action, msgId, e);
                        switch (action) {
                            case DLQ:
                                messageGuardService.sendToDlq(RedisConfig.OUTBOUND_STREAM_KEY, fields);
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                                break;
                            case REFRESH_TOKEN_AND_RETRY:
                                wecomApi.refreshToken();
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            case WAIT_AND_RETRY:
                                if (e instanceof WecomRateLimitException rle) {
                                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            default:
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                        }
                    } catch (Exception e) {
                        log.error("OutboundMsg failed: msgId={}", msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }
                }

                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.OUTBOUND_STREAM_KEY, 10000, true);
                } catch (Exception e) { log.debug("OUTBOUND trim skip: {}", e.getMessage()); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.OUTBOUND_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(RedisConfig.OUTBOUND_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.OUTBOUND_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.OUTBOUND_STREAM_KEY, initId);
                    } catch (Exception e2) {}
                    continue;
                }
                log.error("OutboundWorker-{} error, retry 5s", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void processEvent(String eventJson) throws Exception {
        JsonNode event = objectMapper.readTree(eventJson);
        String externalUserId = getField(event, "external_userid");
        String userid = getField(event, "userid");
        String state = getField(event, "state");
        Long qrCodeId = event.has("qr_code_id") && !event.get("qr_code_id").isNull()
            ? event.get("qr_code_id").asLong() : null;
        String customerId = getField(event, "customer_id");

        if (externalUserId == null || userid == null) return;

        // Resolve welcome text: qrCode.welcomeText -> group default -> system default
        String welcomeText = null;
        Long formTemplateId = null;

        if (qrCodeId != null) {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null) {
                welcomeText = qr.getWelcomeText();
                formTemplateId = qr.getFormTemplateId();
                // Inherit from group
                if (welcomeText == null && qr.getGroupId() != null) {
                    QrCodeGroup grp = groupRepo.findById(qr.getGroupId()).orElse(null);
                    if (grp != null) {
                        welcomeText = grp.getDefaultWelcomeText();
                        if (formTemplateId == null) formTemplateId = grp.getDefaultFormTemplateId();
                    }
                }
            }
        }
        // System default fallback
        if (welcomeText == null) {
            welcomeText = systemConfigRepo.findByConfigKey("default_welcome_text")
                .map(SystemConfig::getConfigValue).orElse("欢迎来到XX书店家校服务！");
        }

        // Template variable replacement
        String schoolName = "";
        String teacherName = "";
        if (qrCodeId != null) {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null) schoolName = qr.getSchoolName();
        }
        // Get teacher name from Employee table or Agent table
        teacherName = userid; // fallback
        try {
            JsonNode ul = wecomApi.getUserSimplelist();
            for (JsonNode u : ul.get("userlist")) {
                if (userid.equals(u.get("userid").asText())) {
                    teacherName = u.has("name") ? u.get("name").asText() : userid;
                    break;
                }
            }
        } catch (Exception ignored) {}

        welcomeText = welcomeText
            .replace("{{school_name}}", schoolName)
            .replace("{{teacher_name}}", teacherName);

        // 1. Send welcome text
        wecomApi.sendMessage(userid, externalUserId, welcomeText);
        log.info("欢迎语已发送: to={}, sender={}", externalUserId, userid);

        // 2. Send form link as a textcard (card-style message, separate from welcome text)
        if (formTemplateId != null) {
            Thread.sleep(300);
            String formUrl = baseUrl + "/form/" + qrCodeId + "?c=" + (customerId != null ? customerId : "");
            String cardTitle = "📋 请填写孩子信息";
            String cardDesc = "<div class=\"normal\">为了更好地为您提供精准服务，请点击下方填写孩子信息</div>";
            wecomApi.sendTextCard(userid, externalUserId, cardTitle, cardDesc, formUrl, "去填写");
            log.info("表单卡片已发送: to={}", externalUserId);
        }
    }

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
