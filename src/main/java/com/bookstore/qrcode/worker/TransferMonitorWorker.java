package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    private final StringRedisTemplate redisTemplate;

    /** Redis 分布式锁 key — 防止多实例同时执行 trackResults */
    private static final String TRACK_RESULTS_LOCK_KEY = "lock:transfer:track-results";
    private static final Duration TRACK_RESULTS_LOCK_TTL = Duration.ofMinutes(5);

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
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(TRACK_RESULTS_LOCK_KEY, "1", TRACK_RESULTS_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("继承结果追踪被其他实例执行中，跳过");
            return;
        }
        try {
            log.debug("继承结果追踪开始");
            transferService.trackResults();
            log.debug("继承结果追踪完成");
        } catch (Exception e) {
            log.error("继承结果追踪异常", e);
        } finally {
            redisTemplate.delete(TRACK_RESULTS_LOCK_KEY);
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
        log.debug("api_failed 转移重试开始");
        try {
            transferService.retryFailedTransfers();
        } catch (Exception e) {
            log.error("api_failed 转移重试异常", e);
        }
    }

    /**
     * 每 30 分钟执行一次，补发失败的交接欢迎语。
     *
     * <p>调用 {@link TransferService#retryFailedGreetings()} 扫描最近 24 小时内
     * 已确认但欢迎语发送失败的记录并重新发送。超过 24 小时的记录不再重试。</p>
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void retryGreetings() {
        log.debug("欢迎语补发检查开始");
        try {
            transferService.retryFailedGreetings();
        } catch (Exception e) {
            log.error("欢迎语补发异常", e);
        }
    }

    /**
     * 每 15 分钟检查一次死信队列，若有积压则输出告警日志。
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void checkDlq() {
        long dlqLen = messageGuardService.dlqSize();
        if (dlqLen > 0) {
            log.warn("⚠ 死信队列积压: {} 条 — 建议检查并重放", dlqLen);
        }
    }
}
