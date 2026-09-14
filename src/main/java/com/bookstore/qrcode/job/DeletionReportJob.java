package com.bookstore.qrcode.job;

import com.bookstore.qrcode.service.DeletionReportService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;

/**
 * 客户删除员工每日日报定时任务。
 * <p>
 * 推送时间可在「系统配置」页动态设置（HH:mm），保存后即时生效，无需重启。
 * 任务本体通过 {@link DeletionReportService#reportWithLock} 执行，与手动推送共用同一把分布式锁，
 * 避免重复推送。统计时区以 {@link DeletionReportService#REPORT_ZONE} 为准。</p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletionReportJob {

    private final DeletionReportService deletionReportService;
    private final TaskScheduler taskScheduler;

    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;

    /**
     * 应用启动时按配置的推送时间排程。
     * <p>读取配置异常时回退默认时间，避免 DB 抖动阻断应用启动。</p>
     */
    @PostConstruct
    public void init() {
        LocalTime time;
        try {
            time = deletionReportService.resolvePushTime();
        } catch (Exception e) {
            log.error("读取客户删除员工日报推送时间失败，回退默认 {}", DeletionReportService.DEFAULT_PUSH_TIME, e);
            time = DeletionReportService.DEFAULT_PUSH_TIME;
        }
        reschedule(time);
    }

    /**
     * 取消旧任务并按给定时间重新排程（每天 HH:mm 触发一次）。
     */
    public void reschedule(LocalTime time) {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
            scheduledFuture = taskScheduler.schedule(
                    this::runDailyDeletionReport,
                    new CronTrigger(buildCron(time),
                            TimeZone.getTimeZone(DeletionReportService.REPORT_ZONE)));
            log.info("客户删除员工日报推送时间已设为 {}", time);
        }
    }

    private String buildCron(LocalTime time) {
        return String.format(Locale.ROOT, "0 %d %d * * *", time.getMinute(), time.getHour());
    }

    public void runDailyDeletionReport() {
        try {
            deletionReportService.reportWithLock(
                    LocalDate.now(DeletionReportService.REPORT_ZONE).minusDays(1));
        } catch (Exception e) {
            log.error("客户删除员工日报定时任务异常", e);
        }
    }
}
