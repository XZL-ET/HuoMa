package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalAgentPoolService 全局员工池")
class GlobalAgentPoolServiceTest {

    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private WecomApiClient wecomApiClient;
    @Mock private ObjectMapper objectMapper;
    @Mock private DepartmentWhitelistService whitelistService;

    @InjectMocks
    private GlobalAgentPoolService poolService;

    /** 默认白名单未启用（空集合即未启用）；启用用例各自覆盖。 */
    @BeforeEach
    void whitelistDisabledByDefault() {
        lenient().when(whitelistService.loadAllowedDeptIdsWithTreeStatus())
            .thenReturn(new DepartmentWhitelistService.AllowedDeptResult(Set.of(), true));
    }

    @Test
    @DisplayName("takeStandby — 从池中取优先级最高待命员工")
    void shouldTakeHighestPriorityStandby() {
        GlobalAgentPool p1 = GlobalAgentPool.builder()
                .id(1L).agentUserid("user1").sortOrder(10).status(GlobalAgentPool.PoolStatus.standby).build();
        GlobalAgentPool p2 = GlobalAgentPool.builder()
                .id(2L).agentUserid("user2").sortOrder(5).status(GlobalAgentPool.PoolStatus.standby).build();

        when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
                .thenReturn(List.of(p2, p1));

        GlobalAgentPool taken = poolService.takeStandby(Collections.emptySet());

        assertThat(taken).isNotNull();
        assertThat(taken.getAgentUserid()).isEqualTo("user2");
    }

    @Test
    @DisplayName("takeStandby — 排除指定员工")
    void shouldExcludeSpecifiedUserids() {
        GlobalAgentPool p1 = GlobalAgentPool.builder()
                .id(1L).agentUserid("user1").sortOrder(10).status(GlobalAgentPool.PoolStatus.standby).build();

        when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
                .thenReturn(List.of(p1));

        GlobalAgentPool taken = poolService.takeStandby(Set.of("user1"));

        assertThat(taken).isNull();
    }

    @Test
    @DisplayName("takeStandby — 池空时返回 null")
    void shouldReturnNullWhenPoolEmpty() {
        when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
                .thenReturn(List.of());

        assertThat(poolService.takeStandby(Collections.emptySet())).isNull();
    }

    @Test
    @DisplayName("markFull — 标记员工为满")
    void shouldMarkAgentAsFull() {
        GlobalAgentPool pool = GlobalAgentPool.builder()
                .id(1L).agentUserid("user1").status(GlobalAgentPool.PoolStatus.standby).build();
        when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.of(pool));

        poolService.markFull("user1");

        assertThat(pool.getStatus()).isEqualTo(GlobalAgentPool.PoolStatus.full);
    }

    @Test
    @DisplayName("countStandby — 统计待命员工数")
    void shouldCountStandby() {
        when(poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby)).thenReturn(5L);

        assertThat(poolService.countStandby()).isEqualTo(5);
    }

    @Test
    @DisplayName("ensureInPool — 已在池中直接返回")
    void shouldReturnExistingWhenAlreadyInPool() {
        GlobalAgentPool existing = GlobalAgentPool.builder().id(1L).agentUserid("user1").build();
        when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.of(existing));

        poolService.ensureInPool("user1", 100);

        verify(poolRepo, never()).save(any());
    }

    @Test
    @DisplayName("ensureInPool — 不在池中从 Employee 创建")
    void shouldCreateFromEmployeeWhenNotInPool() {
        when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.empty());
        when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(
                Employee.builder().userid("user1").name("张三").build()));
        when(poolRepo.findFirstByOrderBySortOrderDesc()).thenReturn(Optional.empty());

        poolService.ensureInPool("user1", 100);

        verify(poolRepo).save(any(GlobalAgentPool.class));
    }

    @Test
    @DisplayName("ensureInPool — 无关部门员工不入池，并清理已有记录")
    void ensureInPoolSkipsDisallowed() {
        when(whitelistService.loadAllowedDeptIdsWithTreeStatus())
            .thenReturn(new DepartmentWhitelistService.AllowedDeptResult(Set.of(100L), true));
        Employee bad = Employee.builder().userid("user1")
            .department("[200]").active(true).wechatStatus(1).build();
        when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(bad));
        when(whitelistService.isEmployeeAllowed(bad, Set.of(100L))).thenReturn(false);
        GlobalAgentPool existing = GlobalAgentPool.builder().id(1L).agentUserid("user1").build();
        when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.of(existing));

        poolService.ensureInPool("user1", 100);

        verify(poolRepo).delete(existing);
        verify(poolRepo, never()).save(any());
    }

    @Test
    @DisplayName("ensureInPool — 部门树不可用时暂缓入池且不删除已有记录")
    void ensureInPoolKeepsRowWhenTreeUnavailable() {
        when(whitelistService.loadAllowedDeptIdsWithTreeStatus())
            .thenReturn(new DepartmentWhitelistService.AllowedDeptResult(Set.of(100L), false));
        Employee child = Employee.builder().userid("user1")
            .department("[101]").active(true).wechatStatus(1).build();
        when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(child));
        when(whitelistService.isEmployeeAllowed(child, Set.of(100L))).thenReturn(false);

        poolService.ensureInPool("user1", 100);

        verify(poolRepo, never()).delete(any());
        verify(poolRepo, never()).save(any());
    }

    @Test
    @DisplayName("takeStandby — 懒清理无关部门候选并跳过")
    void takeStandbyDeletesDisallowedCandidate() {
        when(whitelistService.loadAllowedDeptIdsWithTreeStatus())
            .thenReturn(new DepartmentWhitelistService.AllowedDeptResult(Set.of(100L), true));
        GlobalAgentPool p = GlobalAgentPool.builder()
            .id(1L).agentUserid("user1").sortOrder(1)
            .status(GlobalAgentPool.PoolStatus.standby).build();
        Employee bad = Employee.builder().userid("user1")
            .department("[200]").active(true).wechatStatus(1).build();
        when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
            .thenReturn(List.of(p));
        when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(bad));
        when(whitelistService.isEmployeeAllowed(bad, Set.of(100L))).thenReturn(false);

        assertThat(poolService.takeStandby(Set.of())).isNull();
        verify(poolRepo).delete(p);
    }

    @Test
    @DisplayName("takeStandby — 部门树不可用时跳过候选但不删除池记录")
    void takeStandbyKeepsCandidateWhenTreeUnavailable() {
        when(whitelistService.loadAllowedDeptIdsWithTreeStatus())
            .thenReturn(new DepartmentWhitelistService.AllowedDeptResult(Set.of(100L), false));
        GlobalAgentPool p = GlobalAgentPool.builder()
            .id(1L).agentUserid("user1").sortOrder(1)
            .status(GlobalAgentPool.PoolStatus.standby).build();
        Employee child = Employee.builder().userid("user1")
            .department("[101]").active(true).wechatStatus(1).build();
        when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
            .thenReturn(List.of(p));
        when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(child));
        when(whitelistService.isEmployeeAllowed(child, Set.of(100L))).thenReturn(false);

        assertThat(poolService.takeStandby(Set.of())).isNull();
        verify(poolRepo, never()).delete(any());
    }
}
