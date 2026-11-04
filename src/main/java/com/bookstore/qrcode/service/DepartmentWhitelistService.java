package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全局员工池部门准入白名单判定服务。
 *
 * <p>这是员工池所有准入判定的唯一入口。白名单每次操作都从 DB 读取，
 * 不做实例级缓存——服务部署在 ALB 后的双节点上，白名单改动必须立即在
 * 两个节点同时生效。</p>
 *
 * <p>白名单为空表示功能未启用，此时所有员工都视为允许。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentWhitelistService {

    private final PoolAllowedDepartmentRepository poolAllowedDepartmentRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentService departmentService;
    private final ObjectMapper objectMapper;

    /**
     * 白名单是否启用。
     *
     * @return 白名单表非空时为 true
     */
    public boolean isActive() {
        return poolAllowedDepartmentRepository.count() > 0;
    }

    /**
     * 加载允许的部门 ID 集合：白名单部门本身及其全部子孙部门。
     *
     * @param strict true 表示部门树不可用时抛出异常；false 表示降级为精确白名单 ID
     * @return 允许的部门 ID 集合；白名单为空时返回空集合
     * @throws DeptTreeUnavailableException strict 模式下部门树拉取失败或为空
     */
    public Set<Long> loadAllowedDeptIds(boolean strict) {
        List<PoolAllowedDepartment> rows = poolAllowedDepartmentRepository.findAll();
        if (rows.isEmpty()) {
            return Set.of();
        }
        Set<Long> whitelistIds = whitelistIds(rows);
        try {
            return expandWithTree(whitelistIds);
        } catch (DeptTreeUnavailableException e) {
            if (strict) {
                throw e;
            }
            log.warn("部门树拉取失败，白名单降级为精确部门 ID 判定", e);
            return whitelistIds;
        }
    }

    /**
     * 允许部门集合及部门树可用性。
     *
     * <p>{@code treeAvailable = false} 时 {@code ids} 只包含精确的白名单部门 ID，
     * 不能据此执行任何删除——子部门员工会被误判为「无关」。</p>
     *
     * <p>{@code ids} 为空表示白名单未启用（功能关闭，所有人允许）。
     * 启用的白名单至少有一行，因此空集合不会与「启用但无人允许」混淆。</p>
     *
     * @param ids           允许的部门 ID（树不可用时为精确白名单 ID）
     * @param treeAvailable 部门树是否可用
     */
    public record AllowedDeptResult(Set<Long> ids, boolean treeAvailable) {

        /** 白名单是否启用。 */
        public boolean isEnabled() {
            return !ids.isEmpty();
        }
    }

    /**
     * 供破坏性清理场景读取允许集合。
     *
     * <p>与 {@link #loadAllowedDeptIds(boolean)} 的区别：部门树不可用时额外返回
     * {@code treeAvailable = false}，调用方必须跳过删除动作，仅可据此「少放人」。
     * 日常入池/取人/添加接待员的降级语义不变。</p>
     *
     * <p>白名单行与允许集合来自同一次读取，调用方不要再用 {@link #isActive()}
     * 另行判断——两次查询之间白名单可能被清空，会把「功能已关闭」误判成
     * 「一人都不允许」而误删全部池记录。</p>
     *
     * @return 允许集合与部门树可用性；白名单未启用时返回空集合且标记可用
     */
    public AllowedDeptResult loadAllowedDeptIdsWithTreeStatus() {
        List<PoolAllowedDepartment> rows = poolAllowedDepartmentRepository.findAll();
        if (rows.isEmpty()) {
            return new AllowedDeptResult(Set.of(), true);
        }
        Set<Long> whitelistIds = whitelistIds(rows);
        try {
            return new AllowedDeptResult(expandWithTree(whitelistIds), true);
        } catch (DeptTreeUnavailableException e) {
            log.warn("部门树不可用，允许集合降级为精确部门 ID，本轮跳过破坏性清理", e);
            return new AllowedDeptResult(whitelistIds, false);
        }
    }

    private Set<Long> whitelistIds(List<PoolAllowedDepartment> rows) {
        Set<Long> whitelistIds = new LinkedHashSet<>();
        for (PoolAllowedDepartment row : rows) {
            whitelistIds.add(row.getDeptId());
        }
        return whitelistIds;
    }

    /**
     * 展开白名单部门及其全部子孙部门。
     *
     * @throws DeptTreeUnavailableException 部门树拉取失败或为空
     */
    private Set<Long> expandWithTree(Set<Long> whitelistIds) {
        Map<Long, DepartmentService.DeptNode> tree;
        try {
            tree = departmentService.loadDeptTree();
        } catch (Exception e) {
            throw new DeptTreeUnavailableException("部门树拉取失败", e);
        }
        if (tree == null || tree.isEmpty()) {
            throw new DeptTreeUnavailableException("部门树拉取失败: 部门树为空");
        }

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (DepartmentService.DeptNode node : tree.values()) {
            if (node.parentId() != null) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node.id());
            }
        }

        Set<Long> allowed = new LinkedHashSet<>();
        List<Long> stack = new ArrayList<>(whitelistIds);
        while (!stack.isEmpty()) {
            Long current = stack.remove(stack.size() - 1);
            if (!allowed.add(current)) {
                continue;
            }
            List<Long> children = childrenByParent.get(current);
            if (children != null) {
                stack.addAll(children);
            }
        }
        return allowed;
    }

    /**
     * 判断员工是否命中允许部门集合。
     *
     * @param emp 员工记录
     * @param allowedDeptIds 允许的部门 ID 集合
     * @return 员工所属部门任一命中即为 true；员工为空或部门解析失败为 false
     */
    public boolean isEmployeeAllowed(Employee emp, Set<Long> allowedDeptIds) {
        if (emp == null || allowedDeptIds == null || allowedDeptIds.isEmpty()) {
            return false;
        }
        String department = emp.getDepartment();
        if (department == null || department.isBlank()) {
            return false;
        }
        long[] deptIds;
        try {
            deptIds = objectMapper.readValue(department, long[].class);
        } catch (Exception e) {
            log.debug("员工部门 JSON 解析失败, userid={}, department={}", emp.getUserid(), department, e);
            return false;
        }
        if (deptIds == null) {
            return false;
        }
        for (long deptId : deptIds) {
            if (allowedDeptIds.contains(deptId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断指定 userid 的员工是否允许进入员工池。
     *
     * @param userid 企微 userid
     * @return 白名单未启用时始终 true；启用时按员工部门判定，查无此人返回 false
     */
    public boolean isAllowed(String userid) {
        if (!isActive()) {
            return true;
        }
        Optional<Employee> employee = employeeRepository.findByUserid(userid);
        if (employee.isEmpty()) {
            return false;
        }
        return isEmployeeAllowed(employee.get(), loadAllowedDeptIds(false));
    }

    /**
     * 过滤员工列表，仅保留命中白名单部门的员工。
     *
     * @param employees 待过滤员工
     * @return 允许的员工列表；白名单未启用时原样返回副本
     */
    public List<Employee> filterAllowedEmployees(Collection<Employee> employees) {
        if (employees == null) {
            return List.of();
        }
        if (!isActive()) {
            return new ArrayList<>(employees);
        }
        Set<Long> allowedDeptIds = loadAllowedDeptIds(false);
        List<Employee> result = new ArrayList<>();
        for (Employee employee : employees) {
            if (isEmployeeAllowed(employee, allowedDeptIds)) {
                result.add(employee);
            }
        }
        return result;
    }

    /**
     * 过滤 userid 集合，仅保留命中白名单部门的员工（非严格模式）。
     *
     * <p>部门树不可用时降级为精确白名单 ID 判定，适合「放行」类调用。
     * 需要区分「树不可用」的调用方（如每日重置）应使用
     * {@link #filterAllowedUseridsStrict(Collection)}。</p>
     *
     * @param userids 待过滤 userid
     * @return 允许的 userid 集合；白名单未启用时原样返回副本
     */
    public Set<String> filterAllowedUserids(Collection<String> userids) {
        return filterAllowedUserids(userids, false);
    }

    /**
     * 过滤 userid 集合，仅保留命中白名单部门的员工（严格模式）。
     *
     * <p>与 {@link #filterAllowedUserids(Collection)} 行为一致，但部门树不可用
     * （拉取失败或为空）时抛出 {@link DeptTreeUnavailableException}，由调用方决定
     * 是中止操作还是显式跳过守卫——不能把「树坏了」误当作「都不允许」。</p>
     *
     * @param userids 待过滤 userid
     * @return 允许的 userid 集合；白名单未启用时原样返回副本
     * @throws DeptTreeUnavailableException 部门树不可用
     */
    public Set<String> filterAllowedUseridsStrict(Collection<String> userids) {
        return filterAllowedUserids(userids, true);
    }

    private Set<String> filterAllowedUserids(Collection<String> userids, boolean strict) {
        if (userids == null) {
            return Set.of();
        }
        if (!isActive()) {
            return new LinkedHashSet<>(userids);
        }
        Set<String> result = new LinkedHashSet<>();
        if (userids.isEmpty()) {
            return result;
        }
        Set<Long> allowedDeptIds = loadAllowedDeptIds(strict);
        for (Employee employee : employeeRepository.findByUseridIn(userids)) {
            if (isEmployeeAllowed(employee, allowedDeptIds)) {
                result.add(employee.getUserid());
            }
        }
        return result;
    }

    /**
     * 查找满足准入条件、尚未进入员工池的候选 userid。
     *
     * <p>候选条件：在职、企微状态为 null 或已激活（1）、不在当前池中、命中白名单部门。</p>
     *
     * @param pooledUserids 当前已在池中的 userid 集合
     * @return 候选 userid 集合；白名单未启用时返回空集合
     */
    public Set<String> findAllowedCandidateUseridsNotInPool(Set<String> pooledUserids) {
        Set<String> result = new LinkedHashSet<>();
        if (!isActive()) {
            return result;
        }
        Set<Long> allowedDeptIds = loadAllowedDeptIds(false);
        Set<String> pooled = pooledUserids == null ? Set.of() : pooledUserids;
        for (Employee employee : employeeRepository.findAllByActiveTrueOrderByName()) {
            Integer wechatStatus = employee.getWechatStatus();
            if (wechatStatus != null && wechatStatus != 1) {
                continue;
            }
            if (pooled.contains(employee.getUserid())) {
                continue;
            }
            if (isEmployeeAllowed(employee, allowedDeptIds)) {
                result.add(employee.getUserid());
            }
        }
        return result;
    }

    /**
     * 统计满足准入条件、尚未进入员工池的候选人数。
     *
     * @param pooledUserids 当前已在池中的 userid 集合
     * @return 候选人数；白名单未启用时返回 0
     * @see #findAllowedCandidateUseridsNotInPool(Set)
     */
    public long countAllowedCandidatesNotInPool(Set<String> pooledUserids) {
        return findAllowedCandidateUseridsNotInPool(pooledUserids).size();
    }

    /**
     * 全量替换白名单，并以部门树名称写入快照。
     *
     * @param deptIds 白名单部门 ID；不能为空
     * @param operator 操作人
     * @throws IllegalArgumentException 部门列表为空或包含未知部门
     * @throws DeptTreeUnavailableException 部门树不可用
     */
    @Transactional
    public void replaceWhitelist(List<Long> deptIds, String operator) {
        if (deptIds == null || deptIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个部门");
        }
        Map<Long, DepartmentService.DeptNode> tree;
        try {
            tree = departmentService.loadDeptTree();
        } catch (Exception e) {
            throw new DeptTreeUnavailableException("部门树拉取失败", e);
        }
        if (tree == null || tree.isEmpty()) {
            throw new DeptTreeUnavailableException("部门树拉取失败: 部门树为空");
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(deptIds);
        List<PoolAllowedDepartment> rows = new ArrayList<>(uniqueIds.size());
        for (Long deptId : uniqueIds) {
            DepartmentService.DeptNode node = tree.get(deptId);
            if (node == null) {
                throw new IllegalArgumentException("未知部门: " + deptId);
            }
            rows.add(PoolAllowedDepartment.builder()
                .deptId(deptId)
                .deptName(node.name())
                .createdBy(operator)
                .build());
        }

        poolAllowedDepartmentRepository.deleteAll();
        poolAllowedDepartmentRepository.saveAll(rows);
    }

    /**
     * 清空白名单（功能停用，所有人重新允许入池）。
     */
    @Transactional
    public void clearWhitelist() {
        poolAllowedDepartmentRepository.deleteAll();
    }

    /**
     * 部门树不可用异常。strict 模式下调用方应中止操作而不是放行。
     */
    public static class DeptTreeUnavailableException extends RuntimeException {

        public DeptTreeUnavailableException(String message) {
            super(message);
        }

        public DeptTreeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
