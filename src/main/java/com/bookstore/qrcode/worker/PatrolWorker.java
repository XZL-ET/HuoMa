package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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

        // 1. 检查全局池余量
        checkGlobalPoolLow();

        // 2. 检查全部高负载活码
        checkOverloadedQrCodes();

        // 3. 统计今日异常
        countTodayAlerts();

        // 4. 检查死信队列积压
        try {
            long dlq = messageGuardService.dlqSize();
            if (dlq > 0) {
                log.warn("死信队列积压: {} 条", dlq);
            }
        } catch (Exception e) {
            log.debug("DLQ 积压检查跳过: {}", e.getMessage());
        }

        log.debug("定时巡检完成");
    }

    /**
     * 检查全局员工池 standby 余量并发送告警。
     *
     * <p><b>告警规则：</b>
     * <ul>
     *   <li>standby = 0：全局池完全枯竭，任何活码都无法扩容</li>
     *   <li>standby < 5：全局池严重不足，需及时补充</li>
     * </ul>
     *
     * <p>与旧逻辑不同，全局池是所有活码共享的，无需按活码逐条检查。</p>
     */
    private void checkGlobalPoolLow() {
        long standbyCount = poolRepo.countByStatus(
            GlobalAgentPool.PoolStatus.standby);
        if (standbyCount == 0) {
            alertService.alertEmptyBackup(null, "全局后备池完全枯竭！");
        } else if (standbyCount < 5) {
            alertService.alertEmptyBackup(null,
                "全局后备池严重不足: 仅剩 " + standbyCount + " 人");
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
        long idleMs = 30_000;

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
