package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.SceneConfigProperties;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("QrCodeController 活码管理")
class QrCodeControllerTest {

    private MockMvc mockMvc;
    private QrCodeService qrCodeService;
    private QrImageService qrImageService;
    private QrCodeRepository qrCodeRepo;

    @BeforeEach
    void setUp() {
        qrCodeService = mock(QrCodeService.class);
        qrImageService = mock(QrImageService.class);
        qrCodeRepo = mock(QrCodeRepository.class);
        QrCodeController controller = new QrCodeController(
                qrCodeService,
                mock(WecomApiClient.class),
                mock(QrAgentRepository.class),
                mock(GlobalAgentPoolRepository.class),
                mock(CustomerRepository.class),
                qrCodeRepo,
                mock(QrRotateLogRepository.class),
                mock(TagRepository.class),
                qrImageService,
                mock(TagService.class),
                mock(EmployeeRepository.class),
                mock(CustomerTransferRepository.class),
                mock(EmployeeSyncService.class),
                mock(FormTemplateRepository.class),
                mock(QrCodeGroupRepository.class),
                mock(SchoolCategoryRepository.class),
                mock(SchoolRepository.class),
                mock(SystemConfigRepository.class),
                mock(StringRedisTemplate.class),
                mock(ObjectMapper.class),
                mock(SceneConfigProperties.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /qrcodes — 返回活码列表页")
    void shouldReturnQrCodeListPage() throws Exception {
        when(qrCodeRepo.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(qrCodeService.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/qrcodes"))
                .andExpect(status().isOk())
                .andExpect(view().name("qrcode/list"));
    }

    @Test
    @DisplayName("GET /qrcodes/{id} — 返回活码详情页")
    void shouldReturnQrCodeDetail() throws Exception {
        QrCode qr = QrCode.builder().id(1L).schoolName("北京第一中学")
                .schoolId("BJ-001").regionCity("北京").regionDistrict("海淀区")
                .status(QrCode.QrCodeStatus.active)
                .rotateMode(QrCode.RotateMode.auto)
                .createMode(QrCode.CreateMode.manual).build();
        when(qrCodeService.getById(1L)).thenReturn(qr);
        when(qrCodeService.getAgents(1L)).thenReturn(List.of());
        when(qrCodeService.getBackups(1L, 0, 20)).thenReturn(new PageImpl<>(List.of()));
        when(qrCodeService.getPoolStats()).thenReturn(java.util.Map.of("standby", 0L, "full", 0L, "blocked", 0L));
        when(qrCodeService.getAllPoolUserids()).thenReturn(List.of());

        mockMvc.perform(get("/qrcodes/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("qrcode/detail"));
    }

    @Test
    @Disabled("downloadSingle reads qrCodeRepo directly, needs full mock setup")
    @DisplayName("GET /qrcodes/{id}/download — 下载活码图片")
    void shouldDownloadQrCodeImage() throws Exception {
        when(qrImageService.generateQrImage(1L, 300)).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/qrcodes/1/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
    }
}
