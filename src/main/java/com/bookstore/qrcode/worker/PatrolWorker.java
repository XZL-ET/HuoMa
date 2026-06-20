package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时巡检任务 —— 每 5 分钟扫描全系统异常状态。
 *
 * <p><b>调度说明：</b>使用 Spring {@code @Scheduled(cron = "0 *&#47;5 * * * *")}，
 * 每 5 分钟（整 5 分倍数触发）执行一次巡检。</p>
 *
 * <p><b>巡检项目（共 3 项）：</b>
 * <ol>
 *   <li><b>检查全局池余量</b> —— 检查全局员工池 standby 数量，不足时发送告警；</li>
 *   <li><b>检查全员高负载活码</b> —— 遍历所有活跃活码，如果该活码下所有活跃接待员的
 *       当日接待量都已达到日上限的 90% 以上，则产生 {@code traffic_spike} 告警；</li>
 *   <li><b>统计今日告警</b> —— 占位方法，等待 DashboardService 实现。</li>
 * </ol>
 * </p>
 *
 * <p>该类使用 SLF4J debug 级别记录巡检起止，巡检本身的异常会被框架捕获并记录，不影响下一次执行。</p>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatrolWorker {

    private final QrAgentRepository qrAgentRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AlertService alertService;
    private final RateLimiterService rateLimiterService;
    private final MessageGuardService messageGuardService;
    private final com.bookstore.qrcode.service.EmployeeSyncService employeeSyncService;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;
    private final WecomApiClient wecomApi;
    private final OperationLogRepository operationLogRepo;

    /** 自注入代理 — 让本类方法上的 @Transactional 生效 */
    @Lazy
    @Autowired
    private PatrolWorker self;

    /** 上次 DLQ 自动重放时间戳 — 限流：每 30 分钟最多重放一次 */
    private long lastDlqReplayTime = 0L;

    /**
     * 每 5 分钟执行一次的主巡检入口。
     *
     * <p>依次执行三项检查：空后备池、高负载活码、今日告警统计。</p>
     *
     * @see #checkGlobalPoolLow()
     * @see #checkOverloadedQrCodes()
     * @see #countTodayAlerts()
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void patrol() {
        log.debug("定时巡检开始");

        // 0. 池健康扫描 — 提前清理已离职/未激活/已禁用/封号/熔断的员工，
        //    避免阻塞队首导致后续 takeStandby 遍历浪费
        self.cleanUnhealthyFromPool();

        // 0.5 企微孤儿 QR 对账扫描
        try {
            self.reconcileOrphanQrCodes();
        } catch (Exception e) {
            log.error("企微对账扫描异常", e);
        }

        // 1. 检查全局池余量（扫描后人数可能下降，触发自动补充）
        checkGlobalPoolLow();

        // 2. 检查全部高负载活码
        checkOverloadedQrCodes();

        // 3. 统计今日异常
        countTodayAlerts();

        // 4. 死信队列自动重放（每 30 分钟最多重放一次，防消息永久堆积）
        try {
            long dlq = messageGuardService.dlqSize();
            if (dlq > 0 && System.currentTimeMillis() - lastDlqReplayTime > 30 * 60 * 1000L) {
                int replayed = messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY);
                if (replayed > 0) {
                    log.info("死信队列自动重放: {} 条", replayed);
                    lastDlqReplayTime = System.currentTimeMillis();
                }
            } else if (dlq > 0) {
                log.debug("死信队列积压: {} 条（距上次重放 {} 秒，跳过）",
                    dlq, (System.currentTimeMillis() - lastDlqReplayTime) / 1000);
            }
        } catch (Exception e) {
            log.debug("DLQ 重放跳过: {}", e.getMessage());
        }

        log.debug("定时巡检完成");
    }

    /**
     * 池健康扫描 — 提前清理企微侧已不可用的员工。
     *
     * <p>扫描全局池所有记录，将以下异常员工物理删除：
     * <ul>
     *   <li>Employee.active = false（已离职）</li>
     *   <li>Employee.wechatStatus ≠ 1（未激活 / 已禁用 / 已离职）</li>
     *   <li>Agent.overallStatus = blocked / melted（封号 / 熔断）</li>
     * </ul>
     *
     * <p><b>与 takeStandby 懒清理的关系：</b>此方法提前发现并移除异常员工，
     * 避免队首被堵塞，每次 takeStandby 都要遍历到他们才发现不可用。
     * blocked/melted 的员工在 {@code blockAgentForWechatIssue} 中已被同步清理，
     * 此处作为兜底覆盖漏网之鱼。</p>
     *
     * <p><b>风险：</b>纯读操作（不调企微 API），无网络/限频风险。
     * 删除后可能触发下游 {@link #checkGlobalPoolLow} 自动补充。</p>
     */
    @Transactional
    void cleanUnhealthyFromPool() {
        List<GlobalAgentPool> all = poolRepo.findAll();
        if (all.isEmpty()) return;

        // 批量加载 — 避免 N+1 查询（池 500 人 → 1000 次 DB 查询）
        Map<String, Employee> empMap = employeeRepo.findAll().stream()
            .collect(Collectors.toMap(
                Employee::getUserid, e -> e, (a, b) -> a));
        Map<String, Agent> agentMap = agentRepo.findAll().stream()
            .collect(Collectors.toMap(
                Agent::getUserid, a -> a, (a, b) -> a));

        List<GlobalAgentPool> toRemove = new ArrayList<>();
        for (GlobalAgentPool p : all) {
            Employee emp = empMap.get(p.getAgentUserid());
            if (emp != null && (!emp.getActive()
                || (emp.getWechatStatus() != null && emp.getWechatStatus() != 1))) {
                toRemove.add(p);
                continue;
            }
            Agent agent = agentMap.get(p.getAgentUserid());
            if (agent != null && (agent.getOverallStatus() == Agent.OverallStatus.blocked
                || agent.getOverallStatus() == Agent.OverallStatus.melted)) {
                toRemove.add(p);
            }
        }
        if (!toRemove.isEmpty()) {
            poolRepo.deleteAll(toRemove);
            log.info("池健康扫描：清理 {} 个异常员工", toRemove.size());
        }
    }

    /**
     * 企微孤儿 QR 码对账扫描。
     *
     * <p>扫描本地状态异常（paused/no_agent）但仍有企微 config_id 的 QR 码，
     * 逐条向企微验证是否仍需存在。若企微侧仍存在，则删除以释放资源。</p>
     *
     * <p><b>调用频率：</b>每 5 分钟。</p>
     * <p><b>API 调用量：</b>正常运行时 0-5 次/巡检。</p>
     */
    @Transactional
    void reconcileOrphanQrCodes() {
        List<QrCode> candidates = qrCodeRepo.findOrphanCandidates();
        if (candidates.isEmpty()) return;

        log.info("企微对账扫描: 发现 {} 个异常 QR 码", candidates.size());
        int deleted = 0;

        for (QrCode qr : candidates) {
            String configId = qr.getQrConfigId();
            try {
                JsonNode result = wecomApi.getContactWay(configId);
                // errcode=0 表示企微侧仍存在 → 删除
                if (result != null && result.path("errcode").asInt(0) == 0) {
                    wecomApi.deleteContactWay(configId);
                    qr.setQrConfigId(null);
                    qrCodeRepo.save(qr);
                    deleted++;

                    // 记录操作审计
                    OperationLog oplog = OperationLog.builder()
                        .operator("system(reconciliation)")
                        .action("delete_orphan_qr")
                        .targetType("qr_code")
                        .targetId(String.valueOf(qr.getId()))
                        .detail("{\"school\":\"" + qr.getSchoolName()
                            + "\",\"config_id\":\"" + configId + "\"}")
                        .build();
                    operationLogRepo.save(oplog);
                }
                // errcode!=0 表示企微侧已不存在 → 正常，跳过
            } catch (Exception e) {
                log.warn("对账处理异常: qrCodeId={}, configId={}, msg={}",
                    qr.getId(), configId, e.getMessage());
            }
        }

        if (deleted > 0) {
            log.warn("企微对账清理完成: 删除 {} 个孤儿 QR 码", deleted);
        }
    }

    /**
     * 检查全局员工池 standby 余量，自动补充并发送告警。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>standby ≥ 10：正常，不操作</li>
     *   <li>standby ∈ [1, 9]：尝试从企微同步新员工入池补充</li>
     *   <li>standby = 0：池完全枯竭，发送告警（如果同步后仍为 0）</li>
     * </ol>
     *
     * <p>与旧逻辑不同，全局池是所有活码共享的，无需按活码逐条检查。</p>
     */
    private void checkGlobalPoolLow() {
        long standbyCount = poolRepo.countByStatus(
            GlobalAgentPool.PoolStatus.standby);
        if (standbyCount >= 10) {
            return; // 池子充足
        }

        // 余量不足，尝试从企微通讯录补充
        log.warn("全局池 standby 不足 ({} 人)，尝试自动补充...", standbyCount);
        try {
            int added = employeeSyncService.syncToGlobalPool();
            if (added > 0) {
                log.info("巡检自动补充: 新增 {} 人入池", added);
                standbyCount = poolRepo.countByStatus(
                    GlobalAgentPool.PoolStatus.standby);
            }
        } catch (Exception e) {
            log.error("巡检自动补充失败", e);
        }

        // 补充后仍不足，发送告警
        if (standbyCount == 0) {
            alertService.alertEmptyBackup(null, "全局后备池完全枯竭！已尝试自动补充但无新员工可加");
        } else if (standbyCount < 5) {
            alertService.alertEmptyBackup(null,
                "全局后备池严重不足: 仅剩 " + standbyCount + " 人，已自动补充");
        }
    }

    /**
     * 检查是否存在全员接待员都已高负载（超过日限 90%）的活码，触发流量尖峰告警。
     *
     * <p><b>检查逻辑：</b>对于每个活跃活码，如果其下所有 {@code active} 状态的接待员
     * 的 {@code dailyCurrent / dailyMax >= 0.9}，则认为该活码处于 "全员高负载" 状态，
     * 生成一条 {@code traffic_spike} 类型的告警。</p>
     *
     * <p><b>设计意图：</b>全员高负载意味着该活码即将达到接待上限，需要运维介入，
     * 例如扩充接待员或启用更多后备人员。</p>
     */
    private void checkOverloadedQrCodes() {
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        if (activeQrs.isEmpty()) return;

        // 一次查全部 active QrAgent，按 qrCodeId 分组 — 避免 N+1
        Map<Long, List<QrAgent>> qrAgentMap = qrAgentRepo.findByStatus(QrAgent.AgentStatus.active)
            .stream()
            .collect(Collectors.groupingBy(QrAgent::getQrCodeId));

        for (QrCode qr : activeQrs) {
            List<QrAgent> receptionists = qrAgentMap.getOrDefault(qr.getId(), Collections.emptyList());
            if (receptionists.isEmpty()) continue;

            boolean allOverloaded = receptionists.stream().allMatch(a -> {
                int dailyMax = a.getDailyMax();
                if (dailyMax <= 0) {
                    log.warn("员工 {} dailyMax={} 异常，跳过负载检查", a.getAgentUserid(), dailyMax);
                    return false;
                }
                double ratio = (double) a.getDailyCurrent() / (double) dailyMax;
                return ratio >= 0.9;
            });

            if (allOverloaded) {
                alertService.createAlert(
                    receptionists.get(0).getAgentUserid(),
                    "traffic_spike",
                    AgentAlert.AlertSeverity.medium,
                    String.format("活码 %s 全员高负载(>90%%)，值守 %d 人",
                        qr.getSchoolName(), receptionists.size()),
                    AgentAlert.AutoAction.none,
                    qr.getId());
            }
        }
    }

    /**
     * 统计今日产生的告警数量（预留扩展点）。
     *
     * <p>当前为占位方法，统计逻辑等待 {@code DashboardService} 实现。
     * 未来可用于记录今天的告警摘要至日报或发送通知。</p>
     */
    private void countTodayAlerts() {
        // 统计逻辑在 DashboardService 中等候实现
    }

    /**
     * 每分钟第 30 秒执行一次 PEL 崩溃回收巡检。
     *
     * <p>扫描三个 Stream 的 Pending Entries List，回收 idle 超过 30 秒的孤消息。
     * 这些消息意味着原来的消费者（Worker 线程）在 READ 和 ACK 之间崩溃
     * （JVM crash / kill -9），需要重新入队或移入死信队列。</p>
     *
     * <p><b>与正常重试的关系：</b>正常 Worker 处理失败时会走
     * {@link MessageGuardService#markRetryOrDead} 主动重试或移入 DLQ，
     * 不会让消息留在 PEL。PEL 里有消息一定是意外崩溃。</p>
     */
    @Scheduled(cron = "30 */1 * * * *")
    public void recoverOrphanedPending() {
        log.debug("PEL 崩溃回收巡检开始");
        long idleMs = 120_000; // 给滚动重启留足缓冲，避免 PEL 误回收

        try {
            int cb = messageGuardService.recoverOrphanedPending(
                RedisConfig.CALLBACK_STREAM_KEY,
                RedisConfig.CALLBACK_CONSUMER_GROUP,
                "callback-recovery", idleMs);

            int tag = messageGuardService.recoverOrphanedPending(
                RedisConfig.TAG_STREAM_KEY,
                RedisConfig.TAG_CONSUMER_GROUP,
                "tag-recovery", idleMs);

            int df = messageGuardService.recoverOrphanedPending(
                RedisConfig.DATAFILL_STREAM_KEY,
                RedisConfig.DATAFILL_CONSUMER_GROUP,
                "datafill-recovery", idleMs);

            if (cb + tag + df > 0) {
                log.warn("PEL 崩溃回收完成: callback={}, tag={}, datafill={}", cb, tag, df);
            }
        } catch (Exception e) {
            log.error("PEL 崩溃回收异常", e);
        }
    }
}
