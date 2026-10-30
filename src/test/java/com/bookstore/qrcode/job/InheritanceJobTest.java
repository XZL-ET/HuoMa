package com.bookstore.qrcode.job;

import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在职继承扫描「条件恢复后自动关闭告警」的行为。
 *
 * <p>纯 Mockito（不启动 Spring 上下文）：只验证
 * {@link InheritanceJob#processTransferWindow} 在「全量扫描且无缺服务老师」时关闭告警，
 * 增量扫描或扫描中途出错时保持告警不关（fail-safe）。</p>
 */
@DisplayName("在职继承告警自动关闭")
class InheritanceJobTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 29, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 29, 8, 0);

    private QrCodeRepository qrCodeRepo;
    private QrAgentRepository qrAgentRepo;
    private AlertService alertService;
    private StringRedisTemplate redisTemplate;
    private InheritanceJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        qrCodeRepo = mock(QrCodeRepository.class);
        qrAgentRepo = mock(QrAgentRepository.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        alertService = mock(AlertService.class);
        ScheduledJobGuard jobGuard = mock(ScheduledJobGuard.class);

        job = new InheritanceJob(qrCodeRepo, qrAgentRepo, customerRepo, redisTemplate,
            objectMapper, alertService, jobGuard);
    }

    @Test
    @DisplayName("夜间全量扫描无缺服务老师 → 关闭告警")
    void shouldResolveNoServiceOnFullScan() {
        when(qrCodeRepo.findByStatus(any())).thenReturn(List.of());

        job.processTransferWindow(START, END, "test", false);

        verify(alertService).resolveOpenByType("inheritance_no_service");
    }

    @Test
    @DisplayName("白天增量扫描列表为空 → 不关闭（扫描不完整，不能据此判定恢复）")
    @SuppressWarnings("unchecked")
    void shouldNotResolveOnIncrementalScan() {
        when(qrCodeRepo.findByStatus(any())).thenReturn(List.of());
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of());

        job.processTransferWindow(START, END, "test", true);

        verify(alertService, never()).resolveOpenByType(anyString());
    }

    @Test
    @DisplayName("扫描中单个活码抛异常 → 不关闭（本轮不可信）")
    void shouldNotResolveWhenScanHadError() {
        QrCode qr = QrCode.builder().id(1L).schoolId("s1").schoolName("测试学校").build();
        when(qrCodeRepo.findByStatus(any())).thenReturn(List.of(qr));
        when(qrAgentRepo.findByQrCodeId(1L)).thenThrow(new RuntimeException("db down"));

        job.processTransferWindow(START, END, "test", false);

        verify(alertService, never()).resolveOpenByType(anyString());
    }
}
