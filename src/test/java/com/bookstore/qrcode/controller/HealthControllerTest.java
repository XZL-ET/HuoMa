package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.service.LeakMetrics;
import com.bookstore.qrcode.service.MessageGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("HealthController 健康检查")
class HealthControllerTest {

    private MockMvc mockMvc;
    private StringRedisTemplate redisTemplate;
    private MessageGuardService messageGuardService;
    private final LeakMetrics leakMetrics = new LeakMetrics();

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        messageGuardService = mock(MessageGuardService.class);
        GlobalAgentPoolRepository poolRepo = mock(GlobalAgentPoolRepository.class);

        HealthController controller = new HealthController(redisTemplate, poolRepo, messageGuardService,
            leakMetrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/health/streams — 返回 JSON 健康数据")
    void shouldReturnStreamHealth() throws Exception {
        when(messageGuardService.dlqSize()).thenReturn(0L);

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        mockMvc.perform(get("/api/health/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dlq_length").value(0))
                .andExpect(jsonPath("$.redis_alive").value(true));
    }

    @Test
    @DisplayName("GET /api/health/streams — 暴露静默漏处理计数，运维才拿得到增量")
    void shouldExposeLeakMetrics() throws Exception {
        leakMetrics.tagSkipMissingQrCode();
        leakMetrics.tagConsumerRestarted();
        leakMetrics.tagPermanentFailure(84061);

        mockMvc.perform(get("/api/health/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leak_metrics.tag_skip_missing_qrcode").value(1))
                .andExpect(jsonPath("$.leak_metrics.tag_consumer_restarted").value(1))
                .andExpect(jsonPath("$.leak_metrics.tag_permanent_failure").value(1))
                .andExpect(jsonPath("$.leak_metrics.tag_permanent_failure_by_errcode['84061']").value(1));
    }
}
