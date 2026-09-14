package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WechatSyncHealingService 自愈")
class WechatSyncHealingServiceTest {

    @Mock private WecomApiClient wecomApi;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private AlertService alertService;
    @Mock private ServiceTeacherDailyMaxService serviceTeacherDailyMaxService;

    @InjectMocks
    private WechatSyncHealingService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // self-injection（@Lazy @Autowired 不会被 @InjectMocks 注入）
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    @DisplayName("同步遇到 60111（userid 不存在）时自愈移除该成员")
    void shouldHealUseridNotFound() throws Exception {
        QrCode qr = new QrCode();
        qr.setId(1L);
        qr.setQrConfigId("cfg1");
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        // 初始全量同步抛 60111
        when(wecomApi.updateContactWay("cfg1", List.of("good", "bad")))
                .thenThrow(new WecomApiException(60111, "userid not found", "{}"));
        // 二分定位：good 可用，bad 不可用
        when(wecomApi.updateContactWay("cfg1", List.of("good")))
                .thenReturn(objectMapper.readTree("{\"errcode\":0}"));
        when(wecomApi.updateContactWay("cfg1", List.of("bad")))
                .thenThrow(new WecomApiException(60111, "userid not found", "{}"));

        // failing 成员是接待员
        QrAgent failing = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("bad")
                .role(QrAgent.AgentRole.receptionist).status(QrAgent.AgentStatus.active).build();
        when(qrAgentRepo.findByQrCodeIdAndAgentUserid(1L, "bad")).thenReturn(Optional.of(failing));

        // 移除后重同步 good 成功
        when(wecomApi.getContactWay("cfg1"))
                .thenReturn(objectMapper.readTree("{\"contact_way\":{\"user\":[\"good\"]}}"));

        WechatSyncHealingService.SyncResult result =
                service.syncWithHealing(1L, List.of("good", "bad"), "qr-service");

        assertTrue(result.success);
        assertEquals(List.of("bad"), result.replacedUsers);
        assertTrue(result.needReplacement);
        assertEquals(QrAgent.AgentStatus.removed, failing.getStatus());
        verify(poolService).blockAgentForWechatIssue("bad", 60111);
        verify(qrAgentRepo).save(failing);
    }

    @Test
    @DisplayName("提拔替补 dual 时标记 fallback 并设置服务日限默认值")
    void shouldMarkFallbackAndSetServiceDailyMaxOnPromote() {
        QrAgent senior = QrAgent.builder().id(1L).qrCodeId(1L).agentUserid("senior1")
                .role(QrAgent.AgentRole.receptionist).dailyMax(150)
                .status(QrAgent.AgentStatus.active).build();
        when(serviceTeacherDailyMaxService.resolveDefault()).thenReturn(300);
        when(agentRepo.findById("senior1")).thenReturn(Optional.empty());

        service.persistServiceFallback(senior, 1L, "svc1");

        assertEquals(QrAgent.AgentRole.dual, senior.getRole());
        assertTrue(Boolean.TRUE.equals(senior.getFallback()));
        assertEquals(300, senior.getServiceDailyMax());
        verify(qrAgentRepo).save(senior);
    }

    @Test
    @DisplayName("服务老师恢复后，冗余的失联替补 dual 降回 receptionist")
    void shouldDemoteRecoveredFallbackDual() {
        QrAgent fallback = QrAgent.builder().id(1L).qrCodeId(1L).agentUserid("senior1")
                .role(QrAgent.AgentRole.dual).fallback(true).serviceDailyMax(300)
                .status(QrAgent.AgentStatus.active).build();
        QrAgent realSvc = QrAgent.builder().id(2L).qrCodeId(1L).agentUserid("svc1")
                .role(QrAgent.AgentRole.service).status(QrAgent.AgentStatus.active).build();
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(fallback, realSvc));

        service.demoteRecoveredFallbacks(1L, List.of("svc1"));

        assertEquals(QrAgent.AgentRole.receptionist, fallback.getRole());
        assertFalse(Boolean.TRUE.equals(fallback.getFallback()));
        assertNull(fallback.getServiceDailyMax());
        verify(qrAgentRepo).save(fallback);
    }

    @Test
    @DisplayName("服务老师未恢复时，替补 dual 保持不动")
    void shouldNotDemoteWhenServiceStillUnavailable() {
        QrAgent fallback = QrAgent.builder().id(1L).qrCodeId(1L).agentUserid("senior1")
                .role(QrAgent.AgentRole.dual).fallback(true).serviceDailyMax(300)
                .status(QrAgent.AgentStatus.active).build();
        // 活码上只有替补 dual，没有真实 service/dual 可用
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(fallback));

        service.demoteRecoveredFallbacks(1L, List.of("senior1"));

        assertEquals(QrAgent.AgentRole.dual, fallback.getRole());
        assertTrue(Boolean.TRUE.equals(fallback.getFallback()));
        verify(qrAgentRepo, never()).save(fallback);
    }
}
