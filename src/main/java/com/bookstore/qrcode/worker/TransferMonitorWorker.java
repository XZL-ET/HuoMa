package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 在职继承追踪与重试定时任务。
 *
 * <p><b>职责说明：</b>管理客户在职继承（客户分配/转接）的后置处理流程，
 * 包含四项定时任务：</p>
 * <ol>
 *   <li><b>继承结果追踪</b>（每 30 分钟） —— 定时调用
 *       {@link TransferService#trackResults()}，查询企微 API 获取转移结果；</li>
 *   <li><b>API 失败重试</b>（每 30 分钟） —— 重试因网络/API 错误而失败的转移请求；</li>
 *   <li><b>欢迎语补发</b>（每 30 分钟） —— 补发之前发送失败的交接欢迎语（24h 窗口）；</li>
 *   <li><b>死信队列检查</b>（每 15 分钟） —— 检查 DLQ 积压并输出告警日志。</li>
 * </ol>
 *
 * <p>超时标记（超过 24h → timeout）和重试耗尽（≥48 次 → retry_limit）在
 * {@link TransferService#trackResults()} 中内联处理，无单独的清理任务。</p>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferMonitorWorker {

    private final TransferService transferService;
    private final MessageGuardService messageGuardService;
    private final AlertService alertService;
    private final StringRedisTemplate redisTemplate;

    /** Redis 分布式锁 key — 防止多实例同时执行 trackResults */
    private static final String TRACK_RESULTS_LOCK_KEY = "lock:transfer:track-results";
    private static final Duration TRACK_RESULTS_LOCK_TTL = Duration.ofMinutes(5);
    /** Redis 分布式锁 key — 防止多实例同时执行 retryFailed */
    private static final String RETRY_FAILED_LOCK_KEY = "lock:transfer:retry-failed";
    private static final String RETRY_GREETINGS_LOCK_KEY = "lock:transfer:retry-greetings";
    private static final Duration RETRY_LOCK_TTL = Duration.ofMinutes(5);

    /** DLQ 告警限流：每小时最多告警一次。static 避免 CGLIB 代理实例字段分裂导致限流失效 */
    private static volatile long lastDlqAlertTime = 0L;
    private static volatile int skippedDlqAlertCount = 0;

    /**
     * 每 30 分钟执行一次，追踪在职继承的确认结果。
     *
     * <p>调用 {@link TransferService#trackResults()} 查询企业微信接口，
     * 检查之前发起的继承请求是否已被客户确认或已超时，更新数据库中
     * {@link com.bookstore.qrcode.entity.CustomerTransfer} 的状态。
     * 同时检查重试耗尽记录并标记为 retry_limit。
     * 异常会被捕获并记录，不会影响下一次调度执行。</p>
     *
     * <p>使用 Redis 分布式锁防止多实例并发执行导致重复处理。</p>
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void monitor() {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(TRACK_RESULTS_LOCK_KEY, lockValue, TRACK_RESULTS_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("继承结果追踪被其他实例执行中，跳过");
            return;
        }
        try {
            log.debug("继承结果追踪开始");
            List<Long> newlyConfirmed = transferService.trackResults();
            log.debug("继承结果追踪完成");
            // 在事务外发送欢迎语：避免企微 API 网络 I/O 长期持有 DB 连接
            if (!newlyConfirmed.isEmpty()) {
                if (TransferService.isWithinGreetingWindow(LocalTime.now())) {
                    transferService.sendGreetingsForNewlyConfirmed(newlyConfirmed);
                } else {
                    log.info("当前不在欢迎语发送窗口(08:30-21:00)，跳过 {} 条新确认欢迎语，留待白天补发",
                        newlyConfirmed.size());
                }
            }
        } catch (Exception e) {
            log.error("继承结果追踪异常", e);
        } finally {
            Long unlockResult = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(TRACK_RESULTS_LOCK_KEY), lockValue);
            if (unlockResult != null && unlockResult == 1) {
                log.debug("分布式锁安全释放: {}", TRACK_RESULTS_LOCK_KEY);
            }
        }
    }

    /**
     * 每 30 分钟执行一次，重试 API 调用失败的转移记录。
     *
     * <p>调用 {@link TransferService#retryFailedTransfers()} 重新发起
     * 之前因网络/限流等原因失败的 transfer_customer 调用。
     * 最多重试 3 次，达到上限后标记为 retry_limit。
     * </p>
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void retryFailed() {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(RETRY_FAILED_LOCK_KEY, lockValue, RETRY_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("api_failed 转移重试被其他实例执行中，跳过");
            return;
        }
        try {
            log.debug("api_failed 转移重试开始");
            transferService.retryFailedTransfers();
        } catch (Exception e) {
            log.error("api_failed 转移重试异常", e);
        } finally {
            Long unlockResult = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(RETRY_FAILED_LOCK_KEY), lockValue);
            if (unlockResult != null && unlockResult == 1) {
                log.debug("分布式锁安全释放: {}", RETRY_FAILED_LOCK_KEY);
            }
        }
    }

    /**
     * 每 30 分钟执行一次，补发失败的交接欢迎语。
     *
     * <p>调用 {@link TransferService#retryFailedGreetings()} 扫描最近 24 小时内
     * 已确认但欢迎语发送失败的记录并重新发送。超过 24 小时的记录不再重试。</p>
     *
     * <p><b>时间窗口：</b>仅 08:30–21:00 内执行补发，避免深夜/凌晨打扰客户。</p>
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void retryGreetings() {
        // 时间窗口检查：仅 08:30–21:00 补发，避免深夜打扰
        if (!TransferService.isWithinGreetingWindow(LocalTime.now())) {
            return;
        }
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(RETRY_GREETINGS_LOCK_KEY, lockValue, RETRY_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("欢迎语补发被其他实例执行中，跳过");
            return;
        }
        try {
            log.debug("欢迎语补发检查开始");
            transferService.retryFailedGreetings();
        } catch (Exception e) {
            log.error("欢迎语补发异常", e);
        } finally {
            Long unlockResult = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(RETRY_GREETINGS_LOCK_KEY), lockValue);
            if (unlockResult != null && unlockResult == 1) {
                log.debug("分布式锁安全释放: {}", RETRY_GREETINGS_LOCK_KEY);
            }
        }
    }

    /**
     * 每 15 分钟检查一次死信队列，若有积压则发出告警。
     *
     * <p>注意 {@link MessageGuardService#dlqSize()} 读的是**所有 Stream 共用**的那条 DLQ，
     * 不限于在职继承（消息各自的来源记在 {@code _dlq_origin_stream} 字段里）。
     * 消息经多次退避重试后依然失败才会进 DLQ，因此积压通常意味着企微 API 持续不可用
     * 或存在系统性错误。告警限流：每小时最多发送一次，避免告警风暴。</p>
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void checkDlq() {
        long dlqLen = messageGuardService.dlqSize();
        if (dlqLen > 0) {
            log.warn("⚠ 死信队列积压: {} 条 — 建议检查并重放", dlqLen);

            long now = System.currentTimeMillis();
            if (now - lastDlqAlertTime > 3600_000L) {
                String suffix = skippedDlqAlertCount > 0
                    ? String.format("（过去 1 小时内累计触发 %d 次）", skippedDlqAlertCount)
                    : "";
                lastDlqAlertTime = now;
                skippedDlqAlertCount = 0;
                alertService.createAlert(null, "transfer_dlq_backlog",
                    AgentAlert.AlertSeverity.high,
                    String.format("死信队列积压 %d 条，消息经重试后仍失败，请检查企微 API 状态或手动重放%s",
                        dlqLen, suffix),
                    AgentAlert.AutoAction.none, null);
            } else {
                skippedDlqAlertCount++;
                log.warn("DLQ 积压（告警限流，第{}次）: {} 条",
                    skippedDlqAlertCount, dlqLen);
            }
        }
    }
}
