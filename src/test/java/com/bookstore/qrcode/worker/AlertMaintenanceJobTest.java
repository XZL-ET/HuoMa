package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("告警维护任务")
class AlertMaintenanceJobTest {
    @Mock AlertService alertService;
    @Mock ScheduledJobGuard jobGuard;
    @InjectMocks AlertMaintenanceJob job;

    @Test
    @DisplayName("runOnce — 持锁时先合并再归档")
    void shouldCollapseThenArchive() {
        when(jobGuard.runExclusive(eq("lock:job:alert-maintenance"), any(), any()))
            .thenAnswer(inv -> { ((Runnable) inv.getArgument(2)).run(); return true; });
        when(alertService.collapseDuplicates()).thenReturn(3);
        when(alertService.archiveStale()).thenReturn(5);

        job.runOnce();

        InOrder order = inOrder(alertService);
        order.verify(alertService).collapseDuplicates();
        order.verify(alertService).archiveStale();
    }

    @Test
    @DisplayName("runOnce — 未持锁时不执行")
    void shouldSkipWhenLockedByOtherNode() {
        when(jobGuard.runExclusive(any(), any(), any())).thenReturn(false);
        job.runOnce();
        verifyNoInteractions(alertService);
    }
}
