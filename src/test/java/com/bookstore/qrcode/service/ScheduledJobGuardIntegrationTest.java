package com.bookstore.qrcode.service;

import com.bookstore.qrcode.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑在真实 Redis 上的跨实例互斥验证。
 *
 * <p>单测里 {@code setIfAbsent} 的返回值是 mock 出来的，证明不了「两个 JVM 共享一个 Redis 时，
 * 后到的确会被挡住」。这里用两个 guard 实例（同一个真实 Redis）直接复现双机争锁。</p>
 */
@DisplayName("任务锁跨实例互斥（真实 Redis）")
class ScheduledJobGuardIntegrationTest extends BaseIntegrationTest {

    private static final String KEY = "lock:test:cross-node";
    private static final String THROTTLE_KEY = "throttle:test:window";

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ScheduledJobGuard nodeA;

    /** 模拟另一台节点：同一 Redis、独立实例 */
    private ScheduledJobGuard nodeB;

    @BeforeEach
    void setUp() {
        nodeB = new ScheduledJobGuard(redisTemplate);
        redisTemplate.delete(KEY);
        redisTemplate.delete(THROTTLE_KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
        redisTemplate.delete(THROTTLE_KEY);
    }

    @Test
    @DisplayName("A 持锁期间 B 拿不到；A 释放后 B 才能拿到")
    void onlyOneNodeHoldsTheLockAtATime() {
        String tokenA = nodeA.tryAcquire(KEY, Duration.ofMinutes(1));
        assertThat(tokenA).as("A 先到应拿到锁").isNotNull();

        assertThat(nodeB.tryAcquire(KEY, Duration.ofMinutes(1)))
            .as("B 在 A 持锁期间必须被挡住 —— 否则又是双跑").isNull();

        nodeA.release(KEY, tokenA);

        assertThat(nodeB.tryAcquire(KEY, Duration.ofMinutes(1)))
            .as("A 释放后 B 应能接管").isNotNull();
    }

    @Test
    @DisplayName("B 释放时不能删掉 A 的锁（令牌不匹配）")
    void releaseDoesNotDropAnotherNodesLock() {
        String tokenA = nodeA.tryAcquire(KEY, Duration.ofMinutes(1));
        assertThat(tokenA).isNotNull();

        nodeB.release(KEY, "not-a-real-token");

        assertThat(redisTemplate.hasKey(KEY)).as("B 无权释放 A 的锁").isTrue();
        nodeA.release(KEY, tokenA);
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("限流键跨实例生效：窗口内第二次不再放行")
    void throttleIsCrossNode() {
        assertThat(nodeA.firstWithin(THROTTLE_KEY, Duration.ofMinutes(30))).isTrue();
        assertThat(nodeB.firstWithin(THROTTLE_KEY, Duration.ofMinutes(30)))
            .as("换一台机器也不能绕过限流").isFalse();
    }
}
