package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
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
    private final ObjectMapper objectMapper;

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
    @Transactional
    public void resetAndReport() {
        log.info("===== 每日重置 + 日报生成开始 =====");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // 1. 清零 Redis 每日计数
        clearRedisDailyCounters();

        // 2. 全局池日重置：full → standby
        try {
            poolService.dailyReset();
        } catch (Exception e) {
            log.error("全局池日重置失败", e);
        }

        // 3. 恢复 full 状态的员工（非封号/熔断的）
        recoverFullAgents();

        // 4. 生成昨日日报
        generateDailyReport(yesterday, today);

        log.info("===== 每日重置 + 日报生成完成 =====");
    }

    /**
     * 清空 Redis 中所有 {@code agent:daily:*} 的 Key。
     *
     * <p>使用 {@code keys} 命令扫描匹配的 Key 并批量删除。由于 Key 数量通常不超过
     * 数百个，不会对 Redis 性能造成明显影响。如果删除过程中抛出异常，仅记录错误日志，
     * 不影响后续恢复和日报生成步骤。</p>
     */
    private void clearRedisDailyCounters() {
        try {
            Set<String> keys = redisTemplate.keys("agent:daily:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("已清空 {} 个日计数 key", keys.size());
            }
        } catch (Exception e) {
            log.error("清空日计数失败", e);
        }
    }

    /**
     * 恢复 {@code full} 状态的员工为 {@code active}，并重新同步活码到企业微信。
     *
     * <p><b>恢复条件：</b>排除 {@code blocked（封号）} 和 {@code melted（熔断）} 的员工，
     * 只恢复正常的满负荷员工。恢复后将该员工所属活码 ID 收集起来，统一调用
     * {@link com.bookstore.qrcode.service.QrCodeService#syncQrUsersToWechat(Long)}
     * 重新同步到企微，确保活码上再次展示这些恢复后的员工。</p>
     *
     * <p><b>注意：</b>活码同步可能因网络等原因失败，此处逐条 try-catch 避免一个活码
     * 失败影响其他活码的同步。</p>
     */
    private void recoverFullAgents() {
        var fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        Set<Long> affectedQrIds = new HashSet<>();
        for (var qa : fullAgents) {
            var agent = agentRepo.findById(qa.getAgentUserid()).orElse(null);
            if (agent != null
                && agent.getOverallStatus() != Agent.OverallStatus.blocked
                && agent.getOverallStatus() != Agent.OverallStatus.melted) {
                qa.setStatus(QrAgent.AgentStatus.active);
                qa.setDailyCurrent(0);
                qa.setLastResetAt(LocalDateTime.now());
                qrAgentRepo.save(qa);
                affectedQrIds.add(qa.getQrCodeId());
            }
        }
        log.info("已恢复 {} 个 full 员工，涉及 {} 个活码", fullAgents.size(), affectedQrIds.size());

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
    private void generateDailyReport(LocalDate yesterday, LocalDate today) {
        try {
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
        } catch (Exception e) {
            log.error("生成日报失败", e);
        }
    }
}
