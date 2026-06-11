package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

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

    /**
     * 记录员工一次好友添加操作，检测当前速率并决定是否触发降速或熔断。
     *
     * <p>每次收到企微回调 {@code add_external_contact} 时调用。
     * 同时写入 15 秒和 60 秒两个滑动窗口，然后按优先级检查：</p>
     * <ol>
     *   <li>先检查 60 秒窗口（持续性高负载 → 熔断）</li>
     *   <li>再检查 15 秒窗口（突发峰值 → 降速警告 + 微延迟）</li>
     * </ol>
     *
     * <p>之所以先检查 60 秒窗口，是因为熔断是比降速更严重的处置动作，
     * 需要优先处理。如果同时触发了两个阈值，熔断优先。</p>
     *
     * @param userId 员工 userid
     */
    public void recordAdd(String userId) {
        long now = Instant.now().getEpochSecond();

        // Redis Sorted Set 数据结构：key = rate:{userId}:{windowSize}
        // score = 当前时间戳（秒），member = "timestamp:nanotime"（确保唯一性）
        // 使用时间戳纳秒拼接作为 member，避免高并发下同秒内的重复 member 被去重
        String key15s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":15s";
        String key60s = RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":60s";

        String member = now + ":" + System.nanoTime();

        // ===== 15 秒窗口（捕捉突发峰值） =====
        // 1. 写入当前记录，score=now
        redisTemplate.opsForZSet().add(key15s, member, now);
        // 2. 移除 15 秒前的过期记录，保持窗口始终为最近 15 秒
        redisTemplate.opsForZSet().removeRangeByScore(key15s, 0, now - 15);
        // 3. 设置 TTL 为 30 秒（略大于窗口大小），确保 Redis 内存及时释放
        redisTemplate.expire(key15s, 30, TimeUnit.SECONDS);

        // ===== 60 秒窗口（评估持续吞吐量） =====
        redisTemplate.opsForZSet().add(key60s, member, now);
        redisTemplate.opsForZSet().removeRangeByScore(key60s, 0, now - 60);
        redisTemplate.expire(key60s, 120, TimeUnit.SECONDS);

        // 查询两个窗口的当前计数
        Long count15s = redisTemplate.opsForZSet().zCard(key15s);
        Long count60s = redisTemplate.opsForZSet().zCard(key60s);

        // 熔断优先于降速：60 秒窗口反映持续吞吐量，超过阈值说明员工操作过于密集
        if (count60s != null && count60s > WINDOW_60S_MAX) {
            log.error("员工 {} 1分钟内添加 {} 人，触发熔断！", userId, count60s);
            alertService.meltAgent(userId, null,
                String.format("1分钟内添加 %d 人，超过阈值 %d", count60s, WINDOW_60S_MAX));
        } else if (count15s != null && count15s > WINDOW_15S_MAX) {
            // 15 秒窗口超过阈值说明有短时突发峰值
            // 此时仅告警 + 插入 100ms 微延迟，让操作自然降速
            // 不直接熔断的原因是：突发峰值可能是客户集中扫码导致的，员工操作未必有问题
            log.warn("员工 {} 15秒内添加 {} 人，建议降速", userId, count15s);
            try {
                Thread.sleep(100); // 人为插入微延迟，将集中请求在时间轴上打散
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
