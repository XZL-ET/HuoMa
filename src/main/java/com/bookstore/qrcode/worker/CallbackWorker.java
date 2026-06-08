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
 * Redis Stream 消费者 — 异步处理企微回调事件。
 * 从 Stream 拉取 → 解析事件类型 → 路由到对应的 Service 处理。
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

    @PostConstruct
    public void start() {
        callbackExecutor.execute(this::consumeLoop);
        log.info("CallbackWorker 已启动, Stream={}, Group={}",
            RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP);
    }

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
     * 根据事件类型路由处理。
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
     * 处理外部联系人变更事件（包含添加成功和删除）。
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
     * 添加成功：打标 → 记录客户 → 记数 → 速率检测 → 继承。
     * 每一步独立 catch，单步失败不影响后续处理。
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

        // ② 自动打标（市/区/学校）— 失败不阻塞后续
        if (state != null) {
            try {
                tagService.autoTag(externalUserId, userId, state);
            } catch (Exception e) {
                log.error("自动打标失败（非阻塞）: external={}, state={}", externalUserId, state, e);
            }
        }

        // ③ 记录/更新客户信息
        Long customerId = null;
        try {
            customerId = customerService.upsertFromCallback(externalUserId, userId, state);
        } catch (Exception e) {
            log.error("记录客户失败（非阻塞）: external={}", externalUserId, e);
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

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }

    private String extractXmlTag(String xml, String tag) {
        if (xml == null) return null;
        // CDATA 包裹优先（企微回调均使用 CDATA）
        String startTag = "<" + tag + "><![CDATA[";
        String endTag = "]]></" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // 标准格式
        startTag = "<" + tag + ">";
        endTag = "</" + tag + ">";
        start = xml.indexOf(startTag);
        end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            String content = xml.substring(start + startTag.length(), end);
            if (content.startsWith("<![CDATA[")) {
                content = content.substring(9, content.length() - 3);
            }
            return content;
        }
        return null;
    }
}
