package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.controller.AgentController;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 部门准入：手动入口（addAgent / restore）。
 *
 * <p>白名单启用时，无关部门员工不能通过手动添加上码，
 * 恢复员工时也不会复活其在无关部门下的 removed 绑定，并在提示中列出。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：手动入口")
class DepartmentWhitelistManualEntryTest extends BaseIntegrationTest {

    @Autowired private QrCodeService qrCodeService;
    @Autowired private AgentController agentController;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        whitelistRepo.deleteAll();
        employeeRepo.deleteAll();
        agentRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        employeeRepo.save(Employee.builder().userid("ok").name("ok")
            .department("[100]").active(true).wechatStatus(1).build());
        employeeRepo.save(Employee.builder().userid("bad").name("bad")
            .department("[200]").active(true).wechatStatus(1).build());
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("准入测试").schoolId("SCH-WL-1")
            .regionCity("深圳").regionDistrict("南山区").build());
    }

    @Test
    @DisplayName("addAgent 拒绝无关部门员工")
    void addAgentRejectsDisallowed() {
        assertThatThrownBy(() -> qrCodeService.addAgent(qr.getId(), "bad"))
            .hasMessageContaining("部门准入");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad")).isEmpty();
    }

    @Test
    @DisplayName("addAgent 允许部门员工正常添加")
    void addAgentAllowsEligible() {
        qrCodeService.addAgent(qr.getId(), "ok");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "ok"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("restore 不复活无关部门接待员的 removed 绑定")
    void restoreSkipsDisallowedRemovedBinding() {
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.melted).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build());

        var redirect = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        agentController.restoreAgent("bad", "pool", "", redirect);

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(redirect.getFlashAttributes().get("message").toString())
            .contains("部门准入")
            // 无关部门员工不会真正入池（ensureInPool 静默拒绝），消息不能声称已加入
            .doesNotContain("已自动加入全局池");
    }

    @Test
    @DisplayName("restore 正常恢复无关部门员工的服务老师/双角色绑定")
    void restoreKeepsServiceAndDualBindings() {
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build());
        QrCode qr2 = qrCodeRepo.save(QrCode.builder()
            .schoolName("准入测试2").schoolId("SCH-WL-2")
            .regionCity("深圳").regionDistrict("南山区").build());
        QrCode qr3 = qrCodeRepo.save(QrCode.builder()
            .schoolName("准入测试3").schoolId("SCH-WL-3")
            .regionCity("深圳").regionDistrict("南山区").build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr2.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.service)
            .status(QrAgent.AgentStatus.removed).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr3.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.dual)
            .status(QrAgent.AgentStatus.removed).build());

        var redirect = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        agentController.restoreAgent("bad", "pool", "", redirect);

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr2.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr3.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("白名单停用时无关部门员工可添加上码并恢复")
    void inactiveWhitelistAllowsDisallowed() {
        whitelistRepo.deleteAll();
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build());

        qrCodeService.addAgent(qr.getId(), "bad");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));

        QrCode qr2 = qrCodeRepo.save(QrCode.builder()
            .schoolName("准入测试2").schoolId("SCH-WL-2")
            .regionCity("深圳").regionDistrict("南山区").build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr2.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build());

        var redirect = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        agentController.restoreAgent("bad", "pool", "", redirect);

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr2.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }
}
