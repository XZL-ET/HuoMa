package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.repository.AgentAlertRepository;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.DailyReportRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AgentDailyCountService;
import com.bookstore.qrcode.service.AgentRotationService;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DailyResetWorker#doResetAndReport()} 告警自动关闭单元测试。
 *
 * <p>验证「本轮实际执行且全部步骤成功」才关闭 {@code daily_reset_failure}：
 * 任一步骤失败或关闭本身异常都不应影响主流程，且告警保持 open。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyResetWorker 每日重置失败告警自动关闭")
class DailyResetWorkerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private DailyReportRepository dailyReportRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private AgentAlertRepository alertRepo;
    @Mock private CustomerTransferRepository transferRepo;
    @Mock private QrCodeService qrCodeService;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private AgentDailyCountService countService;
    @Mock private AgentRotationService rotationService;
    @Mock private AlertService alertService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ScheduledJobGuard jobGuard;
    @Mock private Cursor<String> cursor;

    @InjectMocks
    private DailyResetWorker worker;

    @BeforeEach
    void setUp() {
        // self 通过 @Lazy 自注入，@InjectMocks 不会赋值；指向自身即可驱动内部子步骤
        ReflectionTestUtils.setField(worker, "self", worker);
    }

    @Test
    @DisplayName("重置全部成功 → 关闭 daily_reset_failure")
    void shouldResolveWhenNoFailures() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        worker.doResetAndReport();

        verify(alertService).resolveOpenByType("daily_reset_failure");
    }

    @Test
    @DisplayName("任一失败 → 不关闭")
    void shouldNotResolveWhenAnyFailure() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).scan(any(ScanOptions.class));

        worker.doResetAndReport();

        verify(alertService, never()).resolveOpenByType("daily_reset_failure");
    }

    @Test
    @DisplayName("关闭告警抛异常 → 不影响主流程")
    void shouldSwallowResolveFailure() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        when(alertService.resolveOpenByType("daily_reset_failure"))
            .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> worker.doResetAndReport()).doesNotThrowAnyException();
    }
}
