package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务锁与限流的语义，重点是 Redis 故障时的 fail-open。
 *
 * <p>{@code tryAcquire} 在 Redis 异常时返回一个「从未写入 Redis」的令牌放行，
 * 该令牌随后释放时因比对不上而不会误删他机的锁 —— 这条不能只靠读代码相信，要断言。</p>
 */
@DisplayName("定时任务跨实例互斥与限流")
class ScheduledJobGuardTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ScheduledJobGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        guard = new ScheduledJobGuard(redisTemplate);
    }

    @Test
    @DisplayName("setIfAbsent 成功 → 拿到令牌；被占用 → 返回 null")
    void acquireReflectsSetIfAbsent() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(guard.tryAcquire("k", Duration.ofMinutes(1))).isNotNull();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(guard.tryAcquire("k", Duration.ofMinutes(1))).isNull();
    }

    @Test
    @DisplayName("Redis 异常 → fail-open 返回令牌（照常执行），且释放时不会误删他机锁")
    void failOpenOnRedisError() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RuntimeException("connection refused"));

        String token = guard.tryAcquire("k", Duration.ofMinutes(1));

        assertThat(token).as("Redis 不可用不能把关键任务停掉").isNotNull();
        // 该令牌未写入 Redis：释放走 SAFE_UNLOCK，GET 比对不上 → 不会 DEL 掉别人的锁
        guard.release("k", token);
        verify(redisTemplate).execute(eq(RedisConfig.SAFE_UNLOCK_SCRIPT), eq(List.of("k")), eq(token));
    }

    @Test
    @DisplayName("持锁时执行任务体并在结束后释放；未持锁则跳过且不释放")
    void runExclusiveRunsBodyOnlyWhenLocked() {
        AtomicBoolean ran = new AtomicBoolean(false);

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(guard.runExclusive("k", Duration.ofMinutes(1), () -> ran.set(true))).isFalse();
        assertThat(ran).isFalse();
        verify(redisTemplate, never()).execute(any(), any(), anyString());

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(guard.runExclusive("k", Duration.ofMinutes(1), () -> ran.set(true))).isTrue();
        assertThat(ran).isTrue();
        verify(redisTemplate).execute(eq(RedisConfig.SAFE_UNLOCK_SCRIPT), eq(List.of("k")), anyString());
    }

    @Test
    @DisplayName("任务体抛异常也要释放锁，否则锁会一直占到 TTL 过期")
    void releasesEvenWhenBodyThrows() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        try {
            guard.runExclusive("k", Duration.ofMinutes(1), () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException ignored) {
            // 异常照常向上抛给调度器
        }

        verify(redisTemplate).execute(eq(RedisConfig.SAFE_UNLOCK_SCRIPT), eq(List.of("k")), anyString());
    }

    @Test
    @DisplayName("firstWithin：窗口内首次 true、再次 false；Redis 异常时放行")
    void firstWithinWindowSemantics() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true, false);
        assertThat(guard.firstWithin("w", Duration.ofHours(1))).isTrue();
        assertThat(guard.firstWithin("w", Duration.ofHours(1))).isFalse();

        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenThrow(new RuntimeException("down"));
        assertThat(guard.firstWithin("w", Duration.ofHours(1)))
            .as("告警限流不应因 Redis 故障而整块静默").isTrue();
    }
}
