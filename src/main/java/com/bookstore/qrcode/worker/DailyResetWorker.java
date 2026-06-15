package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 每日凌晨 00:00 定时任务 —— 执行三步重置流程。
 *
 * <p><b>调度说明：</b>使用 Spring {@code @Scheduled(cron = "0 0 0 * * *")}，
 * 在每天午夜 00:00:00 准时触发一次。</p>
 *
 * <p><b>职责与执行步骤：</b>
 * <ol>
 *   <li><b>清零 Redis 每日计数</b> —— 删除所有 {@code agent:daily:*} 的 Key；</li>
 *   <li><b>全局池日重置</b> —— 将所有 full 状态员工恢复为 standby，清零日计数；</li>
 *   <li><b>恢复 full 状态员工</b> —— 将 QrAgent 中 full 状态的员工恢复为 active，重新同步到企微；</li>
 *   <li><b>生成昨日日报</b> —— 统计昨日数据写入 {@link DailyReport} 持久化。</li>
 * </ol>
 * </p>
 *
 * <p><b>异常说明：</b>三步各自 try-catch，任一步失败不会阻断后续步骤，保证每日重置的鲁棒性。</p>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyResetWorker {

    private final StringRedisTemplate redisTemplate;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final DailyReportRepository dailyReportRepo;
    private final CustomerRepository customerRepo;
    private final AgentAlertRepository alertRepo;
    private final CustomerTransferRepository transferRepo;
    private final QrCodeService qrCodeService;
    private final GlobalAgentPoolService poolService;
    private final com.bookstore.qrcode.service.AgentBindService agentBindService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    /** 自身代理引用 — 通过 @Lazy 延迟注入，确保 @Transactional 走 AOP 代理 */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private DailyResetWorker self;

    /**
     * 每日 00:00 定时触发的主入口方法。
     *
     * <p>按顺序执行清零、恢复和日报生成三步。整体在一个 {@code @Transactional} 事务中，
     * 但子步骤内部的异常会被各自捕获并记录日志，不会导致事务整体回滚。</p>
     *
     * @see #clearRedisDailyCounters()
     * @see #recoverFullAgents()
     * @see #generateDailyReport(LocalDate, LocalDate)
     */
    @Scheduled(cron = "0 0 0 * * *")
    // 不在此层加 @Transactional：各子步骤独立事务，任一步失败不波及他步。
    // 步骤 1 操作 Redis（非事务资源），若被卷入 DB 事务回滚会连带回滚
    // 步骤 2-3 的 DB 恢复逻辑，导致次日全员仍处于满员状态。
    public void resetAndReport() {
        log.info("===== 每日重置 + 日报生成开始 =====");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        int failures = 0;
        StringBuilder failDetails = new StringBuilder();

        // 1. 清零 Redis 每日计数（非事务资源，无 @Transactional）
        try {
            clearRedisDailyCounters();
        } catch (Exception e) {
            failures++;
            failDetails.append("Redis日计数清零失败; ");
            log.error("清空日计数失败", e);
        }

        // 2. 全局池日重置：full → standby（poolService.dailyReset 自带 @Transactional）
        try {
            poolService.dailyReset();
        } catch (Exception e) {
            failures++;
            failDetails.append("全局池日重置失败; ");
        }

        // 3. 恢复 full 状态的员工（通过代理调用确保 @Transactional 生效）
        try {
            self.recoverFullAgents();
        } catch (Exception e) {
            failures++;
            failDetails.append("full员工恢复失败; ");
        }

        // 4. 生成昨日日报（通过代理调用确保 @Transactional 生效）
        try {
            self.generateDailyReport(yesterday, today);
        } catch (Exception e) {
            failures++;
            failDetails.append("日报生成失败; ");
        }

        // 任一步骤失败均告警
        if (failures > 0) {
            String msg = String.format("每日重置 %d 项失败: %s", failures, failDetails);
            log.error(msg);
            try {
                alertService.createAlert("system", "daily_reset_failure",
                    AgentAlert.AlertSeverity.high, msg, AgentAlert.AutoAction.none, null);
            } catch (Exception e) {
                log.error("告警发送失败", e);
            }
        }

        log.info("===== 每日重置 + 日报生成完成 (失败={}) =====", failures);
    }

    /**
     * 使用 SCAN 命令清空 Redis 中所有 {@code agent:daily:*} 的 Key。
     *
     * <p>使用非阻塞 SCAN 替代 KEYS，避免 1800+ 员工规模的 O(N) 阻塞
     * 影响 Redis 主线程中正在处理的回调/打标/去重操作。
     * 每条 SCAN 迭代返回约 100 个 Key 后休眠 1ms，确保 Redis 有足够时间
     * 处理其他命令。</p>
     */
    private void clearRedisDailyCounters() {
        String pattern = "agent:daily:*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        int total = 0;
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                redisTemplate.delete(key);
                total++;
                // 每 100 条让出 CPU/Redis 时间片
                if (total % 100 == 0) {
                    try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (total > 0) {
            log.info("已清空 {} 个日计数 key", total);
        }
    }

    /**
     * 日计数归零 + 恢复 {@code full} 员工为 {@code active}，并重新同步活码到企微。
     *
     * <p><b>归零范围：</b>所有 QrAgent 的 dailyCurrent 归零（不仅是 full），
     * Redis key 在每日过期，DB 持久化值也必须同步归零，否则跨日残留。</p>
     *
     * <p><b>恢复条件：</b>排除 {@code blocked（封号）} 和 {@code melted（熔断）} 的员工，
     * 只恢复正常的满负荷员工。</p>
     *
     * <p><b>注意：</b>活码同步可能因网络等原因失败，此处逐条 try-catch 避免一个活码
     * 失败影响其他活码的同步。</p>
     */
    @Transactional
    void recoverFullAgents() {
        // 1. 全部 QrAgent 日计数归零（不只是 full，active 的也要清）
        List<QrAgent> allAgents = qrAgentRepo.findAll();
        for (var qa : allAgents) {
            if (qa.getDailyCurrent() > 0) {
                qa.setDailyCurrent(0);
                qa.setLastResetAt(LocalDateTime.now());
                qrAgentRepo.save(qa);
            }
        }

        // 2. full → active
        var fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        Set<Long> affectedQrIds = new HashSet<>();
        for (var qa : fullAgents) {
            var agent = agentRepo.findById(qa.getAgentUserid()).orElse(null);
            if (agent != null
                && agent.getOverallStatus() != Agent.OverallStatus.blocked
                && agent.getOverallStatus() != Agent.OverallStatus.melted) {
                qa.setStatus(QrAgent.AgentStatus.active);
                qrAgentRepo.save(qa);
                affectedQrIds.add(qa.getQrCodeId());
            }
        }
        log.info("已归零 {} 个 QrAgent 日计数，恢复 {} 个 full 员工，涉及 {} 个活码",
            allAgents.size(), fullAgents.size(), affectedQrIds.size());

        // 事务提交后异步同步企微活码（避免长时间占用 DB 连接）
        if (!affectedQrIds.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (Long qrId : affectedQrIds) {
                            try {
                                agentBindService.syncQrCodeToWechatAsync(qrId);
                            } catch (Exception e) {
                                log.error("日重置后异步同步活码失败: qrId={}", qrId, e);
                            }
                        }
                    }
                });
        }
    }

    /**
     * 生成昨日运营日报并持久化。
     *
     * <p>统计维度包括：</p>
     * <ul>
     *   <li>新增客户数 ({@code totalAdd}) —— 昨日 {@code created_at} 范围内的客户记录；</li>
     *   <li>新增失败数 ({@code totalAddFail}) —— 暂未实现，固定为 0；</li>
     *   <li>继承发起数 ({@code totalTransfer}) 与成功数 ({@code totalTransferOk})；</li>
     *   <li>告警数 ({@code totalAlert})；</li>
     *   <li>活码活跃/满员数 ({@code activeQr} / {@code fullQr})；</li>
     *   <li>封号/熔断员工数 ({@code blockedAgent} / {@code meltedAgent})。</li>
     * </ul>
     *
     * @param yesterday 报表所属日期（昨天）
     * @param today     今天的日期，用于计算统计时间范围 [yesterday 00:00, today 00:00)
     */
    @Transactional
    void generateDailyReport(LocalDate yesterday, LocalDate today) {
        LocalDateTime start = yesterday.atStartOfDay();
        LocalDateTime end = today.atStartOfDay();

        long totalAdd = customerRepo.countByAddTimeBetween(start, end);
        long totalAddFail = 0; // 从 alert 表统计
        long totalTransfer = transferRepo.countByTransferTimeBetween(start, end);
        long totalTransferOk = transferRepo.countByStatusAndTransferTimeBetween(
            CustomerTransfer.TransferStatus.confirmed, start, end);
        long totalAlert = alertRepo.countByCreatedAtBetween(start, end);
        long activeQr = qrCodeRepo.countByStatus(QrCode.QrCodeStatus.active);
        long fullQr = qrCodeRepo.countByStatus(QrCode.QrCodeStatus.full);
        long blockedAgent = agentRepo.findByOverallStatus(Agent.OverallStatus.blocked).size();
        long meltedAgent = agentRepo.findByOverallStatus(Agent.OverallStatus.melted).size();

        DailyReport report = DailyReport.builder()
            .date(yesterday)
            .totalAdd((int) totalAdd)
            .totalAddFail((int) totalAddFail)
            .totalTransfer((int) totalTransfer)
            .totalTransferOk((int) totalTransferOk)
            .totalAlert((int) totalAlert)
            .activeQr((int) activeQr)
            .fullQr((int) fullQr)
            .blockedAgent((int) blockedAgent)
            .meltedAgent((int) meltedAgent)
            .build();
        dailyReportRepo.save(report);
        log.info("日报已生成: {}", yesterday);
    }
}
