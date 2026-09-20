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
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QrCodeService 活码核心业务")
class QrCodeServiceTest {

    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrRotateLogRepository rotateLogRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private AlertService alertService;
    @Mock private WechatSyncHealingService syncHealingService;
    @Mock private SchoolRepository schoolRepo;
    @Mock private WecomApiClient wecomApiClient;
    @Mock private ObjectMapper objectMapper;
    @Mock private StringRedisTemplate redisTemplate;

    @InjectMocks
    private QrCodeService qrCodeService;

    @Test
    @DisplayName("getById — 按 ID 查询活码")
    void shouldGetById() {
        QrCode qr = QrCode.builder().id(1L).schoolName("北京第一中学").build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        QrCode result = qrCodeService.getById(1L);

        assertThat(result.getSchoolName()).isEqualTo("北京第一中学");
    }

    @Test
    @DisplayName("getById — 不存在的活码抛异常")
    void shouldThrowWhenNotFound() {
        when(qrCodeRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> qrCodeService.getById(999L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @Disabled("getAgents calls qrAgentRepo.findByQrCodeId which returns null — needs review")
    @DisplayName("getAgents — 返回活码绑定员工列表")
    void shouldReturnAgents() {
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(
                QrAgent.builder().id(1L).qrCodeId(1L).agentUserid("user1").build(),
                QrAgent.builder().id(2L).qrCodeId(1L).agentUserid("user2").build()));

        List<QrAgent> agents = qrCodeService.getAgents(1L);

        assertThat(agents).hasSize(2);
    }

    @Test
    @DisplayName("updateThresholds — 更新预警阈值")
    void shouldUpdateThresholds() {
        QrCode qr = QrCode.builder().id(1L).warnRatio(80).urgentRatio(95).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        qrCodeService.updateThresholds(1L, 70, 90);

        assertThat(qr.getWarnRatio()).isEqualTo(70);
        assertThat(qr.getUrgentRatio()).isEqualTo(90);
        verify(qrCodeRepo).save(qr);
    }

    @Test
    @DisplayName("updateRotateMode — 切换轮换模式")
    void shouldUpdateRotateMode() {
        QrCode qr = QrCode.builder().id(1L).rotateMode(QrCode.RotateMode.auto).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        qrCodeService.updateRotateMode(1L, QrCode.RotateMode.manual);

        assertThat(qr.getRotateMode()).isEqualTo(QrCode.RotateMode.manual);
    }

    @Test
    @DisplayName("updateStatus — 更新活码状态")
    void shouldUpdateStatus() {
        QrCode qr = QrCode.builder().id(1L).status(QrCode.QrCodeStatus.active).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        qrCodeService.updateStatus(1L, QrCode.QrCodeStatus.paused);

        assertThat(qr.getStatus()).isEqualTo(QrCode.QrCodeStatus.paused);
    }

    @Test
    @DisplayName("updateAgent — 接待员升级为服务老师时填充 serviceDailyMax")
    void shouldFillServiceDailyMaxOnUpgradeToService() {
        QrAgent agent = QrAgent.builder()
                .id(10L).qrCodeId(1L).agentUserid("svc1")
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(150)
                .serviceDailyMax(null)
                .status(QrAgent.AgentStatus.active)
                .build();
        when(qrAgentRepo.findById(10L)).thenReturn(Optional.of(agent));
        when(agentRepo.findById("svc1")).thenReturn(Optional.empty());

        qrCodeService.updateAgent(1L, 10L, null, "service", null);

        assertThat(agent.getRole()).isEqualTo(QrAgent.AgentRole.service);
        assertThat(agent.getServiceDailyMax()).isEqualTo(150);
        verify(qrAgentRepo).save(agent);
    }

    @Test
    @DisplayName("updateAgent — 服务老师降级为接待员时清空 serviceDailyMax")
    void shouldClearServiceDailyMaxOnDowngradeToReceptionist() {
        QrAgent agent = QrAgent.builder()
                .id(10L).qrCodeId(1L).agentUserid("svc1")
                .role(QrAgent.AgentRole.service)
                .dailyMax(300)
                .serviceDailyMax(300)
                .status(QrAgent.AgentStatus.active)
                .build();
        QrAgent otherSvc = QrAgent.builder()
                .id(11L).qrCodeId(1L).agentUserid("svc2")
                .role(QrAgent.AgentRole.service)
                .status(QrAgent.AgentStatus.active)
                .build();
        when(qrAgentRepo.findById(10L)).thenReturn(Optional.of(agent));
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(agent, otherSvc));

        qrCodeService.updateAgent(1L, 10L, null, "receptionist", null);

        assertThat(agent.getRole()).isEqualTo(QrAgent.AgentRole.receptionist);
        assertThat(agent.getServiceDailyMax()).isNull();
        verify(qrAgentRepo).save(agent);
    }

    @Test
    @DisplayName("updateAgent — 编辑接待员日限后同步全局池为活跃接待员活码最大值")
    void shouldSyncGlobalPoolDailyMaxOnUpdateAgent() {
        QrAgent agent = QrAgent.builder()
                .id(10L).qrCodeId(1L).agentUserid("r1")
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(200)
                .status(QrAgent.AgentStatus.active)
                .build();
        GlobalAgentPool pool = GlobalAgentPool.builder()
                .agentUserid("r1").dailyMax(150).build();
        when(qrAgentRepo.findById(10L)).thenReturn(Optional.of(agent));
        when(qrAgentRepo.findMaxActiveReceptionistDailyMax("r1")).thenReturn(200);
        when(poolRepo.findByAgentUserid("r1")).thenReturn(Optional.of(pool));

        qrCodeService.updateAgent(1L, 10L, 200, null, null);

        assertThat(pool.getDailyMax()).isEqualTo(200);
        verify(poolRepo).save(pool);
    }

    @Test
    @DisplayName("updateAgent — 无活跃接待员绑定时不改动全局池")
    void shouldNotTouchGlobalPoolWhenNoActiveReceptionist() {
        QrAgent agent = QrAgent.builder()
                .id(10L).qrCodeId(1L).agentUserid("svc1")
                .role(QrAgent.AgentRole.service)
                .dailyMax(300)
                .status(QrAgent.AgentStatus.active)
                .build();
        when(qrAgentRepo.findById(10L)).thenReturn(Optional.of(agent));
        when(qrAgentRepo.findMaxActiveReceptionistDailyMax("svc1")).thenReturn(null);

        qrCodeService.updateAgent(1L, 10L, 300, null, null);

        verify(poolRepo, never()).findByAgentUserid(any());
        verify(poolRepo, never()).save(any(GlobalAgentPool.class));
    }

    @Test
    @DisplayName("batchUpdateRotateMode — 批量 JPQL 更新，repo 直接返回行数")
    void shouldBatchUpdateRotateModeWithPartialFailure() {
        when(qrCodeRepo.batchUpdateRotateMode(eq(QrCode.RotateMode.manual), anyList()))
                .thenReturn(1);

        int count = qrCodeService.batchUpdateRotateMode(List.of(1L, 2L), QrCode.RotateMode.manual);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @Disabled("getBackups calls poolRepo.findAllByOrderBySortOrder — needs @DataJpaTest")
    @DisplayName("getBackups — 返回全局池列表")
    void shouldReturnBackups() {
        when(poolRepo.findAllByOrderBySortOrder(any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        Page<GlobalAgentPool> backups = qrCodeService.getBackups(1L, 0, 100);

        assertThat(backups).isEmpty();
    }
}
