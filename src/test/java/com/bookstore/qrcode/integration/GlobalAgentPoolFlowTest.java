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
}
