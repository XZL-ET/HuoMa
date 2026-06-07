package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 每日 00:00 执行：清零计数、恢复员工、生成日报。
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
    private final ObjectMapper objectMapper;

    /**
     * 每日 00:00 执行。
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void resetAndReport() {
        log.info("===== 每日重置 + 日报生成开始 =====");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // 1. 清零 Redis 每日计数
        clearRedisDailyCounters();

        // 2. 恢复 full 状态的员工（非封号/熔断的）
        recoverFullAgents();

        // 3. 生成昨日日报
        generateDailyReport(yesterday, today);

        log.info("===== 每日重置 + 日报生成完成 =====");
    }

    /**
     * 清空 Redis 中所有 agent:daily:* 的 key。
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
     * 恢复 full 状态的接待员（排除被封/熔断的）。
     */
    private void recoverFullAgents() {
        var fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        for (var qa : fullAgents) {
            var agent = agentRepo.findById(qa.getAgentUserid()).orElse(null);
            if (agent != null
                && agent.getOverallStatus() != Agent.OverallStatus.blocked
                && agent.getOverallStatus() != Agent.OverallStatus.melted) {
                qa.setStatus(QrAgent.AgentStatus.active);
                qa.setDailyCurrent(0);
                qa.setLastResetAt(LocalDateTime.now());
                qrAgentRepo.save(qa);
            }
        }
        log.info("已恢复 {} 个 full 员工", fullAgents.size());
    }

    /**
     * 生成昨日日报。
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
