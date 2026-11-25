package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 严格同部门取人改造（Task 3）：两个取人调用点必须传「活码所在部门」，
 * 而不是全局取人 / 服务老师本人部门。
 *
 * <p>本测试用 mock 的 {@link GlobalAgentPoolService} 来聚焦「调用点传了什么 deptId」，
 * 严格模式本身的取人语义由 {@code GlobalAgentPoolServiceStrictTest} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("取人调用点严格同部门参数")
class StrictStandbyCallerTest {

    // ── WechatSyncHealingService 依赖 ──
    @Mock private WecomApiClient wecomApi;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private AlertService alertService;

    // ── AgentRotationService 额外依赖 ──
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private QrRotateLogRepository rotateLogRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private AgentDailyCountService dailyCountService;
    @Mock private WechatSyncHealingService syncHealingService;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private WechatSyncHealingService healingServiceUnderTest;

    @InjectMocks
    private AgentRotationService rotationService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // self-injection（@Lazy @Autowired 不会被 @InjectMocks 注入）
        ReflectionTestUtils.setField(healingServiceUnderTest, "self", healingServiceUnderTest);
    }

    @Test
    @DisplayName("自愈补位按活码部门取人")
    void selfHealingReplacementUsesQrCodeDepartment() {
        // 活码在 66 县
        QrCode qr = QrCode.builder().id(1L).schoolName("学校A").departmentId(66L).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        QrAgent removed = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("bad")
                .role(QrAgent.AgentRole.receptionist)
                .status(QrAgent.AgentStatus.removed).build();
        QrAgent keep = QrAgent.builder().id(11L).qrCodeId(1L).agentUserid("keep")
                .role(QrAgent.AgentRole.receptionist)
                .status(QrAgent.AgentStatus.active).build();
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(removed, keep));

        // 池里只有 54 县的外县员工：旧实现走无部门全局取人时会被选中并绑定
        GlobalAgentPool crossTown = GlobalAgentPool.builder()
                .agentUserid("cross-town-emp").dailyMax(150)
                .departmentId(54L)
                .status(GlobalAgentPool.PoolStatus.standby).build();
        lenient().when(poolService.takeStandby(anySet())).thenReturn(crossTown);
        // 严格同部门下本县无人 → 返回 null
        lenient().when(poolService.takeStandby(anySet(), eq(66L))).thenReturn(null);

        healingServiceUnderTest.supplementReplacement(1L);

        // 必须按活码部门 66 取人
        verify(poolService).takeStandby(
                argThat((Set<String> ids) -> ids.contains("keep") && !ids.contains("bad")),
                eq(66L));
        // 不应新增任何外县绑定
        verify(qrAgentRepo, never()).save(any(QrAgent.class));
        // 补员失败走原有告警语义
        verify(alertService).alertEmptyBackup(1L, "学校A");
    }

    @Test
    @DisplayName("服务老师下码补位用活码部门不用老师部门")
    void serviceTeacherDownCodeUsesQrCodeDepartmentNotTeacherDepartment() {
        // 活码在 66 县，服务老师本人属于 54 县
        QrCode qr = QrCode.builder().id(1L).schoolName("学校A").departmentId(66L).build();
        when(qrCodeRepo.findById(1L)).thenReturn(Optional.of(qr));

        QrAgent serviceTeacher = QrAgent.builder().id(10L).qrCodeId(1L).agentUserid("svc-teacher")
                .role(QrAgent.AgentRole.service)
                .dailyMax(150)
                .status(QrAgent.AgentStatus.active).build();
        when(qrAgentRepo.findByQrCodeIdAndAgentUserid(1L, "svc-teacher"))
                .thenReturn(Optional.of(serviceTeacher));
        // 活码上没有其他 active 成员 → 触发下码前补员
        when(qrAgentRepo.findByQrCodeIdAndStatus(1L, QrAgent.AgentStatus.active))
                .thenReturn(List.of(serviceTeacher));
        when(qrAgentRepo.findByQrCodeId(1L)).thenReturn(List.of(serviceTeacher));

        // 旧实现会优先用服务老师本人部门 54
        lenient().when(poolService.resolvePrimaryDepartmentId("svc-teacher")).thenReturn(54L);
        lenient().when(poolService.takeStandby(anySet(), eq(54L))).thenReturn(null);
        lenient().when(poolService.takeStandby(anySet(), eq(66L))).thenReturn(null);

        when(valueOps.setIfAbsent(startsWith(RedisConfig.ROTATE_LOCK_PREFIX),
                anyString(), any(Duration.class))).thenReturn(true);

        // 服务老师日限到达 → 下码前补员
        rotationService.checkAndRotate(1L, "svc-teacher", 150);

        verify(poolService).takeStandby(anySet(), eq(66L));
        verify(poolService, never()).resolvePrimaryDepartmentId(anyString());
        verify(poolService, never()).takeStandby(anySet(), eq(54L));
    }
}
