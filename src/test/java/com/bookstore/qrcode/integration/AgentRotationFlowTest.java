package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.AgentRotationService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 业务场景 3：员工轮换 → 后备激活 → 阈值告警。
 *
 * <p>验证 {@link AgentRotationService} 的三级阈值机制：
 * 扩容（full）→ 预激活（urgent）→ 预警（warn），
 * 以及分布式锁、后备池公平轮转、手动模式跳过、空池告警。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("员工轮换 → 后备激活 集成测试")
class AgentRotationFlowTest extends BaseIntegrationTest {

    @Autowired private QrCodeService qrCodeService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private GlobalAgentPoolService poolService;
    @Autowired private AgentRotationService rotationService;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private StringRedisTemplate redisTemplate;

    private QrCode testQr;

    @BeforeEach
    void setUp() {
        rotateLogRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();

        // 创建活码，绑定 agent1（主接待员）, agent2（后备）
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("轮换测试学校");
        req.setSchoolId("SCH-ROTATE-001");
        req.setRegionCity("深圳");
        req.setRegionDistrict("南山区");
        req.setServiceTeacherUserid("agent1,agent2");
        req.setInitialAgentUserids("agent1,agent2");
        testQr = qrCodeService.create(req);

        // 确保 agent3, agent4, agent5 也在后备池中（供轮换使用）
        poolService.ensureInPool("agent3", 100);
        poolService.ensureInPool("agent4", 100);
        poolService.ensureInPool("agent5", 100);

        // service methods have their own @Transactional
    }

    // ================================================================
    // 扩容场景：达到 full 阈值
    // ================================================================

    @Test
    @DisplayName("达到日限 → 扩容：旧员工下码(full) + 新员工上码(active) + 轮换日志")
    void shouldExpandWhenDailyLimitReached() {
        // agent1 的 dailyMax=150，直接调 expandQrCodeUsers 模拟日限到达
        GlobalAgentPool agent1Pool = poolRepo.findByAgentUserid("agent1").orElseThrow();
        QrAgent agent1Qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(testQr.getId(), "agent1").orElseThrow();

        long activeCountBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).stream()
                .filter(a -> a.getStatus() == QrAgent.AgentStatus.active).count();

        rotationService.expandQrCodeUsers(
                testQr.getId(), "agent1", testQr, agent1Pool);

        // flush/clear not needed — service methods manage their own transactions

        // 1. agent1 的 QrAgent.status → full
        QrAgent agent1Updated = qrAgentRepo.findByQrCodeIdAndAgentUserid(testQr.getId(), "agent1").orElseThrow();
        assertThat(agent1Updated.getStatus()).isEqualTo(QrAgent.AgentStatus.full);
        assertThat(agent1Updated.getReplacedBy()).isNotNull(); // 被谁替换

        // 2. 新 agent 上码（从后备池取）
        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(testQr.getId());
        long activeNow = agents.stream().filter(a -> a.getStatus() == QrAgent.AgentStatus.active).count();
        // 扩容后 active 数应不变（老的变 full、新的变 active 各+1 抵消）
        assertThat(activeNow).isGreaterThanOrEqualTo(activeCountBefore);

        // 3. agent1 的 GlobalAgentPool.status → full
        GlobalAgentPool pool1 = poolRepo.findByAgentUserid("agent1").orElseThrow();
        assertThat(pool1.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.full);

