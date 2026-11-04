package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.service.DepartmentWhitelistApplyService;
import com.bookstore.qrcode.service.DepartmentWhitelistService;
import com.bookstore.qrcode.service.EmployeeSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部门准入：配置 → 预览/应用 → 日常维护 的全链路回归。
 *
 * <p>覆盖：应用白名单后无关部门接待员被移出池并撤码、允许部门员工补位；
 * 日常同步不会把无关员工重新入池；次日重置即使无关接待员被手工恢复成 full
 * 也不会复活。</p>
 *
 * <p>必须位于 {@code com.bookstore.qrcode.worker} 包：{@link DailyResetWorker#recoverFullAgents()}
 * 是包级可见方法，{@code integration} 包无法调用。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：全链路")
class DepartmentWhitelistEndToEndTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistApplyService applyService;
    @Autowired private EmployeeSyncService employeeSyncService;
    @Autowired private DepartmentWhitelistService whitelistService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private DailyResetWorker dailyResetWorker;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        // CacheConfig 是无条件 @Bean，spring.cache.type=none 不会生效；
        // departmentTree 无参缓存 key 固定，须在用例间清理，避免读到上一个用例的部门树
        var treeCache = cacheManager.getCache("departmentTree");
        if (treeCache != null) treeCache.clear();
        // qr_rotate_log.qr_code_id 有真实外键：apply 会写轮换日志，必须在删活码前先清日志
        rotateLogRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
    }

    @Test
    @DisplayName("配置白名单 → 清理 → 日常同步只补允许员工 → 次日不复活无关接待员")
    void endToEnd() {
        // 1. 种子：允许部门(100，含子部门101)、无关部门(200)
        employeeRepo.saveAll(List.of(
            Employee.builder().userid("allowed").name("allowed").department("[101]")
                .active(true).wechatStatus(1).build(),
            Employee.builder().userid("other").name("other").department("[200]")
                .active(true).wechatStatus(1).build()));
        agentRepo.saveAll(List.of(
            Agent.builder().userid("allowed").name("allowed")
                .role(Agent.AgentRole.receptionist).overallStatus(Agent.OverallStatus.normal).build(),
            Agent.builder().userid("other").name("other")
                .role(Agent.AgentRole.receptionist).overallStatus(Agent.OverallStatus.normal).build()));
        poolRepo.saveAll(List.of(
            GlobalAgentPool.builder().agentUserid("allowed").departmentId(101L)
                .status(GlobalAgentPool.PoolStatus.standby).build(),
            GlobalAgentPool.builder().agentUserid("other").departmentId(200L)
                .status(GlobalAgentPool.PoolStatus.standby).build()));
        QrCode qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("全链路").schoolId("SCH-E2E-1")
            .regionCity("深圳").regionDistrict("南山区").build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("other")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        // 2. 应用白名单：子部门 101 命中，other 被移出池并撤码，allowed 补位
        var result = applyService.apply(List.of(100L), "tester");
        assertThat(result.poolRemoved()).isEqualTo(1);
        assertThat(result.activeReceptionistsRemoved()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .extracting(QrAgent::getAgentUserid).containsExactly("allowed");

        // 3. 日常同步不再把 other 加回池
        employeeSyncService.syncToGlobalPool();
        assertThat(poolRepo.findByAgentUserid("other")).isEmpty();

        // 4. 次日重置：即使 other 被手工恢复成 full，也不会复活
        QrAgent other = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "other").orElseThrow();
        other.setStatus(QrAgent.AgentStatus.full);
        qrAgentRepo.save(other);
        dailyResetWorker.recoverFullAgents();
        assertThat(qrAgentRepo.findById(other.getId()).orElseThrow().getStatus())
            .isEqualTo(QrAgent.AgentStatus.removed);
    }
}
