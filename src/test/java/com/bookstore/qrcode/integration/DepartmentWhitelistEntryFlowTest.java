package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.service.EmployeeSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部门准入：入池过滤与漂移清理。
 *
 * <p>验证 {@link EmployeeSyncService#syncToGlobalPool()} 在部门白名单启用时
 * 只新增允许部门员工，并清理已漂移到白名单之外的池记录；
 * 白名单未启用时保持原行为。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：入池过滤与漂移清理")
class DepartmentWhitelistEntryFlowTest extends BaseIntegrationTest {

    @Autowired private EmployeeSyncService employeeSyncService;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    @BeforeEach
    void setUp() {
        poolRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
    }

    private Employee emp(String uid, String dept) {
        return Employee.builder().userid(uid).name(uid).department(dept)
            .active(true).wechatStatus(1).build();
    }

    @Test
    @DisplayName("只新增允许部门员工，移出已漂移的池记录")
    void syncAddsAllowedAndRemovesDrifted() {
        employeeRepo.saveAll(List.of(
            emp("ok", "[100]"),
            emp("mixed", "[200,100]"),
            emp("bad", "[200]"),
            emp("none", null)));
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
        poolRepo.save(GlobalAgentPool.builder().agentUserid("bad")
            .status(GlobalAgentPool.PoolStatus.standby).build());

        employeeSyncService.syncToGlobalPool();

        assertThat(poolRepo.findByAgentUserid("ok")).isPresent();
        assertThat(poolRepo.findByAgentUserid("mixed")).isPresent();
        assertThat(poolRepo.findByAgentUserid("bad")).isEmpty();
        assertThat(poolRepo.findByAgentUserid("none")).isEmpty();
    }

    @Test
    @DisplayName("白名单未启用时保持原行为：全部在职可用员工入池")
    void inactiveWhitelistAllowsAll() {
        whitelistRepo.deleteAll();
        employeeRepo.save(emp("bad", "[200]"));

        employeeSyncService.syncToGlobalPool();

        assertThat(poolRepo.findByAgentUserid("bad")).isPresent();
    }
}
