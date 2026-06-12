package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 速率控制服务 — 基于 Redis Sorted Set 的滑动窗口计数，防止企微风控。
 *
 * <h3>算法原理</h3>
 * <p>采用 Redis Sorted Set 实现滑动窗口计数器：</p>
 * <ul>
 *   <li>member = 当前时间戳 + 随机纳秒数（确保唯一性）</li>
 *   <li>score = 当前时间戳（秒级），按时间排序</li>
 *   <li>每次写入后移除窗口外的旧数据：{@code ZREMRANGEBYSCORE key 0 now-windowSize}</li>
 *   <li>通过 {@code ZCARD} 获取窗口内有效请求数</li>
 * </ul>
 *
 * <h3>双窗口熔断机制</h3>
 * <table>
 *   <tr><th>窗口</th><th>阈值</th><th>触发动作</th><th>说明</th></tr>
 *   <tr><td>15 秒</td><td>&gt; 20 人</td><td>降速警告 + 100ms 微延迟</td><td>第一层防护，让流量自然分散</td></tr>
 *   <tr><td>60 秒</td><td>&gt; 60 人</td><td>熔断({@link AlertService#meltAgent})</td><td>第二层防护，直接熔断防止风控</td></tr>
 * </table>
 *
 * <p>设计思路：15 秒窗口捕捉突发峰值(burst)，60 秒窗口评估持续吞吐量(sustained)，
 * 两层配合既避免误熔断短时波动，又能及时阻断持续性高频操作。</p>
 *
 * @author 书店技术团队
 * @since 1.0.0
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

    /** 原子化滑动窗口 Lua 脚本：ZADD + ZREMRANGE + ZCARD + EXPIRE 一步完成 */
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

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RATE_CHECK_SCRIPT;

    static {
        RATE_CHECK_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        RATE_CHECK_SCRIPT.setScriptText(RATE_CHECK_LUA);
        RATE_CHECK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 记录员工一次好友添加操作，原子化检测速率并触发熔断/告警。
     *
     * <p>使用 Redis Lua 脚本确保滑动窗口的 ZADD + ZREMRANGE + ZCARD 原子执行，
     * 消除多线程并发下的计数漏判。移除了 Thread.sleep(100) 阻塞调用，
     * 降速仅记录告警日志，由 60 秒窗口的熔断机制兜底。</p>
     *
     * @param userId 员工 userid
     */
    public void recordAdd(String userId) {
        long now = Instant.now().getEpochSecond();
        String member = now + ":" + System.nanoTime();

        // 15 秒窗口原子检查（突发峰值）
        Long count15s = redisTemplate.execute(
            RATE_CHECK_SCRIPT,
            java.util.List.of(RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":15s"),
            String.valueOf(now), "15", member,
            String.valueOf(WINDOW_15S_MAX), "30");

        // 60 秒窗口原子检查（持续吞吐）
        Long count60s = redisTemplate.execute(
            RATE_CHECK_SCRIPT,
            java.util.List.of(RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":60s"),
            String.valueOf(now), "60", member,
            String.valueOf(WINDOW_60S_MAX), "120");

        int c60 = count60s != null ? count60s.intValue() : 0;
        int c15 = count15s != null ? count15s.intValue() : 0;

        // 熔断优先于降速
        if (c60 > WINDOW_60S_MAX) {
            log.error("员工 {} 1分钟内添加 {} 人，触发熔断！", userId, c60);
            alertService.meltAgent(userId, null,
                String.format("1分钟内添加 %d 人，超过阈值 %d", c60, WINDOW_60S_MAX));
        } else if (c15 > WINDOW_15S_MAX) {
            // 突发峰值仅告警，不再阻塞线程
            log.warn("员工 {} 15秒内添加 {} 人（阈值 {}），建议关注",
                userId, c15, WINDOW_15S_MAX);
        }
    }

    /**
     * 获取某员工当前的实时速率。
     *
     * <p>用于运营管理后台展示员工的实时操作频率，便于运营人员判断是否需要人工干预。
     * 查询时也会先清理过期数据，确保返回的计数是准确的实时值。</p>
     *
     * @param userId 员工 userid
     * @return 长度为 2 的 long 数组，[0] = 15 秒窗口计数，[1] = 60 秒窗口计数
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
