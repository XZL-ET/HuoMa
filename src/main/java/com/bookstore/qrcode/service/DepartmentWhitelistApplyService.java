package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.dto.DepartmentWhitelistApplyResult;
import com.bookstore.qrcode.dto.DepartmentWhitelistPreview;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 部门准入：预览与确认应用编排。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>{@link #preview(List)} 只读计算影响面，不加锁、不写库；</li>
 *   <li>{@link #apply(List, String)} 持全局分布式锁，先在一个事务内保存白名单并移出无关池成员，
 *       再逐活码持轮换锁撤人（每个活码独立 REQUIRES_NEW 事务），最后触发企微异步同步与审计；</li>
 *   <li>{@link #countDriftedActiveReceptionists()} 统计当前白名单下仍在活码上的无关接待员数。</li>
 * </ul>
 *
 * <p>部门树在这里直接拉取并在编排层本地展开（含全部子孙、循环安全）。
 * 不使用 {@link DepartmentWhitelistService#loadAllowedDeptIds(boolean)}：
 * apply 时新白名单尚未写入，读旧表会得到错误的允许集合。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentWhitelistApplyService {

    private static final String GLOBAL_LOCK_KEY = "lock:pool:dept-whitelist";
    private static final Duration GLOBAL_LOCK_TTL = Duration.ofMinutes(10);
    private static final Duration ROTATE_LOCK_TTL = Duration.ofSeconds(30);

    private final DepartmentService departmentService;
    private final DepartmentWhitelistService whitelist;
    private final DepartmentWhitelistTxService txService;
    private final DepartmentWhitelistRemovalService removalService;
    private final GlobalAgentPoolRepository poolRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;
    private final OperationLogService operationLogService;
    private final AgentRotationService agentRotationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 只读预览把白名单切换为指定部门后的影响面。
     *
     * @param deptIds 白名单根部门 ID
     * @return 预览结果
     * @throws IllegalArgumentException 部门列表为空或包含未知部门
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用
     */
    public DepartmentWhitelistPreview preview(List<Long> deptIds) {
        validateNotEmpty(deptIds);
        boolean active = !deptIds.isEmpty();

        Map<Long, DepartmentService.DeptNode> tree = loadTreeOrThrow();
        Set<Long> allowed = expandAllowed(deptIds, tree);

        List<GlobalAgentPool> poolRows = poolRepo.findAll();
        List<QrAgent> allActive =
            qrAgentRepo.findByStatus(QrAgent.AgentStatus.active);
        // full 接待员同样会被 apply 撤下，预览必须一并展示，否则确认页漏报实际影响
        List<QrAgent> allFull = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);

        Set<String> userids = new LinkedHashSet<>();
        for (GlobalAgentPool row : poolRows) {
            userids.add(row.getAgentUserid());
        }
        for (QrAgent qa : allActive) {
            userids.add(qa.getAgentUserid());
        }
        for (QrAgent qa : allFull) {
            userids.add(qa.getAgentUserid());
        }
        Map<String, Employee> employees = loadEmployees(userids);

        List<DepartmentWhitelistPreview.PoolRemoval> poolRemovals = new ArrayList<>();
        for (GlobalAgentPool row : poolRows) {
            Employee employee = employees.get(row.getAgentUserid());
            if (!isAllowed(employee, allowed)) {
                poolRemovals.add(new DepartmentWhitelistPreview.PoolRemoval(
                    row.getAgentUserid(), displayName(employee, row.getAgentUserid()),
                    row.getDepartmentId(), row.getStatus().name()));
            }
        }

        Map<Long, List<QrAgent>> disallowedActiveByQr = new LinkedHashMap<>();
        Map<Long, List<QrAgent>> disallowedFullByQr = new LinkedHashMap<>();
        int serviceBindingsExempt = 0;
        for (QrAgent qa : allActive) {
            boolean employeeAllowed = isAllowed(employees.get(qa.getAgentUserid()), allowed);
            if (qa.getRole() == QrAgent.AgentRole.receptionist) {
                if (!employeeAllowed) {
                    disallowedActiveByQr
                        .computeIfAbsent(qa.getQrCodeId(), k -> new ArrayList<>())
                        .add(qa);
                }
            } else if (!employeeAllowed) {
                // service / dual 服务老师即使不命中白名单也不撤，仅计入豁免数
                serviceBindingsExempt++;
            }
        }
        for (QrAgent qa : allFull) {
            if (qa.getRole() == QrAgent.AgentRole.receptionist
                && !isAllowed(employees.get(qa.getAgentUserid()), allowed)) {
                disallowedFullByQr
                    .computeIfAbsent(qa.getQrCodeId(), k -> new ArrayList<>())
                    .add(qa);
            }
        }

        Set<Long> affectedQrIds = new LinkedHashSet<>(disallowedActiveByQr.keySet());
        affectedQrIds.addAll(disallowedFullByQr.keySet());
        Map<Long, String> schoolNames = loadSchoolNames(affectedQrIds);
        Map<Long, List<QrAgent>> allBindingsByQr = loadAllBindings(affectedQrIds);
        List<GlobalAgentPool> standbys = poolRows.stream()
            .filter(row -> row.getStatus() == GlobalAgentPool.PoolStatus.standby)
            .toList();

        List<DepartmentWhitelistPreview.QrRemoval> qrRemovals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Long qrCodeId : affectedQrIds) {
            List<QrAgent> doomedActive =
                disallowedActiveByQr.getOrDefault(qrCodeId, List.of());
            List<QrAgent> doomedFull =
                disallowedFullByQr.getOrDefault(qrCodeId, List.of());
            List<QrAgent> allOnQr = allBindingsByQr.getOrDefault(qrCodeId, List.of());

            long activeTotal = allOnQr.stream()
                .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.active)
                .count();
            // full 不占 active 名额，补位判断只看撤掉的 active 接待员
            boolean needsBackfill = !doomedActive.isEmpty()
                && activeTotal - doomedActive.size() < 1;

            Set<String> boundUserids = allOnQr.stream()
                .map(QrAgent::getAgentUserid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean poolCanBackfill = standbys.stream().anyMatch(pool ->
                !boundUserids.contains(pool.getAgentUserid())
                    && isAllowed(employees.get(pool.getAgentUserid()), allowed));

            List<DepartmentWhitelistPreview.Member> members = new ArrayList<>();
            for (QrAgent qa : doomedActive) {
                Employee employee = employees.get(qa.getAgentUserid());
                members.add(new DepartmentWhitelistPreview.Member(
                    qa.getAgentUserid(), displayName(employee, qa.getAgentUserid()),
                    firstDepartmentId(employee)));
            }
            for (QrAgent qa : doomedFull) {
                Employee employee = employees.get(qa.getAgentUserid());
                members.add(new DepartmentWhitelistPreview.Member(
                    qa.getAgentUserid(),
                    displayName(employee, qa.getAgentUserid()) + "（已满）",
                    firstDepartmentId(employee)));
            }

            String schoolName = schoolNames.getOrDefault(qrCodeId, String.valueOf(qrCodeId));
            qrRemovals.add(new DepartmentWhitelistPreview.QrRemoval(
                qrCodeId, schoolName, members, needsBackfill, poolCanBackfill));
            if (needsBackfill && !poolCanBackfill) {
                warnings.add("活码 " + qrCodeId + " 需补位但允许池无可补人员，应用时将跳过");
            }
        }

        List<QrAgent> allReceptionistBindings = new ArrayList<>(allActive);
        allReceptionistBindings.addAll(allFull);
        List<DepartmentWhitelistPreview.NoDeptEmployee> noDepartmentEmployees =
            collectNoDepartmentEmployees(poolRows, allReceptionistBindings, employees);
        if (!noDepartmentEmployees.isEmpty()) {
            warnings.add("存在 " + noDepartmentEmployees.size() + " 名员工无部门信息，将按不允许处理");
        }

        return new DepartmentWhitelistPreview(active, poolRemovals, qrRemovals,
            noDepartmentEmployees, serviceBindingsExempt, warnings);
    }

    /**
     * 确认应用新白名单：移出无关池成员、逐活码撤人并异步同步企微。
     *
     * @param deptIds  白名单根部门 ID
     * @param operator 操作人
     * @return 应用结果
     * @throws IllegalArgumentException 部门列表为空或包含未知部门
     * @throws IllegalStateException 已有部门准入操作在执行
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用
     */
    public DepartmentWhitelistApplyResult apply(List<Long> deptIds, String operator) {
        validateNotEmpty(deptIds);
        int allowedDeptCount = new LinkedHashSet<>(deptIds).size();

        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(GLOBAL_LOCK_KEY, lockValue, GLOBAL_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("已有部门准入操作在执行，请稍后重试");
        }

        try {
            Map<Long, DepartmentService.DeptNode> tree = loadTreeOrThrow();
            Set<Long> allowed = expandAllowed(deptIds, tree);

            int poolRemoved = txService.replaceWhitelistAndRemoveFromPool(
                deptIds, allowed, operator);

            List<QrAgent> activeReceptionists =
                qrAgentRepo.findByStatusAndRole(
                    QrAgent.AgentStatus.active, QrAgent.AgentRole.receptionist);
            List<QrAgent> fullReceptionists =
                qrAgentRepo.findByStatusAndRole(
                    QrAgent.AgentStatus.full, QrAgent.AgentRole.receptionist);

            Set<String> userids = new LinkedHashSet<>();
            activeReceptionists.forEach(qa -> userids.add(qa.getAgentUserid()));
            fullReceptionists.forEach(qa -> userids.add(qa.getAgentUserid()));
            Map<String, Employee> employees = loadEmployees(userids);

            Map<Long, Set<String>> disallowedActiveByQr = new LinkedHashMap<>();
            for (QrAgent qa : activeReceptionists) {
                if (!isAllowed(employees.get(qa.getAgentUserid()), allowed)) {
                    disallowedActiveByQr
                        .computeIfAbsent(qa.getQrCodeId(), k -> new LinkedHashSet<>())
                        .add(qa.getAgentUserid());
                }
            }
            List<QrAgent> fullDisallowed = fullReceptionists.stream()
                .filter(qa -> !isAllowed(employees.get(qa.getAgentUserid()), allowed))
                .toList();

            int activeRemoved = 0;
            int backfilled = 0;
            int qrCodesProcessed = 0;
            int qrCodesSkipped = 0;
            List<String> failures = new ArrayList<>();
            Set<Long> affectedQrIds = new LinkedHashSet<>();

            Map<Long, String> schoolNames = loadSchoolNames(disallowedActiveByQr.keySet());
            for (Map.Entry<Long, Set<String>> entry : disallowedActiveByQr.entrySet()) {
                Long qrCodeId = entry.getKey();
                String schoolName = schoolNames.get(qrCodeId);
                String qrLabel = schoolName == null || schoolName.isBlank()
                    ? "活码#" + qrCodeId
                    : "活码 " + schoolName;
                String rotateLockKey =
                    RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":rotate";
                String rotateLockValue = UUID.randomUUID().toString();
                Boolean rotateLocked = redisTemplate.opsForValue()
                    .setIfAbsent(rotateLockKey, rotateLockValue, ROTATE_LOCK_TTL);
                if (!Boolean.TRUE.equals(rotateLocked)) {
                    qrCodesSkipped++;
                    failures.add(qrLabel + " 轮换进行中，未处理");
                    continue;
                }
                try {
                    DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
                        removalService.removeReceptionistsFromQr(qrCodeId, entry.getValue());
                    if (outcome.skipped()) {
                        qrCodesSkipped++;
                        failures.add(qrLabel + "：" + outcome.skipReason());
                    } else {
                        qrCodesProcessed++;
                        activeRemoved += outcome.removed();
                        backfilled += outcome.backfilled();
                        if (outcome.removed() > 0 || outcome.backfilled() > 0) {
                            affectedQrIds.add(qrCodeId);
                        }
                    }
                } catch (RuntimeException e) {
                    qrCodesSkipped++;
                    failures.add("活码 " + qrCodeId + " 处理失败：" + e.getMessage());
                    log.warn("部门准入：活码 {} 撤人失败", qrCodeId, e);
                } finally {
                    releaseLock(rotateLockKey, rotateLockValue);
                }
            }

            int fullRemoved = 0;
            if (!fullDisallowed.isEmpty()) {
                List<Long> fullIds = fullDisallowed.stream().map(QrAgent::getId).toList();
                try {
                    fullRemoved = removalService.removeFullReceptionists(fullIds);
                    if (fullRemoved > 0) {
                        fullDisallowed.forEach(qa -> affectedQrIds.add(qa.getQrCodeId()));
                    }
                } catch (RuntimeException e) {
                    // 撤 full 接待员失败不应吞掉整次应用：白名单已写入，后续同步与审计仍需执行，
                    // 失败明细交由页面「未处理清单」提示用户重新应用
                    log.warn("部门准入：撤下 full 接待员失败", e);
                    failures.add("full 接待员撤下失败：" + e.getMessage());
                }
            }

            for (Long qrCodeId : affectedQrIds) {
                try {
                    agentRotationService.syncQrCodeToWechatAsync(qrCodeId);
                } catch (RuntimeException e) {
                    log.warn("部门准入：触发活码 {} 企微同步失败", qrCodeId, e);
                }
            }

            String description = String.format(
                "部门准入应用：白名单部门 %d 个，池移除 %d 人，活码撤 active %d 人、full %d 人，"
                    + "补位 %d 人，处理活码 %d 个，跳过 %d 个",
                allowedDeptCount, poolRemoved, activeRemoved, fullRemoved,
                backfilled, qrCodesProcessed, qrCodesSkipped);
            operationLogService.log(operator, "dept_whitelist_apply",
                "agent_pool_department", "-", description);

            return new DepartmentWhitelistApplyResult(allowedDeptCount, poolRemoved,
                activeRemoved, fullRemoved, backfilled, qrCodesProcessed,
                qrCodesSkipped, failures);
        } finally {
            releaseLock(GLOBAL_LOCK_KEY, lockValue);
        }
    }

    /**
     * 统计当前白名单下仍在活码上担任接待员的无关人数。
     *
     * <p>部门树不可用时返回 0：此时允许集合退化为精确白名单 ID，
     * 子部门接待员会被误判为「无关」，据此展示漂移提示会误导运营。</p>
     *
     * @return 漂移人数；白名单未启用或部门树不可用时返回 0
     */
    public long countDriftedActiveReceptionists() {
        DepartmentWhitelistService.AllowedDeptResult deptAccess =
            whitelist.loadAllowedDeptIdsWithTreeStatus();
        if (!deptAccess.isEnabled()) {
            return 0L;
        }
        if (!deptAccess.treeAvailable()) {
            log.warn("部门树不可用，跳过无关接待员漂移统计");
            return 0L;
        }
        Set<Long> allowed = deptAccess.ids();
        List<QrAgent> activeReceptionists =
            qrAgentRepo.findByStatusAndRole(
                QrAgent.AgentStatus.active, QrAgent.AgentRole.receptionist);
        if (activeReceptionists.isEmpty()) {
            return 0L;
        }
        Set<String> userids = activeReceptionists.stream()
            .map(QrAgent::getAgentUserid)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Employee> employees = loadEmployees(userids);
        return activeReceptionists.stream()
            .filter(qa -> !isAllowed(employees.get(qa.getAgentUserid()), allowed))
            .count();
    }

    // ==================== 私有辅助 ====================

    private void validateNotEmpty(List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个部门");
        }
    }

    private Map<Long, DepartmentService.DeptNode> loadTreeOrThrow() {
        Map<Long, DepartmentService.DeptNode> tree;
        try {
            tree = departmentService.loadDeptTree();
        } catch (Exception e) {
            throw new DepartmentWhitelistService.DeptTreeUnavailableException(
                "部门树拉取失败", e);
        }
        if (tree == null || tree.isEmpty()) {
            throw new DepartmentWhitelistService.DeptTreeUnavailableException(
                "部门树拉取失败: 部门树为空");
        }
        return tree;
    }

    /**
     * 校验根部门存在并本地展开为「根 + 全部子孙」，循环安全。
     */
    private Set<Long> expandAllowed(
        List<Long> deptIds, Map<Long, DepartmentService.DeptNode> tree) {

        Set<Long> roots = new LinkedHashSet<>(deptIds);
        for (Long deptId : roots) {
            if (!tree.containsKey(deptId)) {
                throw new IllegalArgumentException("未知部门: " + deptId);
            }
        }

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (DepartmentService.DeptNode node : tree.values()) {
            if (node.parentId() != null) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>())
                    .add(node.id());
            }
        }

        Set<Long> allowed = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
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

    private Map<String, Employee> loadEmployees(Collection<String> userids) {
        if (userids == null || userids.isEmpty()) {
            return Map.of();
        }
        Map<String, Employee> employees = new HashMap<>();
        for (Employee employee : employeeRepo.findByUseridIn(userids)) {
            employees.put(employee.getUserid(), employee);
        }
        return employees;
    }

    private Map<Long, List<QrAgent>> loadAllBindings(Collection<Long> qrCodeIds) {
        if (qrCodeIds == null || qrCodeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<QrAgent>> byQr = new HashMap<>();
        for (QrAgent qa : qrAgentRepo.findByQrCodeIdIn(qrCodeIds)) {
            byQr.computeIfAbsent(qa.getQrCodeId(), k -> new ArrayList<>()).add(qa);
        }
        return byQr;
    }

    private Map<Long, String> loadSchoolNames(Collection<Long> qrCodeIds) {
        if (qrCodeIds == null || qrCodeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (QrCode qr : qrCodeRepo.findAllById(qrCodeIds)) {
            names.put(qr.getId(), qr.getSchoolName());
        }
        return names;
    }

    private List<DepartmentWhitelistPreview.NoDeptEmployee> collectNoDepartmentEmployees(
        List<GlobalAgentPool> poolRows, List<QrAgent> allActive,
        Map<String, Employee> employees) {

        Set<String> userids = new LinkedHashSet<>();
        for (GlobalAgentPool row : poolRows) {
            userids.add(row.getAgentUserid());
        }
        for (QrAgent qa : allActive) {
            if (qa.getRole() == QrAgent.AgentRole.receptionist) {
                userids.add(qa.getAgentUserid());
            }
        }

        List<DepartmentWhitelistPreview.NoDeptEmployee> result = new ArrayList<>();
        for (String userid : userids) {
            Employee employee = employees.get(userid);
            String department = employee == null ? null : employee.getDepartment();
            if (department == null || department.isBlank()) {
                result.add(new DepartmentWhitelistPreview.NoDeptEmployee(
                    userid, displayName(employee, userid)));
            }
        }
        return result;
    }

    private boolean isAllowed(Employee employee, Set<Long> allowedDeptIds) {
        return employee != null && whitelist.isEmployeeAllowed(employee, allowedDeptIds);
    }

    private String displayName(Employee employee, String fallbackUserid) {
        if (employee == null || employee.getName() == null || employee.getName().isBlank()) {
            return fallbackUserid;
        }
        return employee.getName();
    }

    /** 取员工部门 JSON 数组的首个部门 ID，用于展示；解析失败返回 null。 */
    private Long firstDepartmentId(Employee employee) {
        if (employee == null) {
            return null;
        }
        String department = employee.getDepartment();
        if (department == null || department.isBlank()) {
            return null;
        }
        try {
            long[] deptIds = objectMapper.readValue(department, long[].class);
            return deptIds != null && deptIds.length > 0 ? deptIds[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void releaseLock(String key, String value) {
        try {
            redisTemplate.execute(RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(key), value);
        } catch (Exception e) {
            log.warn("部门准入：释放分布式锁失败，等待 TTL 过期 key={}", key, e);
        }
    }
}
