package com.bookstore.qrcode.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 学校自助查询 IP 频控过滤器 — Redis 主 + Caffeine 降级。
 * <p>
 * 仅拦截 /s/** 路径，对同一 IP 做滑动窗口限流。
 * 主路径使用 Redis Lua 脚本（Sorted Set 滑动窗口），多实例共享计数；
 * Redis 不可用时自动降级到 Caffeine 本地缓存，保证可用性。
 * </p>
 */
@Slf4j
public class SchoolRateLimitFilter implements Filter {

    private static final String RATE_CHECK_LUA =
            "local key = KEYS[1]\n"
                    + "local now = tonumber(ARGV[1])\n"
                    + "local window = tonumber(ARGV[2])\n"
                    + "local member = ARGV[3]\n"
                    + "local maxCount = tonumber(ARGV[4])\n"
                    + "local ttl = tonumber(ARGV[5])\n"
                    + "redis.call('ZADD', key, now, member)\n"
                    + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
                    + "redis.call('EXPIRE', key, ttl)\n"
                    + "local count = redis.call('ZCARD', key)\n"
                    + "return count";

    private static final DefaultRedisScript<Long> RATE_SCRIPT;

    static {
        RATE_SCRIPT = new DefaultRedisScript<>();
        RATE_SCRIPT.setScriptText(RATE_CHECK_LUA);
        RATE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate rateLimitRedis;
    private final int maxPerMinute;
    private final long windowMs;

    // Caffeine 本地降级缓存
    private final Map<String, SlidingWindow> fallbackWindows = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .<String, SlidingWindow>build()
            .asMap();

    public SchoolRateLimitFilter(
            StringRedisTemplate rateLimitRedis,
            int maxPerMinute) {
        this.rateLimitRedis = rateLimitRedis;
        this.maxPerMinute = maxPerMinute;
        this.windowMs = 60_000;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        String uri = request.getRequestURI();
        if (!uri.startsWith("/s/") && !uri.equals("/s")) {
            chain.doFilter(req, resp);
            return;
        }

        String ip = request.getRemoteAddr();
        boolean exceeded;

        try {
            exceeded = checkRedisRate(ip);
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn("Redis 限流不可用，降级为本地计数: ip={}", ip);
            exceeded = checkLocalRate(ip);
        }

        if (exceeded) {
            response.setStatus(429);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("请求过于频繁，请稍后再试");
            log.warn("Rate limit exceeded for IP: {}", ip);
            return;
        }

        chain.doFilter(req, resp);
    }

    private boolean checkRedisRate(String ip) {
        long now = Instant.now().getEpochSecond();
        String member = now + ":" + System.nanoTime();
        String key = RedisConfig.SCHOOL_RATE_KEY_PREFIX + ip;

        Long count = rateLimitRedis.execute(
                RATE_SCRIPT,
                java.util.List.of(key),
                String.valueOf(now), String.valueOf(windowMs / 1000), member,
                String.valueOf(maxPerMinute), "120");

        return count != null && count > maxPerMinute;
    }

    private boolean checkLocalRate(String ip) {
        SlidingWindow window = fallbackWindows.computeIfAbsent(ip, k -> new SlidingWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            window.prune(now);
            if (window.count >= maxPerMinute) {
                return true;
            }
            window.hits[window.head] = now;
            window.head = (window.head + 1) % window.hits.length;
            window.count++;
            return false;
        }
    }

    /**
     * 滑动窗口数据结构，用于本地降级限流。
     * <p>
     * 使用固定大小环形数组记录时间戳，prune() 清除窗口外旧数据。
     * </p>
     */
    private class SlidingWindow {
        long[] hits = new long[maxPerMinute];
        int head = 0;
        int count = 0;

        void prune(long now) {
            long cutoff = now - windowMs;
            int newCount = 0;
            int tail = (head - count + hits.length) % hits.length;
            for (int i = 0; i < count; i++) {
                int idx = (tail + i) % hits.length;
                if (hits[idx] >= cutoff) {
                    hits[(head - newCount + hits.length) % hits.length] = hits[idx];
                    newCount++;
                }
            }
            count = newCount;
        }
    }
}
