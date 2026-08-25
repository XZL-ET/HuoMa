package com.bookstore.qrcode.job;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.TransferReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 每日转接对账定时任务。
 * <p>
 * 每日早 8 点执行一次，对昨日发起的在职继承转接按区县负责人汇总
 * （成功/失败/进行中），并通过企微应用消息推送给对应负责人，
 * 避免转移失败后无人知晓、客户流失。
 * </p>
 *
 * <p>使用 Redis 分布式锁防止多实例重复推送。时间口径以
 * {@code CustomerTransfer.transferTime} 为准（详见
 * {@link TransferReconciliationService}）。</p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferReconciliationJob {

    private final TransferReconciliationService reconciliationService;
    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_KEY = "lock:reconciliation:daily";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /**
     * 每日 08:00 执行对账推送，可通过 {@code app.reconciliation.cron} 覆盖。
     */
    @Scheduled(cron = "${app.reconciliation.cron:0 0 8 * * *}")
    public void runDailyReconciliation() {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(LOCK_KEY, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("每日转接对账被其他实例执行中，跳过");
            return;
        }
        try {
            reconciliationService.reconcile(LocalDate.now().minusDays(1));
        } catch (Exception e) {
            log.error("每日转接对账定时任务异常", e);
        } finally {
            Long unlock = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT, List.of(LOCK_KEY), lockValue);
            if (unlock != null && unlock == 1) {
                log.debug("分布式锁安全释放: {}", LOCK_KEY);
            }
        }
    }
}
