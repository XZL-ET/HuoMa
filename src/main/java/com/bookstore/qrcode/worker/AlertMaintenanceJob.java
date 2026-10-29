package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 告警维护任务 —— 每小时收敛告警噪音。
 *
 * <p>按顺序执行两步：</p>
 * <ol>
 *   <li>{@link AlertService#collapseDuplicates()} —— 合并同一条件的重复 open 告警；</li>
 *   <li>{@link AlertService#archiveStale()} —— 归档已无法再被处理的过期 open 告警。</li>
 * </ol>
 *
 * <p><b>跨机互斥：</b>生产是 ALB 后双节点 active-active，{@code @Scheduled} 会在两机各自触发。
 * 通过 {@link ScheduledJobGuard#runExclusive} 持 {@code lock:job:alert-maintenance} 才执行，
 * 避免双机同时对同一批告警做合并/归档。</p>
 *
 * <p><b>启动即跑一次：</b>滚动部署或长时间停机后，open 告警可能积压了一整轮，
 * 不必等到下一个整点 —— 监听 {@link ApplicationReadyEvent} 再跑一次。
 * 两个入口共用 {@link #runOnce()}，同样受锁保护，双机同时启动也只有一台会执行。</p>
 *
 * <p><b>异常隔离：</b>服务方法内部虽已 try-catch，但 {@code @Transactional} 的
 * 提交阶段异常（如 {@code UnexpectedRollbackException}）仍可能逃逸。这里对每一步
 * 再包一层 try-catch：一步失败只记日志、不阻断另一步，也绝不让异常逃出锁内 runnable。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertMaintenanceJob {

    static final String LOCK_KEY = "lock:job:alert-maintenance";
    static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final AlertService alertService;
    private final ScheduledJobGuard jobGuard;

    /** 每小时第 20 分钟触发。 */
    @Scheduled(cron = "0 20 * * * *")
    public void runOnce() {
        jobGuard.runExclusive(LOCK_KEY, LOCK_TTL, this::doMaintain);
    }

    /** 应用启动完成后执行一次，收敛停机期间积压的告警。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    private void doMaintain() {
        int merged = 0;
        try {
            merged = alertService.collapseDuplicates();
        } catch (Exception e) {
            log.error("告警维护: 合并重复告警失败", e);
        }

        int archived = 0;
        try {
            archived = alertService.archiveStale();
        } catch (Exception e) {
            log.error("告警维护: 归档过期告警失败", e);
        }

        log.info("告警维护: 合并 {} 条、归档 {} 条", merged, archived);
    }
}
