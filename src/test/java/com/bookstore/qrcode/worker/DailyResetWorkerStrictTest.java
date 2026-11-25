package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentAlertRepository;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.DailyReportRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AgentDailyCountService;
import com.bookstore.qrcode.service.AgentRotationService;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.DepartmentWhitelistService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import com.bookstore.qrcode.service.StrictDeptConfigService;
import com.bookstore.qrcode.service.StrictDeptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 每日重置中严格同部门护栏的行为。
 *
 * <p>自动流程取 fail-open：树不可用/无档时照旧复活，只有明确判出外县才置 removed。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("严格同部门：每日重置护栏")
class DailyResetWorkerStrictTest {

    private static final long LOCAL_DEPT = 66L;
    private static final long OTHER_DEPT = 54L;
    private static final long QR_ID = 100L;

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
    @Mock private DepartmentWhitelistService whitelist;
    @Mock private StrictDeptConfigService strictDeptConfig;
    @Mock private StrictDeptService strictDeptService;
    @Mock private EmployeeRepository employeeRepo;

    @InjectMocks
    private DailyResetWorker worker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(worker, "self", worker);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("full 外县接待员 → 置 removed，不复活")
    void removesOtherDistrictFullReceptionist() {
        QrAgent qa = fullReceptionist("u-other");
        Employee emp = emp("u-other", OTHER_DEPT);
        stubCommon(List.of(qa));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(QR_ID)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(employeeRepo.findByUserid("u-other")).thenReturn(Optional.of(emp));
        when(strictDeptService.expandLocalDeptIds(LOCAL_DEPT)).thenReturn(Set.of(LOCAL_DEPT));
        when(whitelist.isEmployeeAllowed(emp, Set.of(LOCAL_DEPT))).thenReturn(false);

        worker.recoverFullAgents();

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed);
    }

    @Test
    @DisplayName("full 本县接待员 → 照常复活为 active")
    void revivesLocalFullReceptionist() {
        QrAgent qa = fullReceptionist("u-local");
        Employee emp = emp("u-local", LOCAL_DEPT);
        stubCommon(List.of(qa));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(QR_ID)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(employeeRepo.findByUserid("u-local")).thenReturn(Optional.of(emp));
        when(strictDeptService.expandLocalDeptIds(LOCAL_DEPT)).thenReturn(Set.of(LOCAL_DEPT));
        when(whitelist.isEmployeeAllowed(emp, Set.of(LOCAL_DEPT))).thenReturn(true);
        when(agentRepo.findById("u-local")).thenReturn(Optional.of(normalAgent("u-local")));

        worker.recoverFullAgents();

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    @Test
    @DisplayName("部门树不可用 → 跳过严格守卫，照常复活")
    void failsOpenWhenTreeUnavailable() {
        QrAgent qa = fullReceptionist("u-any");
        Employee emp = emp("u-any", OTHER_DEPT);
        stubCommon(List.of(qa));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfig.getExemptUserids()).thenReturn(Set.of());
        when(qrCodeRepo.findById(QR_ID)).thenReturn(Optional.of(qr(LOCAL_DEPT)));
        when(employeeRepo.findByUserid("u-any")).thenReturn(Optional.of(emp));
        when(strictDeptService.expandLocalDeptIds(LOCAL_DEPT))
            .thenThrow(new DepartmentWhitelistService.DeptTreeUnavailableException("tree down"));
        when(agentRepo.findById("u-any")).thenReturn(Optional.of(normalAgent("u-any")));

        worker.recoverFullAgents();

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    @Test
    @DisplayName("严格关闭 → 外县 full 接待员照常复活")
    void strictOffRevivesAll() {
        QrAgent qa = fullReceptionist("u-other");
        stubCommon(List.of(qa));
        when(strictDeptConfig.isStrictModeEnabled()).thenReturn(false);
        when(agentRepo.findById("u-other")).thenReturn(Optional.of(normalAgent("u-other")));

        worker.recoverFullAgents();

        assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    // ==================== helpers ====================

    private void stubCommon(List<QrAgent> fullAgents) {
        when(qrAgentRepo.findAll()).thenReturn(fullAgents);
        when(qrAgentRepo.findByStatus(QrAgent.AgentStatus.full)).thenReturn(fullAgents);
        when(whitelist.isActive()).thenReturn(false);
        when(qrAgentRepo.findByTemporaryTrue()).thenReturn(List.of());
    }

    private static QrAgent fullReceptionist(String userid) {
        return QrAgent.builder().id(1000L).qrCodeId(QR_ID).agentUserid(userid)
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.full)
            .dailyCurrent(0)
            .build();
    }

    private static Employee emp(String userid, long departmentId) {
        return Employee.builder().userid(userid).name(userid)
            .department("[" + departmentId + "]").build();
    }

    private static QrCode qr(Long departmentId) {
        return QrCode.builder().id(QR_ID).schoolName("某学校").departmentId(departmentId).build();
    }

    private static Agent normalAgent(String userid) {
        return Agent.builder().userid(userid).name(userid)
            .overallStatus(Agent.OverallStatus.normal).build();
    }
}
