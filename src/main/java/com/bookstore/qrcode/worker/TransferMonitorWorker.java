package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 10 分钟追踪一次在职继承结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferMonitorWorker {

    private final TransferService transferService;

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

    /**
     * 每天中午 12 点清理已超时的继承记录。
     */
    @Scheduled(cron = "0 0 12 * * *")
    public void cleanupTimeout() {
        log.info("超时继承记录清理");
        // 24h 未确认的标记为 timeout
        // transferService.trackResults() 会处理 retryCount > 144 的情况
    }
}
