package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private AgentRotationService rotationService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

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

    // ==================== preActivateBackup 幂等性测试 ====================

    @Test
    @DisplayName("preActivateBackup — 当天已预激活的活码，再次调用被幂等拦截")
    void shouldSkipPreactivateWhenAlreadyDoneToday() {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setSchoolName("测试学校");

        // doneKey 已存在 → 模拟当天已预激活
        String doneKey = RedisConfig.PREACTIVATE_DONE_PREFIX + 1L;
        when(valueOps.setIfAbsent(eq(doneKey), eq("1"), any(Duration.class)))
            .thenReturn(false);

        rotationService.preActivateBackup(1L, qr);

        // 应该提前返回，不触发任何后续操作
        verify(valueOps, never()).setIfAbsent(
            startsWith(RedisConfig.ROTATE_LOCK_PREFIX), anyString(), any(Duration.class));
        verify(qrAgentRepo, never()).save(any(QrAgent.class));
        verify(poolService, never()).takeStandby(anySet(), anyLong());
    }

    @Test
    @DisplayName("preActivateBackup — 不同活码使用独立的幂等 key")
    void shouldUseSeparateDoneKeyPerQrCode() {
        QrCode qr1 = new QrCode();
        qr1.setId(1L);
        qr1.setSchoolName("学校A");
        QrCode qr2 = new QrCode();
        qr2.setId(2L);
        qr2.setSchoolName("学校B");

        String doneKey1 = RedisConfig.PREACTIVATE_DONE_PREFIX + 1L;
        String doneKey2 = RedisConfig.PREACTIVATE_DONE_PREFIX + 2L;

        // 活码1 已预激活，活码2 未预激活
        when(valueOps.setIfAbsent(eq(doneKey1), eq("1"), any(Duration.class)))
            .thenReturn(false);
        when(valueOps.setIfAbsent(eq(doneKey2), eq("1"), any(Duration.class)))
            .thenReturn(true);
        // 活码2 的 lockKey 也返回 true，让它进入后续流程
        when(valueOps.setIfAbsent(
                startsWith(RedisConfig.ROTATE_LOCK_PREFIX), anyString(), any(Duration.class)))
            .thenReturn(true);

        // 活码1 — doneKey 已存在，幂等拦截，提前返回
        rotationService.preActivateBackup(1L, qr1);
        verify(qrAgentRepo, never()).save(any(QrAgent.class));

        // 活码2 — doneKey 不存在，进入正常流程（因单元测试无事务上下文，
        // TransactionSynchronizationManager 会抛异常，但 doneKey 检查已通过）
        try {
            rotationService.preActivateBackup(2L, qr2);
        } catch (IllegalStateException e) {
            // expected — 单元测试无事务上下文，在 registerSynchronization 处失败
        }

        // 活码2 的 doneKey 确实被检查了，且活码2 走到了取人逻辑
        verify(valueOps).setIfAbsent(eq(doneKey2), eq("1"), any(Duration.class));
    }
}
