package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.OperationLogRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.service.DepartmentWhitelistApplyService;
import com.bookstore.qrcode.service.DepartmentWhitelistService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 部门准入：预览与应用编排。
 *
 * <p>验证 apply 保存白名单、移出无关池成员、逐活码撤人并写审计；
 * preview 只读；部门树不可用时整体中止且不写库；重复 apply 幂等。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：预览与应用")
class DepartmentWhitelistApplyFlowTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistApplyService applyService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private OperationLogRepository operationLogRepo;
    @Autowired private WecomApiClient wecomApi;
    @Autowired private CacheManager cacheManager;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        // CacheConfig 是无条件 @Bean，spring.cache.type=none 不会生效；
        // departmentTree 无参缓存 key 固定，前一用例缓存后，部门树故障用例的 stub 会命中缓存而失效
        var treeCache = cacheManager.getCache("departmentTree");
        if (treeCache != null) treeCache.clear();
        // 共享 mock 的 thenThrow 会残留到后续用例，先复位再恢复基础桩
        reset(wecomApi);
        WecomApiMockConfig.reapplyBaseStubs(wecomApi);
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        rotateLogRepo.deleteAll();
        qrCodeRepo.deleteAll();
        operationLogRepo.deleteAll();
        whitelistRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("应用测试").schoolId("SCH-AP-1")
            .regionCity("深圳").regionDistrict("南山区").build());
    }

    private Employee emp(String uid, String dept) {
        return Employee.builder().userid(uid).name(uid).department(dept)
            .active(true).wechatStatus(1).build();
    }

    @Test
    @DisplayName("apply：保存白名单、移出池、撤接待员、写审计")
    void appliesWhitelistAndCleansUp() {
        employeeRepo.saveAll(List.of(emp("bad", "[200]"), emp("good", "[100]")));
        agentRepo.saveAll(List.of(
            Agent.builder().userid("bad").name("bad")
                .role(Agent.AgentRole.receptionist).build(),
            Agent.builder().userid("good").name("good")
                .role(Agent.AgentRole.receptionist).build()));
        poolRepo.saveAll(List.of(
            GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
                .status(GlobalAgentPool.PoolStatus.standby).build(),
            GlobalAgentPool.builder().agentUserid("good").departmentId(100L)
                .status(GlobalAgentPool.PoolStatus.standby).build()));
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        var result = applyService.apply(List.of(100L), "tester");

        assertThat(result.allowedDeptCount()).isEqualTo(1);
        assertThat(result.poolRemoved()).isEqualTo(1);
        assertThat(result.activeReceptionistsRemoved()).isEqualTo(1);
        assertThat(result.backfilled()).isEqualTo(1);
        assertThat(poolRepo.findByAgentUserid("bad")).isEmpty();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .isNotEmpty();
        assertThat(whitelistRepo.findAllByOrderByDeptIdAsc()).hasSize(1);
        assertThat(operationLogRepo.findAll()).anySatisfy(log ->
            assertThat(log.getAction()).isEqualTo("dept_whitelist_apply"));
    }

    @Test
    @DisplayName("preview 只读：按待应用部门算出影响，但不写白名单、不动数据")
    void previewDoesNotWrite() {
        employeeRepo.save(emp("bad", "[200]"));
        // global_agent_pool.agent_userid 有 agent 外键，必须先建 Agent 记录
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
        poolRepo.save(GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
            .status(GlobalAgentPool.PoolStatus.standby).build());

        var preview = applyService.preview(List.of(100L));

        assertThat(preview.poolRemovals()).hasSize(1);
        assertThat(preview.poolRemovals().get(0).userid()).isEqualTo("bad");
        assertThat(whitelistRepo.count()).isZero();
        assertThat(poolRepo.findByAgentUserid("bad")).isPresent();
    }

    @Test
    @DisplayName("部门树失败：apply 中止且不写库")
    void applyAbortsWhenTreeUnavailable() {
        when(wecomApi.listDepartments(null)).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> applyService.apply(List.of(100L), "tester"))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
        assertThat(whitelistRepo.count()).isZero();
    }

    @Test
    @DisplayName("部门树失败：preview 同样中止且不写库")
    void previewAbortsWhenTreeUnavailable() {
        when(wecomApi.listDepartments(null)).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> applyService.preview(List.of(100L)))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
        assertThat(whitelistRepo.count()).isZero();
    }

    @Test
    @DisplayName("重复 apply 幂等：第二次不再移除")
    void applyIsIdempotent() {
        employeeRepo.saveAll(List.of(emp("bad", "[200]"), emp("good", "[100]")));
        agentRepo.saveAll(List.of(
            Agent.builder().userid("bad").name("bad")
                .role(Agent.AgentRole.receptionist).build(),
            Agent.builder().userid("good").name("good")
                .role(Agent.AgentRole.receptionist).build()));
        poolRepo.saveAll(List.of(
            GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
                .status(GlobalAgentPool.PoolStatus.standby).build(),
            GlobalAgentPool.builder().agentUserid("good").departmentId(100L)
                .status(GlobalAgentPool.PoolStatus.standby).build()));
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        var first = applyService.apply(List.of(100L), "tester");
        var second = applyService.apply(List.of(100L), "tester");

        assertThat(first.activeReceptionistsRemoved()).isEqualTo(1);
        assertThat(first.backfilled()).isEqualTo(1);
        assertThat(first.poolRemoved()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));

        assertThat(second.poolRemoved()).isZero();
        assertThat(second.activeReceptionistsRemoved()).isZero();
        assertThat(second.backfilled()).isZero();
    }
}
