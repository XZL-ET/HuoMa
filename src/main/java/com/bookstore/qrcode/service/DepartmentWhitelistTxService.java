package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门准入应用的事务步骤。
 *
 * <p>把「全量替换白名单」与「移出无关池成员」放进同一事务：
 * 白名单保存委托给 {@link DepartmentWhitelistService#replaceWhitelist(List, String)}
 * （自带 {@code @Transactional}，加入当前事务，并负责部门名校验与快照写入），
 * 本服务不再重复实现 deleteAll/saveAll。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentWhitelistTxService {

    private final DepartmentWhitelistService whitelist;
    private final GlobalAgentPoolRepository poolRepo;
    private final EmployeeRepository employeeRepo;

    /**
     * 保存新白名单，并把不再命中的池成员移出全局员工池。
     *
     * @param deptIds       白名单根部门 ID
     * @param allowedDeptIds 已展开的允许部门 ID 集合（含子孙）
     * @param operator       操作人
     * @return 被移出池的记录数
     */
    @Transactional
    public int replaceWhitelistAndRemoveFromPool(
        List<Long> deptIds, Set<Long> allowedDeptIds, String operator) {

        whitelist.replaceWhitelist(deptIds, operator);

        List<GlobalAgentPool> poolRows = poolRepo.findAll();
        if (poolRows.isEmpty()) {
            return 0;
        }

        Set<String> userids = new LinkedHashSet<>();
        for (GlobalAgentPool row : poolRows) {
            userids.add(row.getAgentUserid());
        }
        Map<String, Employee> employeeByUserid = new HashMap<>();
        for (Employee employee : employeeRepo.findByUseridIn(userids)) {
            employeeByUserid.put(employee.getUserid(), employee);
        }

        List<GlobalAgentPool> disallowed = new ArrayList<>();
        for (GlobalAgentPool row : poolRows) {
            Employee employee = employeeByUserid.get(row.getAgentUserid());
            if (employee == null || !whitelist.isEmployeeAllowed(employee, allowedDeptIds)) {
                disallowed.add(row);
            }
        }
        if (disallowed.isEmpty()) {
            return 0;
        }
        poolRepo.deleteAll(disallowed);
        log.info("部门准入：移出无关池成员 {} 人", disallowed.size());
        return disallowed.size();
    }
}
