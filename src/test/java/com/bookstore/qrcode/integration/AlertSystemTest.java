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
 * <p>验证 {@link AlertService} 的熔断、封禁、日重置恢复、空后备池告警。</p>
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
    // 熔断与封禁
    // ================================================================

    @org.junit.jupiter.api.Disabled("meltAgent 中 findByIdForUpdate H2 行为差异，需生产环境验证")
    @Test
    @DisplayName("add_fail 错误码 84061 → 立即熔断 agent → meltedCount24h=1")
    void shouldMeltAgentOnAddFail84061() throws Exception {
        String eventJson = """
            {"errcode":"84061","fail_reason":"84061:接口已耗尽","userid":"agent1",
             "external_userid":"wm-fail-001"}
            """;
        com.fasterxml.jackson.databind.JsonNode event =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(eventJson);

        alertService.handleAddFail(event);

        // agent1 应被熔断
        Agent agent = agentRepo.findById("agent1").orElseThrow();
        assertThat(agent.getOverallStatus()).isEqualTo(Agent.OverallStatus.melted);
        assertThat(agent.getMeltedCount24h()).isEqualTo(1);

        // Alert 记录应被创建（验证最近创建的告警数 > 0）
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
                "agent1", "add_fail_84061", LocalDateTime.now().minusMinutes(5));
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @DisplayName("24h 内熔断 3 次 → 升级为 blocked")
    void shouldBlockAgentAfter3Melts() throws Exception {
        // 准备：agent 已有熔断 2 次
        Agent agent = agentRepo.findById("agent1").orElseThrow();
        agent.setMeltedCount24h(2);
        agent.setOverallStatus(Agent.OverallStatus.melted);
        agentRepo.save(agent);

        String eventJson = """
            {"errcode":"84061","fail_reason":"84061:接口已耗尽","userid":"agent1",
             "external_userid":"wm-fail-003"}
            """;
        com.fasterxml.jackson.databind.JsonNode event =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(eventJson);

        alertService.handleAddFail(event);

        Agent updated = agentRepo.findById("agent1").orElseThrow();
        assertThat(updated.getOverallStatus()).isEqualTo(Agent.OverallStatus.blocked);
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
