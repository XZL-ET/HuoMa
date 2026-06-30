package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.TransferService;
import com.bookstore.qrcode.service.MessageGuardService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferWorker {

    private final StringRedisTemplate redisTemplate;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final WecomApiClient wecomApi;
    private final MessageGuardService messageGuardService;

    private volatile boolean running = true;
    @Value("${app.worker.transfer.threads:2}")
    private int consumerThreads;
    @Value("${app.worker.transfer.delay-ms:200}")
    private long transferDelayMs;
    private static final String CONSUMER_PREFIX = "transfer-worker";

    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int tid = i;
            final String name = RedisConfig.consumerName(CONSUMER_PREFIX, tid);
            taskExecutor.execute(() -> consumeLoop(name, tid));
        }
        log.info("TransferWorker started {} threads", consumerThreads);
    }

    @PreDestroy
    public void shutdown() { running = false; }

    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.TRANSFER_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.TRANSFER_STREAM_KEY, ReadOffset.lastConsumed())
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
                            RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    try {
                        JsonNode event = objectMapper.readTree(eventJson);
                        Long customerId = event.has("customer_id")
                            ? event.get("customer_id").asLong() : null;
                        String fromUserid = getField(event, "from_userid");
                        String toUserid = getField(event, "to_userid");
                        String externalUserid = getField(event, "external_userid");
                        String state = getField(event, "state");

                        if (customerId != null && fromUserid != null
                                && externalUserid != null) {
                            transferService.initiate(customerId, fromUserid,
                                toUserid,  // 可为 null，initiate 自动查找服务老师
                                externalUserid, state);
                        } else {
                            log.warn("Transfer 事件缺少必要字段，跳过: msgId={}, customerId={}, from_userid={}, external_userid={}",
                                msgId, customerId, fromUserid, externalUserid);
                        }
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("Transfer 失败 (action={}): msgId={}", action, msgId, e);
                        handleError(action, msgId, fields, e);
                    } catch (Exception e) {
                        log.error("Transfer failed: msgId={}", msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }

                    if (transferDelayMs > 0) {
                        try { Thread.sleep(transferDelayMs); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }

                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.TRANSFER_STREAM_KEY, RedisConfig.STREAM_MAXLEN, true);
                } catch (Exception e) { log.debug("TRANSFER trim skip: {}", e.getMessage()); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.TRANSFER_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(RedisConfig.TRANSFER_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.TRANSFER_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.TRANSFER_STREAM_KEY, initId);
                    } catch (Exception e2) {}
                    continue;
                }
                log.error("TransferWorker-{} error, retry 5s", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void handleError(ErrorAction action, String msgId,
                              Map<String, String> fields, WecomApiException e) {
        switch (action) {
            case DLQ:
                messageGuardService.sendToDlq(RedisConfig.TRANSFER_STREAM_KEY, fields);
                redisTemplate.opsForStream().acknowledge(
                    RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                break;
            case REFRESH_TOKEN_AND_RETRY:
                wecomApi.refreshToken();
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
            case WAIT_AND_RETRY:
                if (e instanceof WecomRateLimitException rle) {
                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
            default:
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
        }
    }

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
