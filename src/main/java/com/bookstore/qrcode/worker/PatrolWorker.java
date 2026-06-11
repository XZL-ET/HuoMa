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
 * 定时巡检任务 —— 每 5 分钟扫描全系统异常状态。
 *
 * <p><b>调度说明：</b>使用 Spring {@code @Scheduled(cron = "0 */5 * * * *")}，
 * 每 5 分钟（整 5 分倍数触发）执行一次巡检。</p>
 *
 * <p><b>巡检项目（共 3 项）：</b>
 * <ol>
 *   <li><b>检查空后备池</b> —— 遍历所有活跃活码，如果该活码有活跃接待员但后备池为空，
 *       则发送告警，提醒运维补充后备人员；</li>
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
    private final QrBackupPoolRepository backupPoolRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AlertService alertService;
    private final RateLimiterService rateLimiterService;

    /**
     * 每 5 分钟执行一次的主巡检入口。
     *
     * <p>依次执行三项检查：空后备池、高负载活码、今日告警统计。</p>
     *
     * @see #checkEmptyBackupPools()
     * @see #checkOverloadedQrCodes()
     * @see #countTodayAlerts()
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
     * 扫描所有活跃活码，检查后备接待员池是否为空。
     *
     * <p><b>检查逻辑：</b>对于每个活跃活码，同时查询其后备池中待命
     * ({@code standby}) 的人数，以及该活码下活跃接待员 ({@code active}) 的数量。
     * 当 {@code activeCount > 0 && backupCount == 0} 时，说明有接待员在岗
     * 但无后备人员可用，触发 {@link com.bookstore.qrcode.service.AlertService#alertEmptyBackup}
     * 告警。</p>
     *
     * <p><b>设计意图：</b>当所有后备均已用尽时，运维需要及时补充后备人员，
     * 避免出现接待员异常后无人补位的情况。</p>
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

    /**
     * 统计今日产生的告警数量（预留扩展点）。
     *
     * <p>当前为占位方法，统计逻辑等待 {@code DashboardService} 实现。
     * 未来可用于记录今天的告警摘要至日报或发送通知。</p>
     */
    private void countTodayAlerts() {
        // 统计逻辑在 DashboardService 中等候实现
    }
}
