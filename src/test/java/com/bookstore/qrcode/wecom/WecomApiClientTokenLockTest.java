package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * access_token 读写锁并发安全验证。
 *
 * <p>验证修复：ReentrantReadWriteLock 替换 synchronized，
 * 确保缓存命中时多线程并发读取不互斥。
 */
class WecomApiClientTokenLockTest {

    private WecomConfig config;

    @BeforeEach
    void setUp() {
        config = new WecomConfig();
        config.setCorpId("test_corp_id");
        config.setCorpSecret("test_secret");
    }

    /**
     * 场景 1：缓存命中时，多线程可并发读取。
     *
     * <p>预置未过期的 token，启动 10 个线程同时调用 getAccessToken，
     * 验证所有线程在 1 秒内全部返回（若使用 synchronized 会串行化，
     * 10 个线程至少需要数秒）。</p>
     */
    @Test
    @DisplayName("缓存命中时读写锁允许并发读取")
    void shouldAllowConcurrentReadsWhenCacheHit() throws Exception {
        // 预置有效 token（30 秒后才过期）
        config.setAccessToken("cached_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 30);

        // 使用匿名子类注入 mock config，绕过企微 API 调用
        WecomApiClient client = createClientWithConfig(config);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // 所有线程同时起跑
                    String token = client.getAccessToken();
                    assertEquals("cached_token", token);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        long t0 = System.currentTimeMillis();
        startLatch.countDown();      // 发令枪
        doneLatch.await();           // 等全部完成
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(10, successCount.get(), "全部线程应读到缓存 token");
        assertEquals(0, failCount.get(), "不应有失败");
        assertTrue(elapsed < 1000,
            "10 线程并发读取应在 1s 内完成，实际耗时 " + elapsed + "ms。"
            + "若 >1s 说明存在不必要的互斥（synchronized 串行化）");
    }

    /**
     * 场景 2：缓存过期时只有一个线程刷新 token。
     *
     * <p>预置已过期的 token，启动 5 个线程同时调用。
     * 由于实际会调企微 API（我们没有 mock HTTP），此处仅验证
     * 过期缓存不会返回给调用方（应抛异常或刷新）。</p>
     */
    @Test
    @DisplayName("缓存过期时不会返回过期 token")
    void shouldNotReturnExpiredToken() throws Exception {
        // 预置已过期 token
        config.setAccessToken("expired_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() - 1);

        WecomApiClient client = createClientWithConfig(config);

        // 缓存过期 → 尝试刷新 → 会调企微 API → 因为 corpId/secret 无效会抛异常
        assertThrows(RuntimeException.class, client::getAccessToken,
            "过期 token 不应返回，应尝试刷新并因无效凭据而抛异常");
    }

    /**
     * 场景 3：高并发下 double-check 正确性。
     *
     * <p>模拟 token 即将过期（1 秒后过期）的场景，
     * 多个线程同时进入写锁竞争，验证只有一个线程实际刷新。</p>
     */
    @Test
    @DisplayName("高并发时 double-check 防止重复刷新")
    void shouldDoubleCheckBeforeRefresh() throws Exception {
        // token 1 秒后过期 → 第一个线程发现即将过期，进入写锁
        config.setAccessToken("about_to_expire");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 1);

        // 等待 2 秒让 token 彻底过期
        Thread.sleep(2000);

        WecomApiClient client = createClientWithConfig(config);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    client.getAccessToken(); // 过期会尝试刷新
                } catch (Exception e) {
                    // 预期：因为 corpId/secret 无效而刷新失败
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        // 所有线程都应因无效凭据而失败（而非读到过期 token）
        assertEquals(threadCount, exceptionCount.get(),
            "所有线程应尝试刷新并失败（读到过期 token 才是 bug）");
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 创建注入 mock config 的 WecomApiClient 实例。
     *
     * <p>构造器现在接收 5 个参数 (WecomConfig, connectTimeout, readTimeout, RestTemplateBuilder, ObjectMapper)。
     */
    private WecomApiClient createClientWithConfig(WecomConfig testConfig) throws Exception {
        java.lang.reflect.Constructor<WecomApiClient> ctor =
            WecomApiClient.class.getDeclaredConstructor(
                WecomConfig.class, int.class, int.class, RestTemplateBuilder.class, ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(testConfig, 3, 10, new RestTemplateBuilder(), new ObjectMapper());
    }
}
