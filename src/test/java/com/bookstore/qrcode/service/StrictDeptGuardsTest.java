package com.bookstore.qrcode.service;

import com.bookstore.qrcode.controller.AgentController;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手动加人 / 一键恢复的严格同部门护栏。
 *
 * <p>两者语义相反：手动加人 fail-closed（无法判定即拒绝），
 * 一键恢复 fail-open（无法判定即放行）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("严格同部门：手动加人与一键恢复护栏")
class StrictDeptGuardsTest {

    private static final long LOCAL_DEPT = 66L;
    private static final long OTHER_DEPT = 54L;

    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private WecomApiClient wecomApi;
    @Mock private DepartmentWhitelistService whitelist;
    @Mock private StrictDeptConfigService strictDeptConfig;
    @Mock private StrictDeptService strictDeptService;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private ReceptionistDailyMaxService receptionistDailyMaxService;

    @InjectMocks private QrCodeService qrCodeService;
    @InjectMocks private AgentController agentController;

    @BeforeEach
    void setUp() {
        // addAgent 在事务提交后才触发企微同步；单测没有真实事务，手动开启同步上下文即可
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ==================== addAgent：fail-closed ====================

    @Test
    @DisplayName("严格开启 + 外县员工 → 拒绝添加到活码")
    void addAgentRejectsOtherDistrictWhenStrict() {
        QrCode qr = qr(LOCAL_DEPT);
        Employee emp = emp("u-other", OTHER_DEPT);
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(employeeRepo.findByUserid("u-other")).thenReturn(Optional.of(emp));
        when(strictDeptService.isLocal(emp, LOCAL_DEPT)).thenReturn(false);

        assertThatThrownBy(() -> qrCodeService.addAgent(1L, "u-other"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("该员工不属于本码所在区县")
            .hasMessageContaining("u-other");
    }

    @Test
    @DisplayName("严格开启 + 豁免员工 → 正常添加")
    void addAgentAllowsExemptWhenStrict() {
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of("u-exempt"));
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of());

        assertThatCode(() -> qrCodeService.addAgent(1L, "u-exempt"))
            .doesNotThrowAnyException();
        verify(qrAgentRepo).save(org.mockito.ArgumentMatchers.argThat(
            qa -> "u-exempt".equals(qa.getAgentUserid())));
    }

    @Test
    @DisplayName("严格关闭 → 不拦截")
    void addAgentNotCheckedWhenStrictOff() {
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(false);
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of());

        assertThatCode(() -> qrCodeService.addAgent(1L, "u-any"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("严格开启 + 活码无部门 → fail-closed 报错")
    void addAgentRejectsWhenQrHasNoDept() {
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr(null)));

        assertThatThrownBy(() -> qrCodeService.addAgent(1L, "u-any"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("活码未配置部门");
    }

    @Test
    @DisplayName("严格开启 + 员工无档案 → fail-closed 报错")
    void addAgentRejectsWhenEmployeeMissing() {
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(employeeRepo.findByUserid("u-any")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> qrCodeService.addAgent(1L, "u-any"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("员工不存在档案");
    }

    @Test
    @DisplayName("严格开启 + 未知部门 → 包装为活码部门不在树中")
    void addAgentRejectsWhenDeptNotInTree() {
        QrCode qr = qr(999L);
        Employee emp = emp("u-any", OTHER_DEPT);
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(employeeRepo.findByUserid("u-any")).thenReturn(Optional.of(emp));
        when(strictDeptService.isLocal(emp, 999L))
            .thenThrow(new IllegalArgumentException("未知部门: 999"));

        assertThatThrownBy(() -> qrCodeService.addAgent(1L, "u-any"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("活码所属部门不在部门树中");
    }

    // ==================== restoreAgent：fail-open ====================

    @Test
    @DisplayName("严格开启 + 外县 removed 接待员 → 不复活且消息提示按严格同部门跳过")
    void restoreSkipsOtherDistrictWhenStrict() {
        QrCode qr = QrCode.builder().id(1L).schoolName("某学校").departmentId(LOCAL_DEPT).build();
        QrAgent qa = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("u-other")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build();
        Employee emp = emp("u-other", OTHER_DEPT);

        when(agentRepo.findByIdForUpdate("u-other")).thenReturn(Optional.of(agent("u-other")));
        when(qrAgentRepo.findByAgentUserid("u-other")).thenReturn(List.of(qa));
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(employeeRepo.findByUserid("u-other")).thenReturn(Optional.of(emp));
        when(strictDeptService.isLocalOrUnknown(emp, LOCAL_DEPT)).thenReturn(false);

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        agentController.restoreAgent("u-other", "pool", "", redirect);

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed);
        assertThat(redirect.getFlashAttributes().get("message").toString())
            .contains("按严格同部门跳过活码：某学校");
    }

    @Test
    @DisplayName("严格开启 + 部门树不可用 → 按原逻辑复活（fail-open）")
    void restoreFailsOpenWhenTreeUnavailable() {
        QrCode qr = QrCode.builder().id(1L).schoolName("某学校").departmentId(LOCAL_DEPT).build();
        QrAgent qa = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("u-any")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build();
        Employee emp = emp("u-any", OTHER_DEPT);

        when(agentRepo.findByIdForUpdate("u-any")).thenReturn(Optional.of(agent("u-any")));
        when(qrAgentRepo.findByAgentUserid("u-any")).thenReturn(List.of(qa));
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(employeeRepo.findByUserid("u-any")).thenReturn(Optional.of(emp));
        when(strictDeptService.isLocalOrUnknown(emp, LOCAL_DEPT))
            .thenThrow(new DepartmentWhitelistService.DeptTreeUnavailableException("tree down"));

        agentController.restoreAgent("u-any", "pool", "", new RedirectAttributesModelMap());

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    @Test
    @DisplayName("严格开启 + 员工部门为空串 → 无法判定，照常复活（fail-open）")
    void restoreFailsOpenWhenEmployeeDeptBlank() {
        assertRestoreFailsOpenForUnknownDepartment("");
    }

    @Test
    @DisplayName("严格开启 + 员工部门为 [] → 无法判定，照常复活（fail-open）")
    void restoreFailsOpenWhenEmployeeDeptEmptyArray() {
        assertRestoreFailsOpenForUnknownDepartment("[]");
    }

    @Test
    @DisplayName("严格开启 + 豁免 userid → 复活且不调用严格判定")
    void restoreSkipsStrictCheckForExemptUserid() {
        QrCode qr = QrCode.builder().id(1L).schoolName("某学校").departmentId(LOCAL_DEPT).build();
        QrAgent qa = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("u-exempt")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build();
        Employee emp = emp("u-exempt", OTHER_DEPT);

        when(agentRepo.findByIdForUpdate("u-exempt")).thenReturn(Optional.of(agent("u-exempt")));
        when(qrAgentRepo.findByAgentUserid("u-exempt")).thenReturn(List.of(qa));
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of("u-exempt"));
        when(whitelist.isActive()).thenReturn(false);
        // 即使严格判定会返回 false，豁免员工也不得被调用/拦截
        lenient().when(strictDeptService.isLocalOrUnknown(emp, LOCAL_DEPT)).thenReturn(false);

        agentController.restoreAgent("u-exempt", "pool", "", new RedirectAttributesModelMap());

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
        verify(strictDeptService, never()).isLocalOrUnknown(any(), any());
    }

    private void assertRestoreFailsOpenForUnknownDepartment(String departmentJson) {
        QrCode qr = QrCode.builder().id(1L).schoolName("某学校").departmentId(LOCAL_DEPT).build();
        QrAgent qa = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("u-nodept")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build();
        Employee emp = Employee.builder().userid("u-nodept").name("u-nodept")
            .department(departmentJson).build();

        when(agentRepo.findByIdForUpdate("u-nodept")).thenReturn(Optional.of(agent("u-nodept")));
        when(qrAgentRepo.findByAgentUserid("u-nodept")).thenReturn(List.of(qa));
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(employeeRepo.findByUserid("u-nodept")).thenReturn(Optional.of(emp));
        when(strictDeptService.isLocalOrUnknown(emp, LOCAL_DEPT)).thenReturn(null);

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        assertThatCode(() -> agentController.restoreAgent("u-nodept", "pool", "", redirect))
            .doesNotThrowAnyException();

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
        assertThat(redirect.getFlashAttributes().get("message").toString())
            .doesNotContain("按严格同部门跳过活码");
    }

    // ==================== helpers ====================

    private static QrCode qr(Long departmentId) {
        return QrCode.builder().id(1L).schoolName("某学校").departmentId(departmentId).build();
    }

    private static Employee emp(String userid, long departmentId) {
        return Employee.builder().userid(userid).name(userid)
            .department("[" + departmentId + "]").build();
    }

    private static Agent agent(String userid) {
        return Agent.builder().userid(userid).name(userid)
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build();
    }
}