        // 4. 轮换日志已创建
        List<QrRotateLog> logs = rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
                testQr.getId(), org.springframework.data.domain.Pageable.unpaged());
        assertThat(logs).isNotEmpty();
        QrRotateLog lastLog = logs.get(0);
        assertThat(lastLog.getToUserid()).isNotNull();       // 接替者 userid
        assertThat(lastLog.getReason()).contains("扩容");
    }

    @Test
    @DisplayName("手动模式 rotateMode=manual → 不自动扩容")
    void shouldSkipRotationInManualMode() {
        // 切换为手动模式
        testQr.setRotateMode(QrCode.RotateMode.manual);
        qrCodeRepo.save(testQr);
        // service methods have their own @Transactional

        GlobalAgentPool agent1Pool = poolRepo.findByAgentUserid("agent1").orElseThrow();
        long activeBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).stream()
                .filter(a -> a.getStatus() == QrAgent.AgentStatus.active).count();

        rotationService.expandQrCodeUsers(
                testQr.getId(), "agent1", testQr, agent1Pool);

        // service methods have their own @Transactional
        // manual 模式 → 应直接 return，无变化
        long activeAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).stream()
                .filter(a -> a.getStatus() == QrAgent.AgentStatus.active).count();
        assertThat(activeAfter).isEqualTo(activeBefore);
    }

    // ================================================================
    // 阈值检查
    // ================================================================

    @Test
    @DisplayName("checkAndRotate — globalCount >= dailyMax → 触发扩容")
    void shouldTriggerCheckAndRotateAtFullThreshold() {
        int agentsBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).size();

        // agent1 的 dailyMax 默认 150，传 globalCount=150 触发扩容
        rotationService.checkAndRotate(testQr.getId(), "agent1", 150);

        // flush/clear not needed — service methods manage their own transactions

        // 扩容后应多一个 QrAgent
        int agentsAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore + 1);
    }

    @Test
    @DisplayName("checkAndRotate — globalCount 低于阈值 → 无变更")
    void shouldNotChangeBelowThreshold() {
        int agentsBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).size();

        // agent1 的 dailyMax=150（bindAgents 默认值），warnRatio=80 → warnThreshold=120
        // globalCount=10 低于 warn 阈值 → 应无变更
        rotationService.checkAndRotate(testQr.getId(), "agent1", 10);

        int agentsAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore);
    }

    // ================================================================
    // 后备池操作
    // ================================================================

    @Test
    @DisplayName("takeStandby — 公平轮转：取走后 sortOrder 移至队尾")
    void shouldTakeStandbyWithFairRotation() {
        int maxBefore = poolRepo.findFirstByOrderBySortOrderDesc()
                .map(GlobalAgentPool::getSortOrder).orElse(0);

        GlobalAgentPool taken = poolService.takeStandby(java.util.Set.of("agent1", "agent2"));
        assertThat(taken).isNotNull();

        // 被取走的员工 sortOrder 应变为 max+1（排到队尾）
        GlobalAgentPool updated = poolRepo.findByAgentUserid(taken.getAgentUserid()).orElseThrow();
        assertThat(updated.getSortOrder()).isGreaterThanOrEqualTo(maxBefore + 1);
    }

    @Test
    @DisplayName("takeStandby — 排除列表中的员工不被选中")
    void shouldExcludeSpecifiedUsers() {
        // 排除所有已知员工 → 池空
        GlobalAgentPool taken = poolService.takeStandby(
                java.util.Set.of("agent1", "agent2", "agent3", "agent4", "agent5"));
        assertThat(taken).isNull();
    }

    @Test
    @DisplayName("takeStandby — blocked 员工被跳过并清理")
    void shouldSkipBlockedAgentInPool() {
        // 将 agent5 标记为 blocked
        poolService.ensureInPool("agent5", 100);
        Agent blocked = agentRepo.findById("agent5").orElseGet(() -> {
            Agent a = Agent.builder()
                    .userid("agent5").name("Agent Five")
                    .overallStatus(Agent.OverallStatus.blocked).build();
            return agentRepo.save(a);
        });
        blocked.setOverallStatus(Agent.OverallStatus.blocked);
        agentRepo.save(blocked);
        // service methods have their own @Transactional

        // 排除 agent1-4，确保迭代到达 agent5 → 发现 blocked → 清理出池 → 返回 null
        GlobalAgentPool taken = poolService.takeStandby(
                java.util.Set.of("agent1", "agent2", "agent3", "agent4"));
        // agent5 应被跳过并清理，池中无其他可用 → null
        assertThat(taken).isNull();

        // 且 agent5 应从池中被删除（懒清理）
        assertThat(poolRepo.findByAgentUserid("agent5")).isEmpty();
    }
}
