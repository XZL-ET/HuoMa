package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在职继承追踪与清理定时任务。
 *
 * <p><b>职责说明：</b>管理客户在职继承（客户分配/转接）的后置处理流程，
 * 包含两项定时任务：</p>
 * <ol>
 *   <li><b>继承结果追踪（每 10 分钟）</b> —— 定时调用
 *       {@link TransferService#trackResults()}，检查已发起的继承请求是否
 *       已被客户确认或超时，更新继承记录状态；</li>
 *   <li><b>超时清理（每天中午 12:00）</b> —— 清理超过 24 小时仍未确认的
 *       继承记录，将其标记为 {@code timeout} 状态。</li>
 * </ol>
 *
 * <p><b>调度说明：</b>
 * <ul>
 *   <li>追踪任务：{@code cron = "0 *&#47;10 * * * *"}，每 10 分钟一次；</li>
 *   <li>清理任务：{@code cron = "0 0 12 * * *"}，每天中午 12:00 执行。</li>
 * </ul>
 * </p>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferMonitorWorker {

    private final TransferService transferService;

    /**
     * 每 10 分钟执行一次，追踪在职继承的确认结果。
     *
     * <p>调用 {@link TransferService#trackResults()} 查询企业微信接口，
     * 检查之前发起的继承请求是否已被客户确认或已超时，更新数据库中
     * {@link com.bookstore.qrcode.entity.CustomerTransfer} 的状态。
     * 异常会被捕获并记录，不会影响下一次调度执行。</p>
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void monitor() {
        log.debug("继承结果追踪开始");
        try {
            transferService.trackResults();
        } catch (Exception e) {
            log.error("继承结果追踪异常", e);
        }
        log.debug("继承结果追踪完成");
    }
}
