package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 速率控制 — Redis 滑窗计数，防止企微风控。
 *
 * 第一层：15 秒窗口 > 20 人 → 降速（日志警告）
 * 第二层：1 分钟窗口 > 60 人 → 熔断（调用 AlertService）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;

    /** 15 秒窗口阈值 */
    private static final int WINDOW_15S_MAX = 20;
    /** 1 分钟窗口阈值 */
    private static final int WINDOW_60S_MAX = 60;

    /**
     * 记录员工一次添加，检测速率。
     * 每次回调 add_external_contact 时调用。
     */
    public void recordAdd(String userId) {
        long now = Instant.now().getEpochSecond();

        // Redis Sorted Set: rate:{userId}，score=timestamp，member=timestamp:random
        String key15s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":15s";
        String key60s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":60s";

        String member = now + ":" + System.nanoTime();

        // 写入 15s 窗口
        redisTemplate.opsForZSet().add(key15s, member, now);
        // 删除 15 秒前的数据
        redisTemplate.opsForZSet().removeRangeByScore(key15s, 0, now - 15);
        // 设置过期
        redisTemplate.expire(key15s, 30, TimeUnit.SECONDS);

        // 写入 60s 窗口
        redisTemplate.opsForZSet().add(key60s, member, now);
        redisTemplate.opsForZSet().removeRangeByScore(key60s, 0, now - 60);
        redisTemplate.expire(key60s, 120, TimeUnit.SECONDS);

        // 检查阈值
        Long count15s = redisTemplate.opsForZSet().zCard(key15s);
        Long count60s = redisTemplate.opsForZSet().zCard(key60s);

        if (count60s != null && count60s > WINDOW_60S_MAX) {
            // 🔴 熔断
            log.error("员工 {} 1分钟内添加 {} 人，触发熔断！", userId, count60s);
            alertService.meltAgent(userId, null,
                String.format("1分钟内添加 %d 人，超过阈值 %d", count60s, WINDOW_60S_MAX));
        } else if (count15s != null && count15s > WINDOW_15S_MAX) {
            // ⚠️ 降速警告
            log.warn("员工 {} 15秒内添加 {} 人，建议降速", userId, count15s);
            try {
                Thread.sleep(100); // 微延迟，让流量自然分散
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 获取某员工的当前速率（管理后台用）。
     * @return [15s速率, 60s速率]
     */
    public long[] getRate(String userId) {
        long now = Instant.now().getEpochSecond();
        String key15s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":15s";
        String key60s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":60s";

        redisTemplate.opsForZSet().removeRangeByScore(key15s, 0, now - 15);
        redisTemplate.opsForZSet().removeRangeByScore(key60s, 0, now - 60);

        Long c15 = redisTemplate.opsForZSet().zCard(key15s);
        Long c60 = redisTemplate.opsForZSet().zCard(key60s);

        return new long[]{c15 != null ? c15 : 0, c60 != null ? c60 : 0};
    }
}
