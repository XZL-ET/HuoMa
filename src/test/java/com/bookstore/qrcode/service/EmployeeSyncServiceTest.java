package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.Employee;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
}
