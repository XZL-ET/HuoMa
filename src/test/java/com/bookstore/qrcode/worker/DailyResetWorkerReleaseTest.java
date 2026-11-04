package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 每日重置释放临时顶替接待员 集成测试。
 *
 * <p>验证 {@link DailyResetWorker#recoverFullAgents} 在服务老师恢复 active 后，
 * 将临时顶替的接待员（{@code temporary=true}）从活码中移除（{@code removed}）。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("每日重置释放临时顶替 集成测试")
class DailyResetWorkerReleaseTest extends BaseIntegrationTest {

    @Autowired private DailyResetWorker dailyResetWorker;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private WecomApiClient wecomApi;
    @Autowired private CacheManager cacheManager;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        // departmentTree 无参缓存 key 固定，前一用例缓存后会让部门树故障桩命中缓存而失效
        var treeCache = cacheManager.getCache("departmentTree");
        if (treeCache != null) treeCache.clear();

        qrAgentRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        whitelistRepo.deleteAll();
        employeeRepo.deleteAll();

        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("释放测试学校").schoolId("SCH-RELEASE")
            .regionCity("深圳").regionDistrict("南山区").build());

        // 服务老师：full（昨日日限下码）
        agentRepo.save(Agent.builder()
            .userid("agent_svc").name("服务老师")
            .role(Agent.AgentRole.service)
            .overallStatus(Agent.OverallStatus.normal).build());
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid("agent_svc")
            .role(QrAgent.AgentRole.service)
            .status(QrAgent.AgentStatus.full)
            .dailyCurrent(200).build());

        // 临时顶替接待员：active + temporary=true
        agentRepo.save(Agent.builder()
            .userid("agent_temp").name("临时接待员")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build());
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid("agent_temp")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.active)
            .temporary(true).build());
    }

    @Test
    @DisplayName("服务老师恢复 active 后，临时顶替接待员被释放为 removed")
    void shouldReleaseTemporaryReceptionistAfterServiceTeacherRecovers() {
        dailyResetWorker.recoverFullAgents();

        // 服务老师恢复 active
        QrAgent svc = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_svc").orElseThrow();
        assertThat(svc.getStatus()).isEqualTo(QrAgent.AgentStatus.active);

        // 临时顶替接待员被释放
        QrAgent temp = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_temp").orElseThrow();
        assertThat(temp.getStatus()).isEqualTo(QrAgent.AgentStatus.removed);
    }

    @Test
    @DisplayName("服务老师熔断未恢复时，临时顶替接待员保留")
    void shouldKeepTemporaryReceptionistWhenServiceTeacherBlocked() {
        // 服务老师熔断 → recoverFullAgents 不恢复
        agentRepo.save(Agent.builder()
            .userid("agent_svc").name("服务老师")
            .role(Agent.AgentRole.service)
            .overallStatus(Agent.OverallStatus.melted).build());

        dailyResetWorker.recoverFullAgents();

        // 服务老师仍 full
        QrAgent svc = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_svc").orElseThrow();
        assertThat(svc.getStatus()).isEqualTo(QrAgent.AgentStatus.full);

        // 临时顶替接待员保留（保证 contact_way 不为空）
        QrAgent temp = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_temp").orElseThrow();
        assertThat(temp.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    @Test
    @DisplayName("熔断的临时顶替（full）应被清理为 removed，避免数据残留")
    void shouldRemoveFullTemporaryReceptionistWhenMelted() {
        // 临时顶替日限下码（full）且熔断 → 第 2 步不恢复，第 3 步应直接清理
        QrAgent temp = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_temp").orElseThrow();
        temp.setStatus(QrAgent.AgentStatus.full);
        qrAgentRepo.save(temp);

        agentRepo.save(Agent.builder()
            .userid("agent_temp").name("临时接待员")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.melted).build());

        dailyResetWorker.recoverFullAgents();

        QrAgent after = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "agent_temp").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(QrAgent.AgentStatus.removed);
    }

    @Test
    @DisplayName("每日重置：无关接待员 full 不复活，服务老师不受影响")
    void doesNotReviveDisallowedReceptionist() {
        whitelistRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        employeeRepo.save(Employee.builder().userid("bad").name("bad")
            .department("[200]").active(true).wechatStatus(1).build());
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.full).build());

        dailyResetWorker.recoverFullAgents();

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
    }

    @Test
    @DisplayName("部门树不可用：跳过准入守卫，子孙部门接待员照常复活")
    void revivesDescendantReceptionistWhenTreeUnavailable() {
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        // 员工在 100 的子孙部门 101：降级为精确 ID 匹配时会被误判为不相关，
        // 只有真正拉到部门树才能判定允许，因此这个用例专门覆盖“树不可用”路径
        employeeRepo.save(Employee.builder().userid("child").name("child")
            .department("[101]").active(true).wechatStatus(1).build());
        agentRepo.save(Agent.builder().userid("child").name("child")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.normal).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("child")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.full).build());

        when(wecomApi.listDepartments(null)).thenThrow(new RuntimeException("down"));
        try {
            dailyResetWorker.recoverFullAgents();

            assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "child"))
                .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                    .isEqualTo(QrAgent.AgentStatus.active));
        } finally {
            // 共享 mock 的 thenThrow 不能泄漏到本类其他用例；thenThrow 桩无法用 when() 直接覆盖，
            // 必须先 reset 再恢复基础桩
            reset(wecomApi);
            WecomApiMockConfig.reapplyBaseStubs(wecomApi);
        }
    }
}
