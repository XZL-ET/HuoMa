package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工搜索 JSON 接口 —— 供删除记录页「今日推送接收人」搜索选择器使用。
 *
 * <p>按姓名或 userid 模糊匹配在职员工，合并去重后取前 20 条返回。
 * 空关键字返回空列表（用户尚未输入时不弹下拉）。</p>
 */
@RestController
@RequiredArgsConstructor
public class EmployeeSearchController {

    private static final int MAX_RESULTS = 20;

    private final EmployeeRepository employeeRepo;

    @GetMapping("/api/employees/search")
    public List<EmployeeOption> search(@RequestParam(required = false) String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        Map<String, Employee> merged = new LinkedHashMap<>();
        for (Employee e : employeeRepo.findByActiveTrueAndNameContainingOrderByName(kw)) {
            merged.put(e.getUserid(), e);
        }
        for (Employee e : employeeRepo.findByActiveTrueAndUseridContainingOrderByName(kw)) {
            merged.putIfAbsent(e.getUserid(), e);
        }
        return merged.values().stream()
                .limit(MAX_RESULTS)
                .map(e -> new EmployeeOption(e.getUserid(), e.getName()))
                .toList();
    }

    public record EmployeeOption(String userid, String name) {}
}
