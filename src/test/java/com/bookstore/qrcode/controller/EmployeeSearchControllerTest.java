package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.EmployeeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeSearchController 员工搜索")
class EmployeeSearchControllerTest {

    @Mock private EmployeeRepository employeeRepo;

    @InjectMocks
    private EmployeeSearchController controller;

    private Employee emp(String userid, String name, boolean active) {
        return Employee.builder().userid(userid).name(name).active(active).build();
    }

    @Test
    @DisplayName("空 keyword 返回空列表")
    void emptyKeywordReturnsEmptyList() {
        List<EmployeeSearchController.EmployeeOption> result = controller.search("");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("按姓名模糊搜索，返回在职匹配员工")
    void searchByKeywordMatchesName() {
        when(employeeRepo.findByActiveTrueAndNameContainingOrderByName("张"))
                .thenReturn(List.of(emp("u1", "张三", true), emp("u2", "张伟", true)));
        when(employeeRepo.findByActiveTrueAndUseridContainingOrderByName("张"))
                .thenReturn(List.of());

        List<EmployeeSearchController.EmployeeOption> result = controller.search("张");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userid()).isEqualTo("u1");
        assertThat(result.get(0).name()).isEqualTo("张三");
    }

    @Test
    @DisplayName("按 userid 模糊搜索返回匹配")
    void searchByKeywordMatchesUserid() {
        when(employeeRepo.findByActiveTrueAndNameContainingOrderByName("u9"))
                .thenReturn(List.of());
        when(employeeRepo.findByActiveTrueAndUseridContainingOrderByName("u9"))
                .thenReturn(List.of(emp("u9x", "王五", true)));

        List<EmployeeSearchController.EmployeeOption> result = controller.search("u9");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userid()).isEqualTo("u9x");
    }

    @Test
    @DisplayName("姓名与 userid 同时命中时按 userid 去重，姓名结果优先")
    void dedupesWhenNameAndUseridBothMatch() {
        when(employeeRepo.findByActiveTrueAndNameContainingOrderByName("张"))
                .thenReturn(List.of(emp("u1", "张三", true)));
        when(employeeRepo.findByActiveTrueAndUseridContainingOrderByName("张"))
                .thenReturn(List.of(emp("u1", "张三", true), emp("zhang2", "李四", true)));

        List<EmployeeSearchController.EmployeeOption> result = controller.search("张");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(EmployeeSearchController.EmployeeOption::userid))
                .containsExactly("u1", "zhang2");
    }

    @Test
    @DisplayName("超过 20 条时截断为 20 条")
    void truncatesToTwenty() {
        List<Employee> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(emp("u" + i, "员工" + i, true));
        }
        when(employeeRepo.findByActiveTrueAndNameContainingOrderByName("员工"))
                .thenReturn(many);
        when(employeeRepo.findByActiveTrueAndUseridContainingOrderByName("员工"))
                .thenReturn(List.of());

        List<EmployeeSearchController.EmployeeOption> result = controller.search("员工");

        assertThat(result).hasSize(20);
    }
}
