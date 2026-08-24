package com.bookstore.qrcode.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

import java.io.IOException;

/**
 * 业务场景集成测试的抽象基类。
 *
 * <p>提供 {@code @SpringBootTest} 全上下文启动能力，并启动一个
 * 进程内的 Embedded Redis 实例（真实 Redis 进程，无 Docker 依赖）。</p>
 *
 * <h3>设计决策</h3>
 * <ul>
 *   <li>{@code webEnvironment = NONE} — 业务场景测试目标为 Service 链路</li>
 *   <li>{@code static} RedisServer — 所有子类共享同一实例</li>
 *   <li>{@code DynamicPropertySource} — 动态注入 Redis 端口到 Spring 环境</li>
 *   <li>不添加 {@code @Transactional} 到基类 — 避免外层事务与 Service 层
 *       {@code @Transactional} 嵌套冲突，特别是 afterCommit 回调的触发</li>
 *   <li>激活 {@code test} profile — H2 + 禁用缓存 + worker threads=0</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 2026-06-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    private static RedisServer redisServer;
    private static int redisPort;

    @BeforeAll
    static synchronized void startRedis() throws IOException {
        // 只启动一次：所有子类共享同一静态实例
        if (redisServer != null && redisServer.isActive()) {
            System.out.println("[BaseIntegrationTest] Embedded Redis already running on port " + redisPort);
            return;
        }
        redisPort = findAvailablePort();
        redisServer = new RedisServer(redisPort);
        redisServer.start();
        System.out.println("[BaseIntegrationTest] Embedded Redis started on port " + redisPort);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        // 最后一个子类调用，关闭共享服务器
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
            System.out.println("[BaseIntegrationTest] Embedded Redis stopped");
        }
    }

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    private static int findAvailablePort() {
        for (int port = 16379; port < 16400; port++) {
            try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {
                // port in use, try next
            }
        }
        throw new IllegalStateException("No available port found in range 16379-16399");
    }
}
