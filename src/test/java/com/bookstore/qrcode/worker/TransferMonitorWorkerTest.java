package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import com.bookstore.qrcode.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信队列（DLQ）积压告警的自动关闭。
 *
 * <p>核心约束：{@link MessageGuardService#dlqSize()} 读取失败时返回 {@code -1}，
 * 此时不能把它当成「已清空」而关闭告警 —— Redis 故障恰恰是 DLQ 最可能积压的时候。</p>
 */
@DisplayName("DLQ 积压告警自动关闭")
class TransferMonitorWorkerTest {

    private MessageGuardService messageGuardService;
    private AlertService alertService;
    private ScheduledJobGuard jobGuard;
    private TransferMonitorWorker worker;

    @BeforeEach
    void setUp() {
        TransferService transferService = mock(TransferService.class);
        messageGuardService = mock(MessageGuardService.class);
        alertService = mock(AlertService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        jobGuard = mock(ScheduledJobGuard.class);
        // 锁：默认放行，直接执行任务体
        when(jobGuard.runExclusive(anyString(), any(Duration.class), any(Runnable.class)))
            .thenAnswer(inv -> {
                inv.getArgument(2, Runnable.class).run();
                return true;
            });
        worker = new TransferMonitorWorker(transferService, messageGuardService, alertService,
            redisTemplate, jobGuard);
    }

    @Test
    @DisplayName("DLQ 长度读取失败（-1）→ 不关闭告警、不新建告警")
    void shouldNotResolveWhenDlqSizeUnreadable() {
        when(messageGuardService.dlqSize()).thenReturn(-1L);

        worker.checkDlq();

        verify(alertService, never()).resolveOpenByType(anyString());
        verify(alertService, never()).createAlert(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("DLQ 已清空（0 条）→ 关闭历史积压告警")
    void shouldResolveWhenDlqEmpty() {
        when(messageGuardService.dlqSize()).thenReturn(0L);

        worker.checkDlq();

        verify(alertService).resolveOpenByType("transfer_dlq_backlog");
    }

    @Test
    @DisplayName("DLQ 仍有积压（5 条）→ 不关闭告警，按原逻辑新建告警")
    void shouldNotResolveWhenDlqBacklogged() {
        when(messageGuardService.dlqSize()).thenReturn(5L);
        when(jobGuard.firstWithin(anyString(), any(Duration.class))).thenReturn(true);

        worker.checkDlq();

        verify(alertService, never()).resolveOpenByType(anyString());
        verify(alertService).createAlert(any(), any(), any(), any(), any(), any());
    }
}
