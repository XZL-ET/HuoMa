package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.service.MessageGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维健康检查端点 —— 暴露 Stream 深度、PEL 积压、全局池余量等关键指标。
 *
 * <p>路径：GET /api/health/streams —— 供 Prometheus / 云监控 / 运维脚本抓取。</p>
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate redisTemplate;
    private final GlobalAgentPoolRepository poolRepo;
    private final MessageGuardService messageGuardService;

    @GetMapping("/api/health/streams")
    public Map<String, Object> streamHealth() {
        Map<String, Object> h = new LinkedHashMap<>();

        // CALLBACK_STREAM
        h.put("callback_stream_length", streamLen(RedisConfig.CALLBACK_STREAM_KEY));
        h.put("callback_pel_pending", pelPending(RedisConfig.CALLBACK_STREAM_KEY,
            RedisConfig.CALLBACK_CONSUMER_GROUP));

        // TAG_STREAM
        h.put("tag_stream_length", streamLen(RedisConfig.TAG_STREAM_KEY));
        h.put("tag_pel_pending", pelPending(RedisConfig.TAG_STREAM_KEY,
            RedisConfig.TAG_CONSUMER_GROUP));

        // DATAFILL_STREAM
        h.put("datafill_stream_length", streamLen(RedisConfig.DATAFILL_STREAM_KEY));
        h.put("datafill_pel_pending", pelPending(RedisConfig.DATAFILL_STREAM_KEY,
            RedisConfig.DATAFILL_CONSUMER_GROUP));

        // DLQ
        h.put("dlq_length", messageGuardService.dlqSize());

        // 全局池
        try {
            long standby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
            h.put("global_pool_standby", standby);
            h.put("global_pool_warning", standby < 10);
        } catch (Exception e) {
            h.put("global_pool_standby", "ERROR: " + e.getMessage());
        }

        // Redis 连通性检查
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            h.put("redis_alive", "PONG".equals(pong));
        } catch (Exception e) {
            h.put("redis_alive", false);
            h.put("redis_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        h.put("timestamp", Instant.now().toString());
        return h;
    }

    private long streamLen(String key) {
        try {
            Long len = redisTemplate.opsForStream().size(key);
            return len != null ? len : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private long pelPending(String key, String group) {
        try {
            PendingMessagesSummary p = redisTemplate.opsForStream().pending(key, group);
            return p != null ? p.getTotalPendingMessages() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * POST /api/health/dlq/replay — 将死信队列中的所有消息重放到指定 Stream。
     *
     * <p>重放后 DLQ 被清空。目标默认为 CALLBACK_STREAM，
     * 可通过 target 参数指定 tag / datafill / callback。</p>
     *
     * <p><b>安全提醒：</b>此端点应在确认死信原因后再调用，避免把坏消息反复重放。
     * 生产环境建议加权限控制或操作审计。</p>
     *
     * @param target 重放目标: "callback" / "tag" / "datafill"
     * @return 重放结果（数量 + 目标 Stream + 时间戳）
     */
    @PostMapping("/api/health/dlq/replay")
    public Map<String, Object> replayDlq(@RequestParam(defaultValue = "callback") String target) {
        String targetKey;
        switch (target) {
            case "tag":
                targetKey = RedisConfig.TAG_STREAM_KEY;
                break;
            case "datafill":
                targetKey = RedisConfig.DATAFILL_STREAM_KEY;
                break;
            default:
                targetKey = RedisConfig.CALLBACK_STREAM_KEY;
        }
        int count = messageGuardService.replayDlq(targetKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replayed", count);
        result.put("target", targetKey);
        result.put("timestamp", Instant.now().toString());
        return result;
    }
}
