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

        // 确保 agent1-agent5 在后备池中（作为接待员供轮换测试使用）
        poolService.ensureInPool("agent1", 100);
        poolService.ensureInPool("agent2", 100);
        poolService.ensureInPool("agent3", 100);
        poolService.ensureInPool("agent4", 100);
        poolService.ensureInPool("agent5", 100);

        // 创建活码，绑定 agent_svc 为服务老师（不入全局池）
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("轮换测试学校");
        req.setSchoolId("SCH-ROTATE-001");
        req.setRegionCity("深圳");
        req.setRegionDistrict("南山区");
        req.setStudentCount(500);
        req.setServiceTeacherUserid("agent_svc");
        req.setInitialAgentUserids("agent_svc");
        testQr = qrCodeService.create(req);

        // 手动将 agent1 添加为活码上的接待员（供轮换测试使用）
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(testQr.getId()).agentUserid("agent1")
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(100)
            .sortOrder(1)
            .status(QrAgent.AgentStatus.active).build());
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
        assertThat(lastLog.getFromUserid()).isEqualTo("agent1"); // 下码员工
        assertThat(lastLog.getToUserid()).isNotNull();       // 接替者 userid
        assertThat(lastLog.getReason()).contains("扩容");
    }

    @Test
    @DisplayName("expandQrCodeUsers — 临时顶替满员，接替者继承 temporary 标记")
    void shouldInheritTemporaryOnExpand() {
        // 将 agent1 标记为临时顶替
        QrAgent agent1Qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(testQr.getId(), "agent1").orElseThrow();
        agent1Qa.setTemporary(true);
        qrAgentRepo.save(agent1Qa);

        GlobalAgentPool agent1Pool = poolRepo.findByAgentUserid("agent1").orElseThrow();

        // agent1 满员触发扩容，从池里补入接替者
        rotationService.expandQrCodeUsers(testQr.getId(), "agent1", testQr, agent1Pool);

        // 接替者应继承 temporary=true（次日一并释放）
        QrAgent successor = qrAgentRepo.findByQrCodeIdAndStatus(testQr.getId(), QrAgent.AgentStatus.active)
            .stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).findFirst().orElseThrow();
        assertThat(successor.getAgentUserid()).isNotEqualTo("agent1");
        assertThat(successor.getTemporary()).isTrue();
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

        // agent1 的 dailyMax=100，传 globalCount=150 触发扩容
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

        // agent1 的 dailyMax=100，warnRatio=80 → warnThreshold=80
        // globalCount=10 低于 warn 阈值 → 应无变更
        rotationService.checkAndRotate(testQr.getId(), "agent1", 10);

        int agentsAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore);
    }

    @Test
    @DisplayName("checkAndRotate — 服务老师日限到达下码，不补人，继承照常")
    void shouldDownCodeServiceTeacherWhenDailyLimitReached() {
        // 服务老师 agent_svc 不应在全局池（bindAgents 里 ensureAgent 只建 Agent 不入池）
        assertThat(poolRepo.findByAgentUserid("agent_svc")).isEmpty();

        int agentsBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).size();

        // agent_svc 活码级 dailyMax=150，globalCount=200 超日限 → 服务老师下码（status=full）
        rotationService.checkAndRotate(testQr.getId(), "agent_svc", 200);

        // 服务老师 status 变为 full，且不补人（agent 数量不变）
        QrAgent svcUpdated = qrAgentRepo.findByQrCodeIdAndAgentUserid(testQr.getId(), "agent_svc").orElseThrow();
        assertThat(svcUpdated.getStatus()).isEqualTo(QrAgent.AgentStatus.full);

        int agentsAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore);

        // 下码应写轮换日志：fromUserid=服务老师，toUserid 为空（无接替）
        QrRotateLog downLog = rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
                testQr.getId(), org.springframework.data.domain.Pageable.unpaged()).get(0);
        assertThat(downLog.getFromUserid()).isEqualTo("agent_svc");
        assertThat(downLog.getToUserid()).isNull();
        assertThat(downLog.getReason()).contains("服务老师日限下码");
    }

    @Test
    @DisplayName("checkAndRotate — 服务老师是活码唯一成员时日限下码，先补一名接待员再下码")
    void shouldSupplementBeforeDownCodeWhenServiceTeacherIsOnlyActive() {
        // 新建一个只有服务老师、无接待员的活码（create 不额外补接待员）
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("唯一服务老师学校");
        req.setSchoolId("SCH-ONLY-SVC");
        req.setRegionCity("深圳");
        req.setRegionDistrict("南山区");
        req.setStudentCount(300);
        req.setServiceTeacherUserid("agent_svc");
        req.setInitialAgentUserids("agent_svc");
        QrCode onlySvcQr = qrCodeService.create(req);

        int agentsBefore = qrAgentRepo.findByQrCodeId(onlySvcQr.getId()).size();
        // 此时活码上只有服务老师 agent_svc 一个 active 成员
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(onlySvcQr.getId(), QrAgent.AgentStatus.active))
            .extracting(QrAgent::getAgentUserid).containsExactly("agent_svc");

        // 服务老师日限到达 → 应先补员再下码
        rotationService.checkAndRotate(onlySvcQr.getId(), "agent_svc", 200);

        // 服务老师已下码
        QrAgent svcUpdated = qrAgentRepo.findByQrCodeIdAndAgentUserid(onlySvcQr.getId(), "agent_svc").orElseThrow();
        assertThat(svcUpdated.getStatus()).isEqualTo(QrAgent.AgentStatus.full);

        // 已补一名接待员 → 总数 +1
        int agentsAfter = qrAgentRepo.findByQrCodeId(onlySvcQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore + 1);

        // 新成员是 active 接待员，保证 contact_way 不为空，且标记为临时顶替
        QrAgent supplement = qrAgentRepo.findByQrCodeIdAndStatus(onlySvcQr.getId(), QrAgent.AgentStatus.active)
            .stream().filter(a -> !"agent_svc".equals(a.getAgentUserid())).findFirst().orElseThrow();
        assertThat(supplement.getRole()).isEqualTo(QrAgent.AgentRole.receptionist);
        assertThat(supplement.getTemporary()).isTrue();

        // 轮换日志包含补员记录 + 下码记录
        List<QrRotateLog> logs = rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
                onlySvcQr.getId(), org.springframework.data.domain.Pageable.unpaged());
        assertThat(logs).anyMatch(l -> "服务老师下码前同部门补员".equals(l.getReason()));
        assertThat(logs).anyMatch(l -> l.getReason() != null && l.getReason().contains("服务老师日限下码"));
    }

    @Test
    @DisplayName("checkAndRotate — 服务老师已下码，重复调用不重复写下码日志")
    void shouldNotDuplicateDownCodeWhenServiceTeacherAlreadyFull() {
        // 第一次：下码
        rotationService.checkAndRotate(testQr.getId(), "agent_svc", 200);

        // 第二次：服务老师已 full，应跳过（幂等），不重复写日志
        rotationService.checkAndRotate(testQr.getId(), "agent_svc", 200);

        long downCodeLogs = rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
                testQr.getId(), org.springframework.data.domain.Pageable.unpaged()).stream()
            .filter(l -> l.getReason() != null && l.getReason().contains("服务老师日限下码"))
            .count();
        assertThat(downCodeLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("checkAndRotate — 服务老师唯一成员且全局池枯竭，补员失败则不下码")
    void shouldKeepServiceTeacherActiveWhenPoolExhausted() {
        // 新建只有服务老师、无接待员的活码
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("池枯竭学校");
        req.setSchoolId("SCH-POOL-EMPTY");
        req.setRegionCity("深圳");
        req.setRegionDistrict("南山区");
        req.setStudentCount(300);
        req.setServiceTeacherUserid("agent_svc");
        req.setInitialAgentUserids("agent_svc");
        QrCode onlySvcQr = qrCodeService.create(req);

        // 清空全局池，模拟池枯竭（takeStandby 返回 null）
        poolRepo.deleteAll();

        // 服务老师日限到达 → 补员失败 → 保持 active 不下码
        rotationService.checkAndRotate(onlySvcQr.getId(), "agent_svc", 200);

        QrAgent svc = qrAgentRepo.findByQrCodeIdAndAgentUserid(onlySvcQr.getId(), "agent_svc").orElseThrow();
        assertThat(svc.getStatus()).isEqualTo(QrAgent.AgentStatus.active);

        // 未补入接待员，活码仍只有服务老师一人（避免 contact_way 变空）
        assertThat(qrAgentRepo.findByQrCodeId(onlySvcQr.getId())).hasSize(1);
    }

    @Test
    @DisplayName("checkAndRotate — 接待员不在全局池，日限到达仍应触发扩容补人")
    void shouldExpandWhenReceptionistNotInPool() {
        // 构造角色漂移的接待员：全局 agent.role=service（不入池），但活码上是 receptionist
        agentRepo.save(Agent.builder()
            .userid("drifted_agent").name("漂移接待员")
            .role(Agent.AgentRole.service)
            .dailyTotalCap(500).build());
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(testQr.getId()).agentUserid("drifted_agent")
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(100)
            .sortOrder(10)
            .status(QrAgent.AgentStatus.active).build());
        assertThat(poolRepo.findByAgentUserid("drifted_agent")).isEmpty();

        int agentsBefore = qrAgentRepo.findByQrCodeId(testQr.getId()).size();

        // drifted_agent 活码级 dailyMax=100，globalCount=150 超日限 → 应触发扩容补 1 人
        rotationService.checkAndRotate(testQr.getId(), "drifted_agent", 150);

        int agentsAfter = qrAgentRepo.findByQrCodeId(testQr.getId()).size();
        assertThat(agentsAfter).isEqualTo(agentsBefore + 1);
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
