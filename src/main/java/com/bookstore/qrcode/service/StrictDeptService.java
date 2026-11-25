package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Employee;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 严格同部门取人的「本县」范围判定服务。
 *
 * <p>本县 = 活码的县级根部门（{@code rootDeptId}）及其全部子孙部门。
 * 取人、补位、清退编排等场景统一通过本服务判断员工是否属于同一县。</p>
 *
 * <h3>两个方法的失败语义不同（下游务必区分）</h3>
 * <ul>
 *   <li>{@link #expandLocalDeptIds(Long)}：部门树拉取失败直接抛
 *       {@link DepartmentWhitelistService.DeptTreeUnavailableException}。
 *       清退 preview/apply 等破坏性编排据此整体中止，绝不拿「精确 ID」硬跑。</li>
 *   <li>{@link #isLocal(Employee, Long)}：内部捕获树不可用，降级为「精确 root ID 命中」
 *       并记 WARN。仅用于「放行类」判定（如手动加人的初筛）。</li>
 *   <li>{@link #isLocalOrUnknown(Employee, Long)}：三态判定，不吞树不可用，
 *       也不把「无部门/部门不可解析」当成外县。自动护栏（每日重置/复活/漂移统计）走此口径。</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 2026-11-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrictDeptService {

    private final DepartmentService departmentService;
    private final DepartmentWhitelistService whitelistService;
    private final ObjectMapper objectMapper;

    /**
     * 展开本县部门集合：root 本身 + 全部子孙部门。
     *
     * @param rootDeptId 县级根部门 ID
     * @return 不可变部门 ID 集合，至少包含 {@code rootDeptId}
     * @throws IllegalArgumentException rootDeptId 为 null，或不在部门树中
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树拉取失败或为空
     */
    public Set<Long> expandLocalDeptIds(Long rootDeptId) {
        if (rootDeptId == null) {
            throw new IllegalArgumentException("根部门 ID 不能为空");
        }
        Map<Long, DepartmentService.DeptNode> tree;
        try {
            tree = departmentService.loadDeptTree();
        } catch (Exception e) {
            throw new DepartmentWhitelistService.DeptTreeUnavailableException("部门树拉取失败", e);
        }
        if (tree == null || tree.isEmpty()) {
            throw new DepartmentWhitelistService.DeptTreeUnavailableException("部门树拉取失败: 部门树为空");
        }
        if (!tree.containsKey(rootDeptId)) {
            throw new IllegalArgumentException("未知部门: " + rootDeptId);
        }

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (DepartmentService.DeptNode node : tree.values()) {
            if (node.parentId() != null) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node.id());
            }
        }

        Set<Long> localIds = new LinkedHashSet<>();
        List<Long> stack = new ArrayList<>();
        stack.add(rootDeptId);
        while (!stack.isEmpty()) {
            Long current = stack.remove(stack.size() - 1);
            if (!localIds.add(current)) {
                continue;
            }
            List<Long> children = childrenByParent.get(current);
            if (children != null) {
                stack.addAll(children);
            }
        }
        return Set.copyOf(localIds);
    }

    /**
     * 判断员工是否属于本县。
     *
     * <p>部门树不可用时降级为「精确 root ID 命中」并记 WARN（不抛异常），
     * 保证日常护栏在最坏情况下只是少放人，不会跨县放人。</p>
     *
     * @param emp        员工记录
     * @param rootDeptId 县级根部门 ID
     * @return 员工任意部门命中本县集合时为 true；emp 或 rootDeptId 为 null 时为 false
     * @throws IllegalArgumentException rootDeptId 不在部门树中（未知部门不静默放行）
     */
    public boolean isLocal(Employee emp, Long rootDeptId) {
        if (emp == null || rootDeptId == null) {
            return false;
        }
        Set<Long> localIds;
        try {
            localIds = expandLocalDeptIds(rootDeptId);
        } catch (DepartmentWhitelistService.DeptTreeUnavailableException e) {
            log.warn("部门树不可用，isLocal 降级为精确部门 ID 判定: rootDeptId={}", rootDeptId, e);
            localIds = Set.of(rootDeptId);
        }
        return whitelistService.isEmployeeAllowed(emp, localIds);
    }

    /**
     * 严格护栏用三态判定：{@code true}=本县，{@code false}=确认外县，
     * {@code null}=无法判定（员工无档 / 部门为空 / JSON 不可解析 / 部门数组为空）。
     *
     * <p>与 {@link #isLocal(Employee, Long)} 的区别：不吞部门树不可用（原样抛出），
     * 也不把「无部门」当成外县——调用方可据此 fail-open，避免误撤无法判定归属的员工。</p>
     *
     * @param emp        员工记录，可为 null
     * @param rootDeptId 县级根部门 ID
     * @return 见方法说明
     * @throws IllegalArgumentException root 为 null 或不在部门树
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用
     */
    public Boolean isLocalOrUnknown(Employee emp, Long rootDeptId) {
        Set<Long> localIds = expandLocalDeptIds(rootDeptId);
        if (emp == null) {
            return null;
        }
        String department = emp.getDepartment();
        if (department == null || department.isBlank()) {
            return null;
        }
        long[] deptIds;
        try {
            deptIds = objectMapper.readValue(department, long[].class);
        } catch (Exception e) {
            log.warn("严格同部门：员工部门 JSON 解析失败，视为无法判定 userid={}, department={}",
                emp.getUserid(), department, e);
            return null;
        }
        if (deptIds == null || deptIds.length == 0) {
            return null;
        }
        for (long deptId : deptIds) {
            if (localIds.contains(deptId)) {
                return true;
            }
        }
        return false;
    }
}
