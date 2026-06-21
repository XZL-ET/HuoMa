package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRotationService 轮换逻辑")
class AgentRotationServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private QrRotateLogRepository rotateLogRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private AgentDailyCountService dailyCountService;
    @Mock private WechatSyncHealingService syncHealingService;
    @Mock private AlertService alertService;
    @Mock private WecomApiClient wecomApiClient;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private AgentRotationService rotationService;

    @Test
    @DisplayName("getActiveReceptionistCount — 返回活跃接待员数")
    void shouldReturnActiveReceptionistCount() {
        when(qrAgentRepo.findByQrCodeIdAndStatus(1L, QrAgent.AgentStatus.active))
                .thenReturn(java.util.List.of(new QrAgent(), new QrAgent(), new QrAgent()));

        int count = rotationService.getActiveReceptionistCount(1L);

        assertEquals(3, count);
    }

    @Test
    @Disabled("expandQrCodeUsers requires Redis + TransactionSynchronization — test with @SpringBootTest")
    @DisplayName("expandQrCodeUsers — 手动模式活码跳过扩容")
    void shouldSkipManualMode() {}

    @Test
    @Disabled("expandQrCodeUsers requires Redis + TransactionSynchronization — test with @SpringBootTest")
    @DisplayName("expandQrCodeUsers — 自动模式下池空时告警")
    void shouldAlertWhenPoolEmptyInAutoMode() {}
}
