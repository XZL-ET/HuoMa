package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeSyncService 僵尸对账")
class EmployeeSyncServiceTest {

    @Mock private EmployeeRepository employeeRepo;
    @Mock private WecomApiClient wecomApi;
    @Mock private GlobalAgentPoolService poolService;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private AlertService alertService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ServiceTeacherDailyMaxService serviceTeacherDailyMaxService;

    @InjectMocks
    private EmployeeSyncService service;

    @Test
    @DisplayName("对账僵尸记录：离职员工 agent normal / qr_agent active → 封禁 + 下码 + 告警服务老师")
    void shouldReconcileZombieAgents() {
        Employee departed = new Employee();
        departed.setUserid("bad");
        when(employeeRepo.findByActiveFalse()).thenReturn(List.of(departed));
        when(employeeRepo.countByActiveTrue()).thenReturn(100L);
        when(agentRepo.findNormalUseridsIn(any())).thenReturn(Set.of("bad"));
        when(qrAgentRepo.findUseridsWithActiveBinding(any())).thenReturn(Set.of("bad"));

        when(qrAgentRepo.findServiceUseridsIn(List.of("bad"))).thenReturn(List.of("bad"));
        when(agentRepo.batchBlockByUserids(List.of("bad"))).thenReturn(1);
        when(qrAgentRepo.batchRemoveByAgentUserids(List.of("bad"))).thenReturn(1);

        QrAgent removed = QrAgent.builder().qrCodeId(1L).agentUserid("bad")
            .status(QrAgent.AgentStatus.removed).build();
        when(qrAgentRepo.findByAgentUserid("bad")).thenReturn(List.of(removed));

        service.reconcileZombieAgents();

        verify(agentRepo).batchBlockByUserids(List.of("bad"));
        verify(qrAgentRepo).batchRemoveByAgentUserids(List.of("bad"));
        verify(alertService).createAlert(eq("bad"), eq("employee_departed_service"),
            eq(AgentAlert.AlertSeverity.high), any(), eq(AgentAlert.AutoAction.none), isNull());
    }

    @Test
    @DisplayName("僵尸数占比超 30% 时跳过对账，防止跨轮 API 异常批量误伤")
    void shouldSkipReconcileWhenRatioTooHigh() {
        List<Employee> zombies = new ArrayList<>();
        Set<String> zombieUserids = new HashSet<>();
        for (int i = 0; i < 45; i++) {
            Employee e = new Employee();
            e.setUserid("zombie" + i);
            zombies.add(e);
            zombieUserids.add("zombie" + i);
        }
        when(employeeRepo.findByActiveFalse()).thenReturn(zombies);
        when(employeeRepo.countByActiveTrue()).thenReturn(5L);
        when(agentRepo.findNormalUseridsIn(any())).thenReturn(zombieUserids);
        when(qrAgentRepo.findUseridsWithActiveBinding(any())).thenReturn(Set.of());

        service.reconcileZombieAgents();

        verify(agentRepo, never()).batchBlockByUserids(any());
        verify(qrAgentRepo, never()).batchRemoveByAgentUserids(any());
    }

    @Test
    @DisplayName("回填偏小 daily_max：接待员抬到 150，服务老师/双角色抬到 300，只升不降")
    void shouldRaiseLowDailyMaxToRoleDefault() {
        ReflectionTestUtils.setField(service, "dailyMaxDefault", 150);
        when(serviceTeacherDailyMaxService.resolveDefault()).thenReturn(300);

        GlobalAgentPool receptionist = GlobalAgentPool.builder()
            .agentUserid("r1").dailyMax(100).build();
        GlobalAgentPool serviceTeacher = GlobalAgentPool.builder()
            .agentUserid("s1").dailyMax(100).build();
        GlobalAgentPool dual = GlobalAgentPool.builder()
            .agentUserid("d1").dailyMax(100).build();
        GlobalAgentPool alreadyHigh = GlobalAgentPool.builder()
            .agentUserid("r2").dailyMax(200).build();

        when(poolRepo.findWithDailyMaxBelow(300))
            .thenReturn(List.of(receptionist, serviceTeacher, dual, alreadyHigh));
        when(agentRepo.findAllById(any())).thenReturn(List.of(
            Agent.builder().userid("r1").role(Agent.AgentRole.receptionist).build(),
            Agent.builder().userid("s1").role(Agent.AgentRole.service).build(),
            Agent.builder().userid("d1").role(Agent.AgentRole.dual).build(),
            Agent.builder().userid("r2").role(Agent.AgentRole.receptionist).build()
        ));

        int raised = service.backfillLowDailyMax();

        assertThat(raised).isEqualTo(3);
        assertThat(receptionist.getDailyMax()).isEqualTo(150);
        assertThat(serviceTeacher.getDailyMax()).isEqualTo(300);
        assertThat(dual.getDailyMax()).isEqualTo(300);
        assertThat(alreadyHigh.getDailyMax()).isEqualTo(200);
        verify(poolRepo).saveAll(any());
    }
}
