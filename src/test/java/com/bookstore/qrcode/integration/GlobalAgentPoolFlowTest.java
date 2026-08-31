package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * 业务场景 4：全局员工池操作。
 *
 * <p>验证 {@link GlobalAgentPoolService} 的核心操作：
 * ensureInPool → takeStandby（悲观锁）→ markFull → dailyReset →
 * 懒清理（离职/企微不可用/封号/熔断）。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("全局员工池操作 集成测试")
class GlobalAgentPoolFlowTest extends BaseIntegrationTest {

    @Autowired private GlobalAgentPoolService poolService;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;

    @BeforeEach
    void setUp() {
        // 严格 FK 依赖顺序：先删引用子表，再删主表
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
    }

    // ================================================================
    // ensureInPool
    // ================================================================

    @Test
    @DisplayName("ensureInPool → 首次添加自动创建 Agent 和 GlobalAgentPool 记录")
    void shouldEnsureAgentInPool() {
        poolService.ensureInPool("agent1", 100);

        // Agent 记录已创建
        assertThat(agentRepo.existsById("agent1")).isTrue();
        Agent agent = agentRepo.findById("agent1").orElseThrow();
        // name 来自 wecomApi mock 的 getUserSimplelist 返回的 "Agent One"
        assertThat(agent.getName()).isEqualTo("Agent One");

        // GlobalAgentPool 记录已创建
        GlobalAgentPool pool = poolRepo.findByAgentUserid("agent1").orElseThrow();
        assertThat(pool.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.standby);
        assertThat(pool.getDailyMax()).isEqualTo(100);
        assertThat(pool.getSortOrder()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("ensureInPool → 重复入池应幂等，不重复创建")
    void shouldDeduplicateInPool() {
        poolService.ensureInPool("agent2", 100);


        long countBefore = poolRepo.count();

        // 再次入池同一员工 → 幂等
        poolService.ensureInPool("agent2", 200); // dailyMax 不同，但已存不更新


        long countAfter = poolRepo.count();
        assertThat(countAfter).isEqualTo(countBefore);

        // 原有的 dailyMax 不变
        GlobalAgentPool pool = poolRepo.findByAgentUserid("agent2").orElseThrow();
        assertThat(pool.getDailyMax()).isEqualTo(100); // 未变
    }

    // ================================================================
    // markFull + dailyReset
    // ================================================================

    @Test
    @DisplayName("markFull → standby 变 full → dailyReset → full 变 standby + dailyCurrent 清零")
    void shouldMarkFullAndResetDaily() {
        // 准备：agent3 入池 standby
        poolService.ensureInPool("agent3", 100);
        GlobalAgentPool pool = poolRepo.findByAgentUserid("agent3").orElseThrow();
        assertThat(pool.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.standby);

        // markFull
        poolService.markFull("agent3");



        GlobalAgentPool fullPool = poolRepo.findByAgentUserid("agent3").orElseThrow();
        assertThat(fullPool.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.full);
        assertThat(fullPool.getLastResetAt()).isNotNull();

        // dailyReset
        poolService.dailyReset();



        GlobalAgentPool resetPool = poolRepo.findByAgentUserid("agent3").orElseThrow();
        assertThat(resetPool.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.standby);
        assertThat(resetPool.getDailyCurrent()).isEqualTo(0);
    }

    @Test
    @DisplayName("markFull → 不存在的员工静默处理")
    void shouldSilentlyHandleMarkFullOnMissingAgent() {
        // 不存在也不应抛异常
        assertThatCode(() -> poolService.markFull("no-such-user"))
                .doesNotThrowAnyException();
    }

    // ================================================================
    // 懒清理
    // ================================================================

    @Test
    @DisplayName("takeStandby → 离职员工（active=false）被跳过并清理出池")
    void shouldFilterInactiveEmployees() {
        // agent3 入池
        poolService.ensureInPool("agent3", 100);
        // 标记为离职
        Employee emp = Employee.builder()
                .userid("agent3").name("Agent Three")
                .active(false).build();
        employeeRepo.save(emp);


        // takeStandby 应跳过离职员工并清理
        GlobalAgentPool taken = poolService.takeStandby(Set.of());
        assertThat(taken).isNull(); // 池中仅有的员工已离职 → 无可用

        // 且 agent3 应从池中删除
        assertThat(poolRepo.findByAgentUserid("agent3")).isEmpty();
    }

    @Test
    @DisplayName("takeStandby → 企微未激活员工（wechatStatus!=1）被跳过并清理")
    void shouldFilterWechatUnavailableEmployees() {
        poolService.ensureInPool("agent4", 100);
        Employee emp = Employee.builder()
                .userid("agent4").name("Agent Four")
                .active(true)           // Employee 记录仍 active
                .wechatStatus(2)        // 但企微侧已禁用
                .build();
        employeeRepo.save(emp);


        GlobalAgentPool taken = poolService.takeStandby(Set.of());
        assertThat(taken).isNull(); // 唯一员工被跳过

        assertThat(poolRepo.findByAgentUserid("agent4")).isEmpty(); // 已清理
    }

    // ================================================================
    // 角色漂移：可入池判定基于 qr_agent 活跃绑定，而非 agent.role
    // ================================================================

    private QrCode newQrCode(String schoolId) {
        return qrCodeRepo.save(QrCode.builder()
                .schoolName("漂移测试学校").schoolId(schoolId)
                .regionCity("深圳").regionDistrict("南山区").build());
    }

    @Test
    @DisplayName("isPoolEligible — 无绑定→可入池；有 receptionist/dual 绑定→可入池；仅 service 绑定→不可入池")
    void shouldDeterminePoolEligibilityByActiveBindings() {
        // 漂移员工：agent.role=service，但活码上是 receptionist
        agentRepo.save(Agent.builder()
                .userid("drifted").name("漂移员工")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        // 纯服务老师：agent.role=service，活码上也是 service
        agentRepo.save(Agent.builder()
                .userid("svc").name("服务老师")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        // 新员工：无任何活码绑定
        agentRepo.save(Agent.builder()
                .userid("newbie").name("新员工")
                .role(Agent.AgentRole.receptionist).dailyTotalCap(500).build());
        // 双重角色：agent.role=dual，活码上是 dual（同时接待+服务）
        agentRepo.save(Agent.builder()
                .userid("dualagent").name("双重角色")
                .role(Agent.AgentRole.dual).dailyTotalCap(500).build());

        QrCode qr = newQrCode("SCH-ELIG-001");
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("drifted")
                .role(QrAgent.AgentRole.receptionist).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("svc")
                .role(QrAgent.AgentRole.service).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("dualagent")
                .role(QrAgent.AgentRole.dual).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());

        assertThat(poolService.isPoolEligible("drifted")).isTrue();  // 有 receptionist 绑定
        assertThat(poolService.isPoolEligible("svc")).isFalse();     // 仅 service 绑定
        assertThat(poolService.isPoolEligible("newbie")).isTrue();   // 无绑定
        assertThat(poolService.isPoolEligible("dualagent")).isTrue(); // 有 dual 绑定
    }

    @Test
    @DisplayName("isPoolEligible — 无绑定的纯服务老师/双角色（如继承目标）不可入池")
    void shouldExcludeUnboundServiceOrDualAgent() {
        // 继承目标：agent.role=service，但从未上过活码（无任何 qr_agent 绑定）
        agentRepo.save(Agent.builder()
                .userid("inherit_svc").name("继承目标老师")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        // 无绑定的 dual 角色
        agentRepo.save(Agent.builder()
                .userid("inherit_dual").name("继承目标双角色")
                .role(Agent.AgentRole.dual).dailyTotalCap(500).build());

        assertThat(poolService.isPoolEligible("inherit_svc")).isFalse();
        assertThat(poolService.isPoolEligible("inherit_dual")).isFalse();
    }

    @Test
    @DisplayName("filterPoolEligible — 批量返回可入池员工（含 dual）")
    void shouldFilterPoolEligibleBatch() {
        agentRepo.save(Agent.builder()
                .userid("drifted").name("漂移员工")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        agentRepo.save(Agent.builder()
                .userid("svc").name("服务老师")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        agentRepo.save(Agent.builder()
                .userid("dualagent").name("双重角色")
                .role(Agent.AgentRole.dual).dailyTotalCap(500).build());
        // 无绑定的纯服务老师/双角色（继承目标）
        agentRepo.save(Agent.builder()
                .userid("inherit_svc").name("继承目标老师")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        agentRepo.save(Agent.builder()
                .userid("inherit_dual").name("继承目标双角色")
                .role(Agent.AgentRole.dual).dailyTotalCap(500).build());
        QrCode qr = newQrCode("SCH-ELIG-002");
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("drifted")
                .role(QrAgent.AgentRole.receptionist).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("svc")
                .role(QrAgent.AgentRole.service).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("dualagent")
                .role(QrAgent.AgentRole.dual).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());

        Set<String> eligible = poolService.filterPoolEligible(
                List.of("drifted", "svc", "newbie", "dualagent", "inherit_svc", "inherit_dual"));

        assertThat(eligible).containsExactlyInAnyOrder("drifted", "newbie", "dualagent");
    }

    @Test
    @DisplayName("takeStandby → 角色漂移员工（agent.role=service 但活码上有 receptionist 绑定）不应被清理出池")
    void shouldNotRemoveDriftedEmployeeWithReceptionistBinding() {
        agentRepo.save(Agent.builder()
                .userid("drifted").name("漂移员工")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        QrCode qr = newQrCode("SCH-DRIFT-001");
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("drifted")
                .role(QrAgent.AgentRole.receptionist).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        poolService.ensureInPool("drifted", 100);

        GlobalAgentPool taken = poolService.takeStandby(Set.of());

        assertThat(taken).isNotNull();
        assertThat(taken.getAgentUserid()).isEqualTo("drifted");
        // 不应被清理出池
        assertThat(poolRepo.findByAgentUserid("drifted")).isPresent();
    }

    @Test
    @DisplayName("takeStandby → 纯服务老师（仅有 service 绑定）被清理出池")
    void shouldRemovePureServiceTeacher() {
        agentRepo.save(Agent.builder()
                .userid("svc").name("服务老师")
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
        QrCode qr = newQrCode("SCH-SVC-001");
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("svc")
                .role(QrAgent.AgentRole.service).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        poolService.ensureInPool("svc", 100);

        GlobalAgentPool taken = poolService.takeStandby(Set.of());

        assertThat(taken).isNull();
        assertThat(poolRepo.findByAgentUserid("svc")).isEmpty();
    }

    @Test
    @DisplayName("takeStandby → 双重角色员工（仅有 dual 绑定）不被清理出池")
    void shouldNotRemoveDualEmployee() {
        agentRepo.save(Agent.builder()
                .userid("dualagent").name("双重角色")
                .role(Agent.AgentRole.dual).dailyTotalCap(500).build());
        QrCode qr = newQrCode("SCH-DUAL-001");
        qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("dualagent")
                .role(QrAgent.AgentRole.dual).dailyMax(100)
                .status(QrAgent.AgentStatus.active).build());
        poolService.ensureInPool("dualagent", 100);

        GlobalAgentPool taken = poolService.takeStandby(Set.of());

        assertThat(taken).isNotNull();
        assertThat(taken.getAgentUserid()).isEqualTo("dualagent");
        assertThat(poolRepo.findByAgentUserid("dualagent")).isPresent();
    }
}
