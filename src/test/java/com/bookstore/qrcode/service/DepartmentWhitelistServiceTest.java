package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("部门准入白名单判定")
class DepartmentWhitelistServiceTest {

    @Mock private PoolAllowedDepartmentRepository repo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private DepartmentService departmentService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private DepartmentWhitelistService service;

    private static PoolAllowedDepartment row(long id) {
        return PoolAllowedDepartment.builder().deptId(id).deptName("d" + id).build();
    }

    @Test
    @DisplayName("空表 → 未启用")
    void inactiveWhenEmpty() {
        when(repo.count()).thenReturn(0L);
        assertThat(service.isActive()).isFalse();
    }

    @Test
    @DisplayName("允许集合包含全部子孙部门")
    void expandsDescendants() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L),
            101L, new DepartmentService.DeptNode(101, "校区A", 100L),
            102L, new DepartmentService.DeptNode(102, "校区B", 101L),
            200L, new DepartmentService.DeptNode(200, "后勤", 0L)));

        assertThat(service.loadAllowedDeptIds(true))
            .containsExactlyInAnyOrder(100L, 101L, 102L);
    }

    @Test
    @DisplayName("strict 模式部门树失败 → 抛 DeptTreeUnavailableException")
    void strictFailureThrows() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.loadAllowedDeptIds(true))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
    }

    @Test
    @DisplayName("非 strict 模式失败 → 退化为精确白名单 ID")
    void nonStrictDegrades() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("down"));

        assertThat(service.loadAllowedDeptIds(false)).containsExactly(100L);
    }

    @Test
    @DisplayName("带树状态的允许集合：白名单为空 → 空集合且树可用")
    void treeStatusEmptyWhitelist() {
        when(repo.findAll()).thenReturn(List.of());

        DepartmentWhitelistService.AllowedDeptResult result =
            service.loadAllowedDeptIdsWithTreeStatus();

        assertThat(result.ids()).isEmpty();
        assertThat(result.treeAvailable()).isTrue();
    }

    @Test
    @DisplayName("带树状态的允许集合：部门树失败 → 精确 ID 且标记树不可用")
    void treeStatusDegradesWhenTreeFails() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("down"));

        DepartmentWhitelistService.AllowedDeptResult result =
            service.loadAllowedDeptIdsWithTreeStatus();

        assertThat(result.ids()).containsExactly(100L);
        assertThat(result.treeAvailable()).isFalse();
        // 启用判定与允许集合必须同源：只读一次白名单表
        verify(repo, times(1)).findAll();
    }

    @Test
    @DisplayName("带树状态的允许集合：部门树可用 → 含子孙部门且标记可用")
    void treeStatusExpandsWhenTreeAvailable() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L),
            101L, new DepartmentService.DeptNode(101, "校区A", 100L),
            200L, new DepartmentService.DeptNode(200, "后勤", 0L)));

        DepartmentWhitelistService.AllowedDeptResult result =
            service.loadAllowedDeptIdsWithTreeStatus();

        assertThat(result.ids()).containsExactlyInAnyOrder(100L, 101L);
        assertThat(result.treeAvailable()).isTrue();
        verify(repo, times(1)).findAll();
    }

    @Test
    @DisplayName("兼属多部门：任一命中即允许")
    void anyDepartmentMembershipAllows() {
        Employee emp = Employee.builder().userid("u1").department("[200,100]").build();
        assertThat(service.isEmployeeAllowed(emp, Set.of(100L))).isTrue();
    }

    @Test
    @DisplayName("无部门或空数组 → 不允许")
    void emptyDepartmentNotAllowed() {
        assertThat(service.isEmployeeAllowed(
            Employee.builder().userid("u1").department(null).build(), Set.of(100L))).isFalse();
        assertThat(service.isEmployeeAllowed(
            Employee.builder().userid("u2").department("[]").build(), Set.of(100L))).isFalse();
    }

    @Test
    @DisplayName("每次调用都读 DB（跨节点实时一致，不做本地强缓存）")
    void readsDbEveryCall() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));

        service.loadAllowedDeptIds(false);
        service.loadAllowedDeptIds(false);

        verify(repo, times(2)).findAll();
    }

    @Test
    @DisplayName("保存白名单时用部门树名称写入快照")
    void replaceWhitelistUsesTreeNames() {
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));

        service.replaceWhitelist(List.of(100L), "tester");

        verify(repo).deleteAll();
        verify(repo).saveAll(argThat(rows -> {
            List<PoolAllowedDepartment> list = (List<PoolAllowedDepartment>) rows;
            return list.size() == 1
                && list.get(0).getDeptId().equals(100L)
                && list.get(0).getDeptName().equals("校区")
                && list.get(0).getCreatedBy().equals("tester");
        }));
    }

    @Test
    @DisplayName("部门树 API 失败 → replaceWhitelist 抛 DeptTreeUnavailableException 且不落库")
    void replaceWhitelistWrapsTreeFailure() {
        when(departmentService.loadDeptTree())
            .thenThrow(new WecomApiException(500, "down", null));

        assertThatThrownBy(() -> service.replaceWhitelist(List.of(1L), "tester"))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);

        verify(repo, never()).deleteAll();
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("department 为字面量 \"null\" → 不允许")
    void literalNullDepartmentIsDisallowed() {
        Employee emp = Employee.builder().userid("u1").department("null").build();

        assertThat(service.isEmployeeAllowed(emp, Set.of(1L))).isFalse();
    }

    @Test
    @DisplayName("未启用时 filter 接受 null 集合 → 返回空集合")
    void filterAllowsNullWhenInactive() {
        assertThat(service.filterAllowedEmployees(null)).isEmpty();
        assertThat(service.filterAllowedUserids(null)).isEmpty();
    }
}
