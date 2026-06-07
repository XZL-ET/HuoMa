package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时巡检：每 5 分钟检查异常状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatrolWorker {

    private final QrAgentRepository qrAgentRepo;
    private final QrBackupPoolRepository backupPoolRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AlertService alertService;
    private final RateLimiterService rateLimiterService;

    /**
     * 每 5 分钟巡检一次。
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void patrol() {
        log.debug("定时巡检开始");

        // 1. 检查后备池空
        checkEmptyBackupPools();

        // 2. 检查全部高负载活码
        checkOverloadedQrCodes();

        // 3. 统计今日异常
        countTodayAlerts();

        log.debug("定时巡检完成");
    }

    /**
     * 扫描所有后备池为空的活跃活码 → 告警。
     */
    private void checkEmptyBackupPools() {
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        for (QrCode qr : activeQrs) {
            long backupCount = backupPoolRepo.countByQrCodeIdAndStatus(
                qr.getId(), QrBackupPool.PoolStatus.standby);
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active).size();

            if (activeCount > 0 && backupCount == 0) {
                alertService.alertEmptyBackup(qr.getId(), qr.getSchoolName());
            }
        }
    }

    /**
     * 检查全部活跃接待员都 > 90% 日限的活码 → 告警。
     */
    private void checkOverloadedQrCodes() {
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        for (QrCode qr : activeQrs) {
            List<QrAgent> receptionists = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active);
            if (receptionists.isEmpty()) continue;

            boolean allOverloaded = receptionists.stream().allMatch(a -> {
                double ratio = (double) a.getDailyCurrent() / (double) a.getDailyMax();
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

    private void countTodayAlerts() {
        // 统计逻辑在 DashboardService 中等候实现
    }
}
