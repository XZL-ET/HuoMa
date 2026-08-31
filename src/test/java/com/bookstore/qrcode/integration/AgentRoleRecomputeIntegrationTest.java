package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.EmployeeSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务场景：agent.role 定期重算（角色漂移根治）。
 *
 * <p>验证 {@link EmployeeSyncService#recomputeAgentRoles()} 依据
 * qr_agent 活跃绑定 + QrCode.transferTargetUserid 校准 agent.role，
 * 解决「只升级不降级」造成的角色漂移。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("agent.role 重算 集成测试")
class AgentRoleRecomputeIntegrationTest extends BaseIntegrationTest {

    @Autowired private EmployeeSyncService employeeSyncService;
    @Autowired private AgentRepository agentRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private EmployeeRepository employeeRepo;

    @BeforeEach
    void setUp() {
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
    }

    private Agent agent(String userid, Agent.AgentRole role) {
        return agentRepo.save(Agent.builder()
            .userid(userid).name(userid).role(role).dailyTotalCap(500).build());
    }

    private QrCode qrCode(String schoolId, String transferTarget) {
        return qrCodeRepo.save(QrCode.builder()
            .schoolName("学校" + schoolId).schoolId(schoolId)
            .regionCity("深圳").regionDistrict("南山区")
            .transferTargetUserid(transferTarget).build());
    }

    private void bind(Long qrCodeId, String userid, QrAgent.AgentRole role) {
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qrCodeId).agentUserid(userid)
            .role(role).dailyMax(100)
            .status(QrAgent.AgentStatus.active).build());
    }

    @Test
    @DisplayName("无绑定非继承目标的下码老服务老师 → 降回 receptionist")
    void shouldDowngradeUnboundNonTargetServiceToReceptionist() {
        agent("old-service", Agent.AgentRole.service);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("old-service").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.receptionist);
    }

    @Test
    @DisplayName("无绑定但为继承目标 → 保持 service（不入池）")
    void shouldKeepInheritanceTargetAsService() {
        agent("inherit", Agent.AgentRole.service);
        qrCode("SCH-INHERIT", "inherit"); // transferTargetUserid = inherit

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("inherit").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.service);
    }

    @Test
    @DisplayName("漂移员工（有 receptionist 绑定、agent.role=service）→ 校准为 receptionist")
    void shouldCalibrateDriftedReceptionist() {
        agent("drifted", Agent.AgentRole.service);
        QrCode qr = qrCode("SCH-DRIFT", null);
        bind(qr.getId(), "drifted", QrAgent.AgentRole.receptionist);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("drifted").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.receptionist);
    }

    @Test
    @DisplayName("纯服务老师（仅 service 绑定）→ 保持 service")
    void shouldKeepPureService() {
        agent("svc", Agent.AgentRole.service);
        QrCode qr = qrCode("SCH-SVC", null);
        bind(qr.getId(), "svc", QrAgent.AgentRole.service);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("svc").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.service);
    }

    @Test
    @DisplayName("跨码 receptionist + service 绑定 → dual")
    void shouldDeriveDualFromBothRoles() {
        agent("both", Agent.AgentRole.receptionist);
        QrCode qrA = qrCode("SCH-BOTH-A", null);
        QrCode qrB = qrCode("SCH-BOTH-B", null);
        bind(qrA.getId(), "both", QrAgent.AgentRole.receptionist);
        bind(qrB.getId(), "both", QrAgent.AgentRole.service);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("both").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.dual);
    }

    @Test
    @DisplayName("显式 dual 绑定 → dual")
    void shouldDeriveDualFromDualBinding() {
        agent("du", Agent.AgentRole.receptionist);
        QrCode qr = qrCode("SCH-DUAL", null);
        bind(qr.getId(), "du", QrAgent.AgentRole.dual);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("du").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.dual);
    }

    @Test
    @DisplayName("blocked 员工被跳过，角色不变")
    void shouldSkipBlockedAgents() {
        Agent blocked = agent("blocked", Agent.AgentRole.service);
        blocked.setOverallStatus(Agent.OverallStatus.blocked);
        agentRepo.save(blocked);

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("blocked").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.service);
    }

    @Test
    @DisplayName("有 removed 历史绑定但无 active 绑定的下码老服务老师 → 降回 receptionist")
    void shouldIgnoreRemovedBindingsWhenRecomputing() {
        agent("removed-svc", Agent.AgentRole.service);
        QrCode qr = qrCode("SCH-REMOVED", null);
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid("removed-svc")
            .role(QrAgent.AgentRole.service).dailyMax(100)
            .status(QrAgent.AgentStatus.removed).build());

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("removed-svc").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.receptionist);
    }

    @Test
    @DisplayName("已下码（full）的服务老师绑定 → 保持 service（临时下码不丢身份）")
    void shouldKeepFullServiceAsService() {
        agent("full-svc", Agent.AgentRole.service);
        QrCode qr = qrCode("SCH-FULL-SVC", null);
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid("full-svc")
            .role(QrAgent.AgentRole.service).dailyMax(150)
            .status(QrAgent.AgentStatus.full).build());

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("full-svc").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.service);
    }

    @Test
    @DisplayName("已下码（full）的 dual 绑定 → 保持 dual")
    void shouldKeepFullDualAsDual() {
        agent("full-dual", Agent.AgentRole.dual);
        QrCode qr = qrCode("SCH-FULL-DUAL", null);
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid("full-dual")
            .role(QrAgent.AgentRole.dual).dailyMax(150)
            .status(QrAgent.AgentStatus.full).build());

        employeeSyncService.recomputeAgentRoles();

        assertThat(agentRepo.findById("full-dual").orElseThrow().getRole())
            .isEqualTo(Agent.AgentRole.dual);
    }
}
