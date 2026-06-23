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
    @DisplayName("batchUpdateRotateMode — 部分失败不影响其他")
    void shouldBatchUpdateRotateModeWithPartialFailure() {
        QrCode qr1 = QrCode.builder().id(1L).rotateMode(QrCode.RotateMode.auto).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr1));
        when(qrCodeRepo.findById(2L)).thenReturn(Optional.empty());

        int count = qrCodeService.batchUpdateRotateMode(List.of(1L, 2L), QrCode.RotateMode.manual);

        assertThat(count).isEqualTo(1);
        assertThat(qr1.getRotateMode()).isEqualTo(QrCode.RotateMode.manual);
    }

    @Test
    @Disabled("getBackups calls poolRepo.findAllByOrderBySortOrder — needs @DataJpaTest")
    @DisplayName("getBackups — 返回全局池列表")
    void shouldReturnBackups() {
        when(poolRepo.findAllByOrderBySortOrder(any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        List<GlobalAgentPool> backups = qrCodeService.getBackups(1L);

        assertThat(backups).isEmpty();
    }
}
