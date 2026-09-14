package com.bookstore.qrcode.job;

import com.bookstore.qrcode.service.DeletionReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.LocalTime;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeletionReportJob 动态调度")
class DeletionReportJobTest {

    @Mock private DeletionReportService deletionReportService;
    @Mock private TaskScheduler taskScheduler;
    @Mock private ScheduledFuture<?> future;

    @InjectMocks private DeletionReportJob job;

    @Test
    @DisplayName("init — 按解析出的时间排程")
    void initSchedulesWithResolvedTime() {
        when(deletionReportService.resolvePushTime()).thenReturn(LocalTime.of(9, 0));
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        job.init();

        ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getValue().getExpression()).isEqualTo("0 0 9 * * *");
    }

    @Test
    @DisplayName("init — 读取时间异常时回退默认 09:00 排程")
    void initFallsBackToDefaultWhenResolveThrows() {
        when(deletionReportService.resolvePushTime())
                .thenThrow(new RuntimeException("db down"));
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        job.init();

        ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getValue().getExpression()).isEqualTo("0 0 9 * * *");
    }

    @Test
    @DisplayName("reschedule — 取消旧任务并按新时间排程")
    void rescheduleCancelsAndSchedulesNew() {
        when(deletionReportService.resolvePushTime()).thenReturn(LocalTime.of(9, 0));
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        job.init();
        job.reschedule(LocalTime.of(18, 30));

        verify(future).cancel(false);
        ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getAllValues().get(1).getExpression()).isEqualTo("0 30 18 * * *");
    }
}
