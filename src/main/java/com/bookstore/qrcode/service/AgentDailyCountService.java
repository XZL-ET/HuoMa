package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 代理每日计数服务 — Redis INCR 原子计数 + Lua 过期保证。
 *
 * <p>从原 AgentBindService 拆分，专注 Redis 计数器操作。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDailyCountService {

    private final StringRedisTemplate redisTemplate;

    /** Lua: INCR + EXPIRE 原子化，防止计数器永不过期 */
    private static final String INCR_WITH_EXPIRE_LUA =
        "local val = redis.call('INCR', KEYS[1])\n"
        + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n"
        + "return val";

    /**
     * 原子递增日计数，返回递增后的值。
     * @param qrAgentId QR-代理关联 ID
     * @param delta 增量（通常为 1）
     * @return 递增后的日计数值
     */
    public long incrementDailyCount(Long qrAgentId, int delta) {
        String key = RedisConfig.DAILY_COUNT_KEY_PREFIX + qrAgentId;
        long secondsUntilMidnight = secondsUntilMidnight();
        Long val = redisTemplate.execute(
            new DefaultRedisScript<>(INCR_WITH_EXPIRE_LUA, Long.class),
            List.of(key),
            String.valueOf(secondsUntilMidnight));
        return val != null ? val : 0;
    }

    /** 查询当前日计数 */
    public long getDailyCount(Long qrAgentId) {
        String key = RedisConfig.DAILY_COUNT_KEY_PREFIX + qrAgentId;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0;
    }

    /** 午夜清零 — SCAN + DELETE（由 DailyResetWorker 调用） */
    public void resetDailyCounts() {
        var pattern = RedisConfig.DAILY_COUNT_KEY_PREFIX + "*";
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("每日计数器已清零: {} 个 key", keys.size());
        }
    }

    private long secondsUntilMidnight() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        long tomorrowStart = tomorrow.atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toEpochSecond();
        return Math.max(tomorrowStart - System.currentTimeMillis() / 1000, 60);
    }
}
