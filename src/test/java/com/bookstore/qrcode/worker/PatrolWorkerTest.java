package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.OperationLogRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.EmployeeSyncService;
import com.bookstore.qrcode.service.LeakMetrics;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.service.RateLimiterService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 漏处理计数增量告警的基线语义。
 *
 * <p>{@code lastLeakCounters} 是「上次真正告警时的值」而不是「上次巡检时的值」：
 * 限流那一小时里的增量必须留到下一次告警里，否则这段时间发生的事永远不出现在任何告警中。
 * 同时计数回退（进程重启归零）必须重建基线，否则基线永远高于现值、此后彻底沉默。</p>
 */
@DisplayName("巡检的漏处理计数增量告警")
class PatrolWorkerTest {

    /** 取 LEAK_ALERT_THRESHOLDS 里阈值为 10 的计数作代表，增量好凑 */
    private static final String NAME = "tag_permanent_failure";
    private static final String ALERT_TYPE = "leak_" + NAME;
    private static final int THRESHOLD = 10;

    private AlertService alertService;
    private LeakMetrics leakMetrics;
    private PatrolWorker worker;
    private Method alertOnSilentLeaks;

    @BeforeEach
    void setUp() throws Exception {
        alertService = mock(AlertService.class);
        leakMetrics = new LeakMetrics();
        worker = buildWorker(leakMetrics);

        // 基线与限流时间戳都是 static，同 JVM 内跨用例残留，不清会让后一个用例静默不告警
        clearStatic("lastLeakCounters");
        clearStatic("lastLeakAlertAt");

        alertOnSilentLeaks = PatrolWorker.class.getDeclaredMethod("alertOnSilentLeaks");
        alertOnSilentLeaks.setAccessible(true);
    }

    @Test
    @DisplayName("限流那一小时内的新增会被算进下一次告警，而不是被基线悄悄吞掉")
    void incrementsDuringThrottledHourAreNotSwallowed() throws Exception {
        leakMetricsTagged(THRESHOLD);
        invoke();                                   // 首次巡检：只建基线
        verifyNoAlert();

        leakMetricsTagged(THRESHOLD);                // 累计 20，增量 10 → 告警，限流窗口开启
        invoke();
        verify(alertService, times(1)).createAlert(any(), eq(ALERT_TYPE),
            eq(AgentAlert.AlertSeverity.high), any(), any(), any());

        leakMetricsTagged(THRESHOLD);                // 累计 30，落在限流窗口内
        invoke();
        verify(alertService, times(1)).createAlert(any(), any(), any(), any(), any(), any());

        expireThrottle();                            // 限流窗口过去，此后没有新增
        invoke();

        // 基线若在限流期间就被推到 30，这里算出的增量是 0 —— 限流期那 10 次永久失败
        // 就再也不会出现在任何一条告警里
        verify(alertService, times(2)).createAlert(any(), eq(ALERT_TYPE),
            eq(AgentAlert.AlertSeverity.high), any(), any(), any());
    }

    @Test
    @DisplayName("计数回退（进程重启归零）时重建基线，后续新增仍能告警")
    void rebuildsBaselineAfterCounterRegression() throws Exception {
        leakMetricsTagged(100);
        invoke();

        // 进程重启：计数从 0 重新开始，低于上次的基线
        leakMetrics = new LeakMetrics();
        worker = buildWorker(leakMetrics);
        leakMetricsTagged(50);
        invoke();
        verifyNoAlert();                             // 回退不告警

        leakMetricsTagged(THRESHOLD);                // 50 → 60
        invoke();

        // 回退时若不重建基线，基线会一直停在 100，此后每条巡检都判成「计数回退」而永久沉默
        verify(alertService, times(1)).createAlert(any(), eq(ALERT_TYPE),
            eq(AgentAlert.AlertSeverity.high), any(), any(), any());
    }

    // ==================== 小工具 ====================

    private void invoke() throws Exception {
        alertOnSilentLeaks.invoke(worker);
    }

    private void leakMetricsTagged(int times) {
        for (int i = 0; i < times; i++) {
            leakMetrics.tagPermanentFailure(84061);
        }
    }

    /** 把限流时间戳拨回一小时的窗口之外，模拟「限流期已过」。 */
    @SuppressWarnings("unchecked")
    private void expireThrottle() throws Exception {
        Field f = PatrolWorker.class.getDeclaredField("lastLeakAlertAt");
        f.setAccessible(true);
        Map<String, Long> map = (Map<String, Long>) f.get(null);
        map.put(NAME, System.currentTimeMillis() - 3600_001L);
    }

    @SuppressWarnings("unchecked")
    private static void clearStatic(String fieldName) throws Exception {
        Field f = PatrolWorker.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    private void verifyNoAlert() {
        verify(alertService, never()).createAlert(any(), any(), any(), any(), any(), any());
    }

    private PatrolWorker buildWorker(LeakMetrics metrics) {
        return new PatrolWorker(
            mock(QrAgentRepository.class),
            mock(GlobalAgentPoolRepository.class),
            mock(QrCodeRepository.class),
            alertService,
            mock(RateLimiterService.class),
            mock(MessageGuardService.class),
            mock(EmployeeSyncService.class),
            mock(EmployeeRepository.class),
            mock(AgentRepository.class),
            mock(WecomApiClient.class),
            mock(OperationLogRepository.class),
            mock(CustomerTransferRepository.class),
            mock(QrCodeService.class),
            metrics);
    }
}
