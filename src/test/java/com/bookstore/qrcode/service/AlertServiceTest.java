package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentAlertRepository;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertService 告警与异常处理")
class AlertServiceTest {

    @Mock private AgentAlertRepository alertRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private StringRedisTemplate redisTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AlertService alertService;

    @Test
    @DisplayName("meltAgent — 24h 内熔断 < 3 次设为 melted")
    void shouldMeltAgent() {
        Agent agent = new Agent();
        agent.setUserid("user1");
        agent.setOverallStatus(Agent.OverallStatus.normal);
        when(agentRepo.findByIdForUpdate("user1")).thenReturn(Optional.of(agent));

        alertService.meltAgent("user1", 1L, "测试熔断");

        assertThat(agent.getOverallStatus()).isEqualTo(Agent.OverallStatus.melted);
        assertThat(agent.getMeltedCount24h()).isEqualTo(1);
        verify(agentRepo).save(agent);
    }

    @Test
    @DisplayName("meltAgent — 24h 内熔断 >= 3 次升级为 blocked，并同步清理全局池")
    void shouldBlockOnThirdMelt() {
        Agent agent = new Agent();
        agent.setUserid("user1");
        agent.setOverallStatus(Agent.OverallStatus.melted);
        agent.setMeltedCount24h(2);
        when(agentRepo.findByIdForUpdate("user1")).thenReturn(Optional.of(agent));

        GlobalAgentPool poolEntry = GlobalAgentPool.builder()
            .agentUserid("user1").dailyMax(100)
            .status(GlobalAgentPool.PoolStatus.standby).build();
        when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.of(poolEntry));

        alertService.meltAgent("user1", 1L, "测试熔断");

        assertThat(agent.getOverallStatus()).isEqualTo(Agent.OverallStatus.blocked);
        assertThat(agent.getMeltedCount24h()).isEqualTo(3);
        // 验证池清理
        verify(poolRepo).delete(poolEntry);
    }

    @Test
    @DisplayName("meltAgent — 已封禁员工跳过熔断")
    void shouldSkipBlockedAgent() {
        Agent agent = new Agent();
        agent.setUserid("user1");
        agent.setOverallStatus(Agent.OverallStatus.blocked);
        when(agentRepo.findByIdForUpdate("user1")).thenReturn(Optional.of(agent));

        alertService.meltAgent("user1", 1L, "不应触发");

        verify(agentRepo, never()).save(any(Agent.class));
        verify(alertRepo, never()).save(any());
    }

    @Test
    @DisplayName("meltAgent — 员工不存在时静默处理")
    void shouldSkipWhenAgentNotFound() {
        when(agentRepo.findByIdForUpdate("user1")).thenReturn(Optional.empty());

        alertService.meltAgent("user1", 1L, "不应触发");

        verify(agentRepo, never()).save(any());
    }

    @Test
    @DisplayName("alertEmptyBackup — 后备池为空告警")
    void shouldCreateEmptyBackupAlert() {
        alertService.alertEmptyBackup(1L, "北京第一中学");

        ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
        verify(alertRepo).save(captor.capture());
        AgentAlert alert = captor.getValue();
        assertThat(alert.getAlertType()).isEqualTo("empty_backup");
        assertThat(alert.getAgentUserid()).isNull();
        assertThat(alert.getSeverity()).isEqualTo(AgentAlert.AlertSeverity.high);
    }

    @Test
    @DisplayName("handleCustomerApiError — 累计恰好达到阈值一半应创建 medium 告警")
    void shouldCreateMediumAlertAtHalfThreshold() throws Exception {
        QrCode qrCode = QrCode.builder().id(1L).schoolId("BJ-001").build();
        when(qrCodeRepo.findBySchoolId("BJ-001")).thenReturn(Optional.of(qrCode));
        // 84073 阈值 5，半数 = 2：Redis 滑动窗口计数返回 2 → 触发 medium 预警
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L);

        alertService.handleCustomerApiError("user1", "ext1", 84073, "84073:deleted", "BJ-001");

        ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AgentAlert.AlertSeverity.medium);
        assertThat(captor.getValue().getAlertType()).isEqualTo("add_fail");
        // medium 预警不触发自动暂停
        verify(agentRepo, never()).findByIdForUpdate(anyString());
    }

    @Test
    @DisplayName("handleCustomerApiError — 达到阈值应创建 high 告警并暂停员工")
    void shouldCreateHighAlertAndPauseAtThreshold() {
        QrCode qrCode = QrCode.builder().id(1L).schoolId("BJ-001").build();
        when(qrCodeRepo.findBySchoolId("BJ-001")).thenReturn(Optional.of(qrCode));
        // 25002 阈值 10：滑动窗口返回 10 → high 告警 + 暂停
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(10L);
        Agent agent = new Agent();
        agent.setUserid("user1");
        agent.setOverallStatus(Agent.OverallStatus.normal);
        when(agentRepo.findByIdForUpdate("user1")).thenReturn(Optional.of(agent));

        alertService.handleCustomerApiError("user1", "ext1", 25002, "拒绝添加", "BJ-001");

        ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AgentAlert.AlertSeverity.high);
        assertThat(agent.getOverallStatus()).isEqualTo(Agent.OverallStatus.warning);
    }

    @Test
    @DisplayName("handleTransferFail — customer_refused 应创建 high 告警")
    void shouldCreateAlertOnCustomerRefused() throws Exception {
        JsonNode event = objectMapper.readTree("""
            {"userid":"user1","external_userid":"ext1","fail_reason":"customer_refused"}
            """);

        alertService.handleTransferFail(event);

        ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getAlertType()).isEqualTo("transfer_fail");
        assertThat(captor.getValue().getSeverity()).isEqualTo(AgentAlert.AlertSeverity.high);
        assertThat(captor.getValue().getDetail()).contains("客户拒绝接替");
    }

    @Test
    @DisplayName("handleTransferFail — customer_limit_exceed 应创建 high 告警")
    void shouldCreateAlertOnCustomerLimitExceed() throws Exception {
        JsonNode event = objectMapper.readTree("""
            {"userid":"user1","external_userid":"ext1","fail_reason":"customer_limit_exceed"}
            """);

        alertService.handleTransferFail(event);

        ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AgentAlert.AlertSeverity.high);
    }
}
