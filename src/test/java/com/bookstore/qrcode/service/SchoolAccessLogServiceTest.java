package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * SchoolAccessLogService 单元测试。
 * <p>验证异步日志方法正确提取请求属性并构造日志实体。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchoolAccessLogService 日志记录")
class SchoolAccessLogServiceTest {

    @Mock
    private QrAccessLogRepository logRepository;

    @InjectMocks
    private SchoolAccessLogService logService;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        request.addHeader("User-Agent", "Mozilla/5.0 TestAgent");
    }

    @Test
    @DisplayName("logView 应构造 view action 的日志")
    void logViewShouldCreateViewLog() {
        logService.logView(42L, request);

        ArgumentCaptor<QrAccessLog> captor = ArgumentCaptor.forClass(QrAccessLog.class);
        verify(logRepository).save(captor.capture());

        QrAccessLog log = captor.getValue();
        assertThat(log.getQrCodeId()).isEqualTo(42L);
        assertThat(log.getAction()).isEqualTo(QrAccessLog.Action.view);
        assertThat(log.getChannel()).isEqualTo(QrAccessLog.Channel.school);
        assertThat(log.getIpAddress()).isEqualTo("192.168.1.100");
        assertThat(log.getUserIdentity()).isEqualTo("192.168.1.100");
        assertThat(log.getUserAgent()).isEqualTo("Mozilla/5.0 TestAgent");
    }

    @Test
    @DisplayName("logDownload 应构造 download action 的日志")
    void logDownloadShouldCreateDownloadLog() {
        logService.logDownload(99L, request);

        ArgumentCaptor<QrAccessLog> captor = ArgumentCaptor.forClass(QrAccessLog.class);
        verify(logRepository).save(captor.capture());

        QrAccessLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo(QrAccessLog.Action.download);
        assertThat(log.getQrCodeId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("logGlobalContactView 应构造无 qrCodeId 的日志")
    void logGlobalContactViewShouldCreateLogWithoutQrCodeId() {
        logService.logGlobalContactView(request);

        ArgumentCaptor<QrAccessLog> captor = ArgumentCaptor.forClass(QrAccessLog.class);
        verify(logRepository).save(captor.capture());

        QrAccessLog log = captor.getValue();
        assertThat(log.getQrCodeId()).isNull();
        assertThat(log.getAction()).isEqualTo(QrAccessLog.Action.view);
    }

    @Test
    @DisplayName("应截断超长 User-Agent")
    void shouldTruncateLongUserAgent() {
        StringBuilder longUA = new StringBuilder();
        for (int i = 0; i < 600; i++) longUA.append("x");
        request.removeHeader("User-Agent");
        request.addHeader("User-Agent", longUA.toString());

        logService.logView(1L, request);

        ArgumentCaptor<QrAccessLog> captor = ArgumentCaptor.forClass(QrAccessLog.class);
        verify(logRepository).save(captor.capture());

        QrAccessLog log = captor.getValue();
        assertThat(log.getUserAgent()).hasSize(512);
    }
}
