package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务的跨实例互斥与限流。
 *
 * <p><b>为什么需要：</b>生产是 ALB 后双节点 active-active（同一个 RDS + Redis）。
 * {@code @Scheduled} 任务在两个 JVM 上各自触发，没有共享协调就会双跑 ——
 * 每日重置撞唯一键、PEL 回收把共享重试计数翻倍、告警逐条成对。
 * 本组件用 Redis 把「同一时刻只有一台执行」与「每窗口最多一次」落到实处。</p>
 *
 * <p><b>失败语义（fail-open）：</b>Redis 不可用时 {@link #tryAcquire} / {@link #firstWithin}
 * 记 {@code warn} 并**放行**。理由：Redis 抖动时若改成跳过，每日重置、日报、员工同步等
 * 关键任务会整轮不执行（现有 {@code DailyResetWorker} 对 Redis 故障本就是容错的：
 * 步骤 1 失败但步骤 2-5 的 DB 重置照跑）。代价是故障期间双跑会短暂回归 —— 比任务不跑轻得多。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledJobGuard {

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试获取任务锁。
     *
     * @return 获取成功 → 锁令牌（释放时原样传回）；被其他实例占用 → {@code null}；
     *         Redis 异常 → 一个新令牌（fail-open，调用方照常执行；该令牌从未写入 Redis，
     *         释放时因比对不上而不会误删他机的锁）
     */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            log.warn("任务锁获取失败，降级为直接执行: key={}", key, e);
            return token;
        }
    }

    /** 释放任务锁；仅当锁仍属于本令牌时才删除（{@link RedisConfig#SAFE_UNLOCK_SCRIPT}）。异常吞掉，随 TTL 过期。 */
    public void release(String key, String token) {
        if (token == null) return;
        try {
            redisTemplate.execute(RedisConfig.SAFE_UNLOCK_SCRIPT, List.of(key), token);
        } catch (Exception e) {
            log.debug("任务锁释放失败（将随 TTL 过期）: key={}", key, e);
        }
    }

    /**
     * 获取到锁才执行 {@code body}，否则跳过。
     *
     * @return 是否执行了 {@code body}
     */
    public boolean runExclusive(String key, Duration ttl, Runnable body) {
        String token = tryAcquire(key, ttl);
        if (token == null) {
            log.debug("任务被其他实例执行中，跳过: key={}", key);
            return false;
        }
        try {
            body.run();
        } finally {
            release(key, token);
        }
        return true;
    }

    /**
     * 窗口内首次调用返回 {@code true}（并把窗口键写入 Redis），窗口内后续调用返回 {@code false}。
     * 用于把原先的进程内告警限流（每小时最多一条）改成跨实例生效。
     *
     * <p>Redis 异常时放行（fail-open），与 {@link #tryAcquire} 一致。
     */
    public boolean firstWithin(String key, Duration window) {
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("限流键操作失败，降级为放行: key={}", key, e);
            return true;
        }
    }

    /**
     * 撤销 {@link #firstWithin} 刚占下的窗口键 —— 供「先占窗口、再发告警、告警失败就退窗」的场景使用。
     *
     * <p>典型是 {@code StreamBacklogMonitor.raise()}：告警本身是尽力而为的
     * （{@code AlertService#createAlert} 失败时静默返回 null），若先占窗口再发失败，
     * 这条告警会彻底消失、还要被压制一整个窗口。退窗后下一轮可重试。</p>
     *
     * <p>DELETE 是幂等的：即便键已过期或不存在也不报错。</p>
     */
    public void clearThrottle(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("限流键撤销失败（将随 TTL 过期）: key={}", key, e);
        }
    }
}
