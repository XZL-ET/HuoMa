package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 业务场景 5：告警系统。
 *
 * <p>验证 {@link AlertService} 的空后备池告警与通用告警创建。
 * 熔断/封禁逻辑已在 AlertServiceTest 单元测试覆盖（H2 的 findByIdForUpdate 行为差异）。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("告警系统 集成测试")
class AlertSystemTest extends BaseIntegrationTest {

    @Autowired private AlertService alertService;
    @Autowired private AgentAlertRepository alertRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private QrCodeService qrCodeService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private GlobalAgentPoolService poolService;

    private QrCode testQr;

    @BeforeEach
    void setUp() {
        alertRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();

        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("告警测试学校");
        req.setSchoolId("SCH-ALERT-001");
        req.setRegionCity("广州");
        req.setRegionDistrict("天河区");
        req.setStudentCount(500);
        req.setServiceTeacherUserid("agent1");
        req.setInitialAgentUserids("agent1");
        testQr = qrCodeService.create(req);
    }

    // ================================================================
    // 告警记录
    // ================================================================

    @org.junit.jupiter.api.Disabled("alertEmptyBackup 在独立事务中与 H2 行为不一致")
    @Test
    @DisplayName("空后备池 → 创建 high severity 告警")
    void shouldCreateAlertOnEmptyBackup() {
        alertService.alertEmptyBackup(testQr.getId(), testQr.getSchoolName());

        // 验证：alertEmptyBackup 使用 agentUserid="" 创建告警
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
                "", "empty_backup", LocalDateTime.now().minusMinutes(5));
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("createAlert 通用方法 → 告警成功创建")
    void shouldCreateGenericAlert() {
        alertService.createAlert("agent1", "test_alert",
                AgentAlert.AlertSeverity.medium,
                "集成测试告警详情",
                AgentAlert.AutoAction.paused,
                testQr.getId());

        // 验证告警已创建
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
                "agent1", "test_alert", LocalDateTime.now().minusMinutes(5));
        assertThat(count).isEqualTo(1);
    }
}
