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
    private final FormTemplateRepository formTemplateRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;

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

        // === 欢迎语 & 表单模板继承链解析 ===
        // 链：QrCode → Group → SchoolCategory → SystemConfig（两字段独立解析）
        String welcomeText = null;
        Long formTemplateId = null;
        String schoolName = "";

        if (qrCodeId != null) {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null) {
                welcomeText = qr.getWelcomeText();
                formTemplateId = qr.getFormTemplateId();
                schoolName = qr.getSchoolName();  // 复用此处查询，避免重复查库

                // L2: 分组继承（isBlank 过滤空字符串，防止穿透到 API 导致 40063）
                if (qr.getGroupId() != null) {
                    QrCodeGroup grp = groupRepo.findById(qr.getGroupId()).orElse(null);
                    if (grp != null) {
                        if (isBlank(welcomeText)) welcomeText = grp.getDefaultWelcomeText();
                        if (formTemplateId == null) formTemplateId = grp.getDefaultFormTemplateId();
                    }
                }

                // L3: 学校分类继承（新增 — 通过 qr.schoolId → school.categoryId 解析）
                if ((isBlank(welcomeText) || formTemplateId == null) && qr.getSchoolId() != null) {
                    School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
                    if (school != null && school.getCategoryId() != null) {
                        SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                        if (cat != null) {
                            if (isBlank(welcomeText)) welcomeText = cat.getDefaultWelcomeText();
                            if (formTemplateId == null) formTemplateId = cat.getDefaultFormTemplateId();
                        }
                    }
                }
            }
        }
        // L4: 系统默认（仅欢迎语有全局兜底）
        // filter(isBlank) 防止 DB 中存在空字符串时 orElse 不生效
        if (isBlank(welcomeText)) {
            welcomeText = systemConfigRepo.findByConfigKey("default_welcome_text")
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse("欢迎来到XX书店家校服务！");
        }

        // Template variable replacement
        String teacherName = "";
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

        // Extract welcome_code for send_welcome_msg API (more reliable than sendMessage)
        String welcomeCode = event.has("welcome_code") && !event.get("welcome_code").isNull()
            ? event.get("welcome_code").asText() : null;

        // Build form link attachment (common to both paths)
        // Read card content from template, fall back to defaults
        FormTemplate formTpl = null;
        String cardTitle = "📋 请填写孩子信息";
        String cardDesc = "为了更好地为您提供精准服务，请点击填写孩子信息";
        String cardPicUrl = null;
        if (formTemplateId != null) {
            formTpl = formTemplateRepo.findById(formTemplateId).orElse(null);
            if (formTpl != null) {
                if (formTpl.getCardTitle() != null && !formTpl.getCardTitle().isBlank())
                    cardTitle = formTpl.getCardTitle();
                if (formTpl.getCardDesc() != null && !formTpl.getCardDesc().isBlank())
                    cardDesc = formTpl.getCardDesc();
                if (formTpl.getCardPicUrl() != null && !formTpl.getCardPicUrl().isBlank())
                    cardPicUrl = formTpl.getCardPicUrl();
            }
        }

        Map<String, Object> formAttach = null;
        if (formTemplateId != null) {
            String formUrl = baseUrl + "/form/" + qrCodeId + "?c=" + (customerId != null ? customerId : "");
            Map<String, Object> linkMap = new java.util.LinkedHashMap<>();
            linkMap.put("title", cardTitle);
            linkMap.put("desc", cardDesc);
            linkMap.put("url", formUrl);
            if (cardPicUrl != null) {
                linkMap.put("picurl", cardPicUrl);
            }
            formAttach = Map.of(
                "msgtype", "link",
                "link", linkMap
            );
        }

        // 1. Send welcome text
        // Prefer send_welcome_msg (uses WelcomeCode, no daily rate limit) over sendMessage.
        // When welcome_code is available, attach form link directly — one API call for both.
        boolean sent = false;
        if (!isBlank(welcomeCode)) {
            try {
                List<Map<String, Object>> attachments = formAttach != null
                    ? List.of(formAttach) : null;
                log.info("send_welcome_msg 请求: welcomeCode={}, text.len={}, hasAttach={}",
                    welcomeCode.substring(0, Math.min(20, welcomeCode.length())),
                    welcomeText != null ? welcomeText.length() : 0,
                    attachments != null);
                wecomApi.sendWelcomeMsg(welcomeCode, welcomeText, attachments);
                sent = true;
                log.info("欢迎语+表单已通过 send_welcome_msg 发送: to={}, hasForm={}",
                    externalUserId, formAttach != null);
            } catch (Exception e) {
                // welcome_code expired (valid ~20s) or already used — fallback to sendMessage
                log.warn("send_welcome_msg 失败，降级到 sendMessage: external={}", externalUserId, e);
            }
        }
        if (!sent) {
            try {
                wecomApi.sendMessage(userid, externalUserId, welcomeText);
                log.info("欢迎语已通过 sendMessage 发送: to={}, sender={}", externalUserId, userid);
            } catch (Exception e) {
                // sendMessage 也失败（如 48002 api forbidden）→ 不抛异常，避免死循环入 DLQ
                // 若 sendWelcomeMsg 已在上一次尝试中成功发出欢迎语，客户已收到，此处只是重复重试
                log.error("sendMessage 失败，欢迎语可能未发出: to={}, sender={}",
                    externalUserId, userid, e);
            }
        }

        // 2. Send form link — only needed when welcome_code was NOT available
        //    (because send_welcome_msg already includes the attachment)
        if (formAttach != null && !sent) {
            try {
                Thread.sleep(300);
                String formUrl = baseUrl + "/form/" + qrCodeId + "?c=" + (customerId != null ? customerId : "");
                log.info("发送表单卡片(sendTextCard): to={}, qrCodeId={}, formTemplateId={}, url={}",
                    externalUserId, qrCodeId, formTemplateId, formUrl);
                wecomApi.sendTextCard(userid, externalUserId,
                    cardTitle,
                    "<div class=\"normal\">" + cardDesc + "</div>",
                    formUrl, "去填写");
                log.info("表单卡片已发送(sendTextCard): to={}", externalUserId);
                sent = true;
            } catch (Exception e) {
                // 表单卡片失败不回滚欢迎语（欢迎语已发送，retry 会导致重复）
                log.error("表单卡片发送失败（欢迎语已发送，不回滚）: to={}, qrCodeId={}",
                    externalUserId, qrCodeId, e);
            }
        }

        if (formTemplateId == null) {
            log.info("未发送表单卡片: to={}, qrCodeId={}, 原因: formTemplateId=null (活码未绑定且分组未设置默认表单模板)",
                externalUserId, qrCodeId);
        }
    }

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }

    /** 等价于 {@code s == null || s.isBlank()}，避免 NPE 并统一空值判断语义 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
