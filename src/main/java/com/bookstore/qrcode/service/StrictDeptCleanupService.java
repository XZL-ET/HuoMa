package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.dto.StrictDeptCleanupPreview;
import com.bookstore.qrcode.dto.StrictDeptCleanupResult;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 活码「严格同部门」跨县接待员清退编排。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>{@link #preview(Set)} 只读计算影响面：不加锁、不 save/delete、不调用任何
 *       {@code takeStandby*}（该方法会写库、加行锁、懒清理，违反「确认前不写库」）；</li>
 *   <li>{@link #apply(Set, String)} 持全局锁，先按当前库状态整体重算（不复用预览快照），
 *       再逐码持轮换锁调用 {@link DepartmentWhitelistRemovalService} 撤人
 *       （强制本县补位、每个活码独立 REQUIRES_NEW 事务），最后异步同步企微并写审计；</li>
 *   <li>{@link #countDriftedActiveReceptionists()} 统计仍在活码上的外县非豁免接待员数。</li>
 * </ul>
 *
 * <h3>扫描范围</h3>
 * <p>以 {@code QrAgent} 的 active + full 绑定为源按活码分组（R4），因此
 * {@code QrCode.status = full/no_agent} 的码也会被覆盖；{@code paused} 码跳过不改。
 * 只处理 {@code role = receptionist}，服务老师/双角色一律不动。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrictDeptCleanupService {

    private static final String GLOBAL_LOCK_KEY = "lock:pool:strict-dept-cleanup";
    private static final Duration GLOBAL_LOCK_TTL = Duration.ofMinutes(10);
    /** 与 {@link DepartmentWhitelistApplyService} 保持一致。 */
    private static final Duration ROTATE_LOCK_TTL = Duration.ofSeconds(30);

    private static final String REMOVAL_REASON = "严格同部门移出";
    private static final String UNKNOWN_DISTRICT = "未知";
    private static final String SYSTEM_OPERATOR = "system";

    private final StrictDeptConfigService strictDeptConfig;
    private final StrictDeptService strictDeptService;
    private final DepartmentWhitelistService whitelist;
    private final DepartmentWhitelistRemovalService removalService;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final PoolAllowedDepartmentRepository allowedDeptRepo;
    private final OperationLogService operationLogService;
    private final AgentRotationService agentRotationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ==================== 对外接口 ====================

    /**
     * 只读预览清退影响面。
     *
     * @param rootDeptFilter 仅处理这些根部门的活码；{@code null}/空表示全部区县
     * @return 预览结果
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用（整体中止）
     */
    public StrictDeptCleanupPreview preview(Set<Long> rootDeptFilter) {
        boolean strictModeEnabled = strictDeptConfig.isStrictModeEnabled();
        Set<String> exemptUserids = strictDeptConfig.getExemptUserids();
        List<String> warnings = new ArrayList<>();

        ScanResult scan = scan(rootDeptFilter, exemptUserids, warnings);
        for (CodeWork work : scan.codes()) {
            if (work.skipReason() != null) {
                warnings.add(label(work) + "：" + work.skipReason());
            }
        }

        List<StrictDeptCleanupPreview.QrPlan> plans = scan.codes().stream()
            .map(this::toPlan)
            .toList();
        return new StrictDeptCleanupPreview(strictModeEnabled, exemptUserids,
            scan.codeCount(), scan.receptionistCount(), scan.districts(), plans, warnings);
    }

    /**
     * 确认执行清退：按当前库状态重算，逐码撤人并同步企微。
     *
     * @param rootDeptFilter 仅处理这些根部门的活码；{@code null}/空表示全部区县
     * @param operator       操作人；空白时记为 {@code system}
     * @return 执行结果
     * @throws IllegalStateException 已有清退任务执行中
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用（写库前整体中止）
     */
    public StrictDeptCleanupResult apply(Set<Long> rootDeptFilter, String operator) {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(GLOBAL_LOCK_KEY, lockValue, GLOBAL_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("严格同部门清退正在执行，请稍后");
        }
        try {
            String op = (operator == null || operator.isBlank()) ? SYSTEM_OPERATOR : operator;
            Set<String> exemptUserids = strictDeptConfig.getExemptUserids();
            // 三类信息分开累计：提示（纯信息）、跳过（未处理）、真实失败（异常）
            List<String> hints = new ArrayList<>();
            List<String> skippedReasons = new ArrayList<>();
            List<String> realFailures = new ArrayList<>();

            // 写库前先整体重算：树不可用时此处抛异常，尚未产生任何写入
            ScanResult scan = scan(rootDeptFilter, exemptUserids, hints);

            int codesProcessed = 0;
            int codesSkipped = 0;
            int activeRemoved = 0;
            int fullRemoved = 0;
            int backfilled = 0;
            Set<Long> changedQrIds = new LinkedHashSet<>();

            for (CodeWork work : scan.codes()) {
                String qrLabel = label(work);
                if (work.skipReason() != null) {
                    codesSkipped++;
                    skippedReasons.add(qrLabel + "：" + work.skipReason());
                    continue;
                }

                String rotateLockKey =
                    RedisConfig.ROTATE_LOCK_PREFIX + work.qrCodeId() + ":rotate";
                String rotateLockValue = UUID.randomUUID().toString();
                Boolean rotateLocked = redisTemplate.opsForValue()
                    .setIfAbsent(rotateLockKey, rotateLockValue, ROTATE_LOCK_TTL);
                if (!Boolean.TRUE.equals(rotateLocked)) {
                    codesSkipped++;
                    skippedReasons.add(qrLabel + " 轮换进行中，未处理");
                    continue;
                }
                try {
                    boolean codeProcessed = false;
                    boolean codeSkipped = false;
                    boolean codeChanged = false;

                    if (!work.doomedActive().isEmpty()) {
                        Set<String> disallowed = work.doomedActive().stream()
                            .map(QrAgent::getAgentUserid)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
                            removalService.removeReceptionistsFromQr(
                                work.qrCodeId(), disallowed, REMOVAL_REASON, true);
                        if (outcome.skipped()) {
                            codeSkipped = true;
                            skippedReasons.add(qrLabel + "：" + outcome.skipReason());
                        } else {
                            codeProcessed = true;
                            activeRemoved += outcome.removed();
                            backfilled += outcome.backfilled();
                            if (outcome.removed() > 0 || outcome.backfilled() > 0) {
                                codeChanged = true;
                            }
                        }
                    }

                    if (!work.doomedFull().isEmpty()) {
                        try {
                            List<Long> fullIds = work.doomedFull().stream()
                                .map(QrAgent::getId)
                                .toList();
                            int removed = removalService.removeFullReceptionists(fullIds);
                            fullRemoved += removed;
                            if (removed > 0) {
                                codeChanged = true;
                                if (!codeProcessed && !codeSkipped) {
                                    codeProcessed = true;
                                }
                            }
                        } catch (RuntimeException e) {
                            log.warn("严格同部门清退：活码 {} 撤 full 接待员失败",
                                work.qrCodeId(), e);
                            realFailures.add(qrLabel + " full 接待员撤下失败：" + e.getMessage());
                        }
                    }

                    if (codeSkipped) {
                        codesSkipped++;
                    } else if (codeProcessed) {
                        codesProcessed++;
                    }
                    if (codeChanged) {
                        changedQrIds.add(work.qrCodeId());
                    }
                } catch (RuntimeException e) {
                    // 处理异常属真实失败，不计入跳过码
                    realFailures.add(qrLabel + " 处理失败：" + e.getMessage());
                    log.warn("严格同部门清退：活码 {} 处理失败", work.qrCodeId(), e);
                } finally {
                    releaseLock(rotateLockKey, rotateLockValue);
                }
            }

            for (Long qrCodeId : changedQrIds) {
                try {
                    agentRotationService.syncQrCodeToWechatAsync(qrCodeId);
                } catch (RuntimeException e) {
                    log.warn("严格同部门清退：触发活码 {} 企微同步失败", qrCodeId, e);
                    realFailures.add("活码#" + qrCodeId + " 企微同步失败：" + e.getMessage());
                }
            }

            StringBuilder description = new StringBuilder(String.format(
                "严格同部门清退：处理 %d 码，跳过 %d 码，失败 %d 条，"
                    + "撤 active %d 人、full %d 人，补位 %d 人",
                codesProcessed, codesSkipped, realFailures.size(),
                activeRemoved, fullRemoved, backfilled));
            if (!hints.isEmpty()) {
                description.append("，提示 ").append(hints.size()).append(" 条");
            }
            operationLogService.log(op, "strict_dept_cleanup", "qr_code", "-",
                description.toString());

            // 结果 DTO 保留「未处理清单」语义：跳过原因在前，真实失败在后
            List<String> failures = new ArrayList<>(skippedReasons);
            failures.addAll(realFailures);
            return new StrictDeptCleanupResult(codesProcessed, codesSkipped,
                activeRemoved, fullRemoved, backfilled, failures);
        } finally {
            releaseLock(GLOBAL_LOCK_KEY, lockValue);
        }
    }

    /**
     * 统计仍在活码上担任接待员的外县非豁免人数。
     *
     * <p>不判断严格模式总开关（由巡检调用方控制）；排除 paused 码、部门为空的码、
     * 根部门不在全局白名单的码（白名单启用时）、豁免 userid 与无员工档案的绑定，
     * 以及「员工部门缺失/不可解析」这类无法判定归属的绑定（不计为漂移）。</p>
     *
     * <p>部门树不可用时抛出 {@link DepartmentWhitelistService.DeptTreeUnavailableException}，
     * 由巡检调用方决定保持告警状态不变——不能把「树坏了」误当作「全员外县」。</p>
     *
     * @return 外县 active 接待员绑定数
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用
     */
    public long countDriftedActiveReceptionists() {
        List<QrAgent> activeReceptionists = qrAgentRepo.findByStatusAndRole(
            QrAgent.AgentStatus.active, QrAgent.AgentRole.receptionist);
        if (activeReceptionists.isEmpty()) {
            return 0L;
        }
        Set<String> exemptUserids = strictDeptConfig.getExemptUserids();
        List<QrAgent> candidates = activeReceptionists.stream()
            .filter(qa -> !exemptUserids.contains(qa.getAgentUserid()))
            .toList();
        if (candidates.isEmpty()) {
            return 0L;
        }

        Set<Long> qrCodeIds = candidates.stream()
            .map(QrAgent::getQrCodeId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, QrCode> qrById = new HashMap<>();
        for (QrCode qr : qrCodeRepo.findAllById(qrCodeIds)) {
            qrById.put(qr.getId(), qr);
        }

        Set<String> userids = candidates.stream()
            .map(QrAgent::getAgentUserid)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Employee> employees = loadEmployees(userids);

        Set<Long> whitelistIds = loadWhitelistRootIds();
        boolean whitelistEnabled = !whitelistIds.isEmpty();

        long drifted = 0L;
        for (QrAgent qa : candidates) {
            QrCode qr = qrById.get(qa.getQrCodeId());
            if (qr == null || qr.getStatus() == QrCode.QrCodeStatus.paused) {
                continue;
            }
            Long rootDeptId = qr.getDepartmentId();
            if (rootDeptId == null) {
                continue;
            }
            if (whitelistEnabled && !whitelistIds.contains(rootDeptId)) {
                continue;
            }
            Employee employee = employees.get(qa.getAgentUserid());
            if (employee == null) {
                continue;
            }
            Boolean local;
            try {
                local = strictDeptService.isLocalOrUnknown(employee, rootDeptId);
            } catch (IllegalArgumentException e) {
                // 根部门在全局白名单里但不在企微部门树：无法判定，不计入漂移
                log.warn("严格同部门漂移统计：活码 {} 根部门 {} 不在部门树，已跳过",
                    qr.getId(), rootDeptId);
                continue;
            }
            // DeptTreeUnavailableException 不捕获：向上抛给巡检，保持告警状态不变
            if (Boolean.FALSE.equals(local)) {
                drifted++;
            } else if (local == null) {
                log.warn("严格同部门漂移统计：员工 {} 部门缺失或无法解析，跳过漂移计数",
                    qa.getAgentUserid());
            }
        }
        return drifted;
    }

    // ==================== 扫描与计算 ====================

    /**
     * 按当前库状态扫描 active + full 绑定，生成逐码计划。
     *
     * @param hints 追加纯提示（员工档案缺失、白名单未启用）；跳过原因由调用方按需另行采集
     * @throws DepartmentWhitelistService.DeptTreeUnavailableException 部门树不可用
     */
    private ScanResult scan(Set<Long> rootDeptFilter, Set<String> exemptUserids,
                            List<String> hints) {
        Map<Long, List<QrAgent>> bindingsByQr = new TreeMap<>();
        addBindings(bindingsByQr, qrAgentRepo.findByStatus(QrAgent.AgentStatus.active));
        addBindings(bindingsByQr, qrAgentRepo.findByStatus(QrAgent.AgentStatus.full));

        Map<Long, QrCode> qrById = new HashMap<>();
        for (QrCode qr : qrCodeRepo.findAllById(bindingsByQr.keySet())) {
            qrById.put(qr.getId(), qr);
        }

        Set<Long> whitelistIds = loadWhitelistRootIds();
        boolean whitelistEnabled = !whitelistIds.isEmpty();
        if (!whitelistEnabled) {
            hints.add("全局员工部门白名单未启用，将不按白名单过滤活码部门");
        }

        List<GlobalAgentPool> poolRows = poolRepo.findAll();
        List<GlobalAgentPool> standbys = poolRows.stream()
            .filter(row -> row.getStatus() == GlobalAgentPool.PoolStatus.standby)
            .toList();

        Set<String> userids = new LinkedHashSet<>();
        bindingsByQr.values().forEach(list ->
            list.forEach(qa -> userids.add(qa.getAgentUserid())));
        poolRows.forEach(row -> userids.add(row.getAgentUserid()));
        Map<String, Employee> employees = loadEmployees(userids);

        boolean filterActive = rootDeptFilter != null && !rootDeptFilter.isEmpty();
        List<CodeWork> codeWorks = new ArrayList<>();
        Map<String, DistrictAccumulator> districts = new TreeMap<>();
        int codeCount = 0;
        int receptionistCount = 0;

        for (Map.Entry<Long, List<QrAgent>> entry : bindingsByQr.entrySet()) {
            Long qrCodeId = entry.getKey();
            List<QrAgent> bindings = entry.getValue();
            QrCode qr = qrById.get(qrCodeId);

            if (filterActive && qr != null) {
                Long deptId = qr.getDepartmentId();
                if (deptId == null || !rootDeptFilter.contains(deptId)) {
                    continue;
                }
            }
            if (qr == null) {
                codeWorks.add(skipWork(qrCodeId, "活码不存在"));
                continue;
            }
            if (qr.getStatus() == QrCode.QrCodeStatus.paused) {
                codeWorks.add(skipWork(qrCodeId, "活码已暂停"));
                continue;
            }
            Long rootDeptId = qr.getDepartmentId();
            if (rootDeptId == null) {
                codeWorks.add(skipWork(qrCodeId, "活码未设置所属部门"));
                continue;
            }
            if (whitelistEnabled && !whitelistIds.contains(rootDeptId)) {
                codeWorks.add(skipWork(qrCodeId,
                    "根部门 " + rootDeptId + " 不在全局员工部门白名单"));
                continue;
            }

            Set<Long> localDeptIds;
            try {
                localDeptIds = strictDeptService.expandLocalDeptIds(rootDeptId);
            } catch (IllegalArgumentException e) {
                codeWorks.add(skipWork(qrCodeId,
                    "根部门 " + rootDeptId + " 不在部门树"));
                continue;
            }

            List<QrAgent> doomedActive = new ArrayList<>();
            List<QrAgent> doomedFull = new ArrayList<>();
            for (QrAgent qa : bindings) {
                if (qa.getRole() != QrAgent.AgentRole.receptionist) {
                    continue;
                }
                String userid = qa.getAgentUserid();
                if (exemptUserids.contains(userid)) {
                    continue;
                }
                Employee employee = employees.get(userid);
                if (employee == null) {
                    hints.add("活码 " + qrCodeId + " 上接待员 " + userid
                        + " 缺少员工记录，保留不撤");
                    continue;
                }
                // 与 R6 对齐：部门无法判定（空白/坏 JSON/空数组）时 fail-open 保留，
                // 只有「部门集合非空且与本县无交集」才算确认外县
                if (!hasKnownDepartments(employee)) {
                    hints.add("活码 " + qrCodeId + " 上接待员 " + userid
                        + " 部门缺失或无法解析，保留不撤");
                    continue;
                }
                if (whitelist.isEmployeeAllowed(employee, localDeptIds)) {
                    continue;
                }
                if (qa.getStatus() == QrAgent.AgentStatus.active) {
                    doomedActive.add(qa);
                } else {
                    doomedFull.add(qa);
                }
            }

            if (doomedActive.isEmpty() && doomedFull.isEmpty()) {
                continue;
            }

            long activeTotal = bindings.stream()
                .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.active)
                .count();
            boolean needsBackfill = !doomedActive.isEmpty()
                && activeTotal - doomedActive.size() < 1;

            Set<String> boundUserids = bindings.stream()
                .map(QrAgent::getAgentUserid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean localStandbyAvailable = standbys.stream().anyMatch(pool -> {
                if (boundUserids.contains(pool.getAgentUserid())) {
                    return false;
                }
                Employee employee = employees.get(pool.getAgentUserid());
                return employee != null
                    && whitelist.isEmployeeAllowed(employee, localDeptIds);
            });

            List<StrictDeptCleanupPreview.Member> members = new ArrayList<>();
            for (QrAgent qa : doomedActive) {
                members.add(toMember(qa.getAgentUserid(), employees));
            }
            for (QrAgent qa : doomedFull) {
                members.add(toMember(qa.getAgentUserid(), employees));
            }

            codeWorks.add(new CodeWork(qr, qrCodeId, doomedActive, doomedFull,
                members, needsBackfill, localStandbyAvailable, null));
            codeCount++;
            receptionistCount += members.size();

            String district = districtOf(qr);
            DistrictAccumulator acc = districts.computeIfAbsent(district,
                key -> new DistrictAccumulator());
            acc.codeCount++;
            acc.memberCount += members.size();
        }

        List<StrictDeptCleanupPreview.DistrictGroup> districtGroups = districts.entrySet()
            .stream()
            .map(e -> new StrictDeptCleanupPreview.DistrictGroup(
                e.getKey(), e.getValue().codeCount, e.getValue().memberCount))
            .toList();
        return new ScanResult(codeWorks, codeCount, receptionistCount, districtGroups);
    }

    private void addBindings(Map<Long, List<QrAgent>> byQr, List<QrAgent> bindings) {
        for (QrAgent qa : bindings) {
            byQr.computeIfAbsent(qa.getQrCodeId(), key -> new ArrayList<>()).add(qa);
        }
        byQr.values().forEach(list -> list.sort(
            Comparator.comparing(QrAgent::getId, Comparator.nullsLast(Comparator.naturalOrder()))));
    }

    private CodeWork skipWork(Long qrCodeId, String reason) {
        return new CodeWork(null, qrCodeId, List.of(), List.of(), List.of(),
            false, false, reason);
    }

    private StrictDeptCleanupPreview.QrPlan toPlan(CodeWork work) {
        QrCode qr = work.qr();
        return new StrictDeptCleanupPreview.QrPlan(
            work.qrCodeId(),
            qr == null ? null : qr.getSchoolName(),
            qr == null ? UNKNOWN_DISTRICT : districtOf(qr),
            List.copyOf(work.members()),
            work.needsBackfill(),
            work.localStandbyAvailable(),
            work.skipReason());
    }

    private StrictDeptCleanupPreview.Member toMember(String userid,
                                                     Map<String, Employee> employees) {
        Employee employee = employees.get(userid);
        return new StrictDeptCleanupPreview.Member(
            userid,
            displayName(employee, userid),
            firstDepartmentId(employee),
            false);
    }

    // ==================== 私有辅助 ====================

    private Set<Long> loadWhitelistRootIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (PoolAllowedDepartment row : allowedDeptRepo.findAll()) {
            ids.add(row.getDeptId());
        }
        return ids;
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

    private String districtOf(QrCode qr) {
        String district = qr.getRegionDistrict();
        return district == null || district.isBlank() ? UNKNOWN_DISTRICT : district;
    }

    private String label(CodeWork work) {
        QrCode qr = work.qr();
        if (qr == null || qr.getSchoolName() == null || qr.getSchoolName().isBlank()) {
            return "活码#" + work.qrCodeId();
        }
        return "活码 " + qr.getSchoolName();
    }

    private String displayName(Employee employee, String fallbackUserid) {
        if (employee == null || employee.getName() == null || employee.getName().isBlank()) {
            return fallbackUserid;
        }
        return employee.getName();
    }

    /**
     * 员工部门是否可判定：部门字段非空、可解析为非空 long 数组时为 true。
     *
     * <p>返回 false 表示无法判断员工属于哪个县，调用方应保留该员工（fail-open），
     * 不能把「没数据」当成「外县」。</p>
     */
    private boolean hasKnownDepartments(Employee emp) {
        if (emp == null) {
            return false;
        }
        String department = emp.getDepartment();
        if (department == null || department.isBlank()) {
            return false;
        }
        try {
            long[] deptIds = objectMapper.readValue(department, long[].class);
            return deptIds != null && deptIds.length > 0;
        } catch (Exception e) {
            return false;
        }
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
            redisTemplate.execute(RedisConfig.SAFE_UNLOCK_SCRIPT, List.of(key), value);
        } catch (Exception e) {
            log.warn("严格同部门清退：释放分布式锁失败，等待 TTL 过期 key={}", key, e);
        }
    }

    // ==================== 内部结构 ====================

    private record CodeWork(
        QrCode qr,
        Long qrCodeId,
        List<QrAgent> doomedActive,
        List<QrAgent> doomedFull,
        List<StrictDeptCleanupPreview.Member> members,
        boolean needsBackfill,
        boolean localStandbyAvailable,
        String skipReason) {}

    private record ScanResult(
        List<CodeWork> codes,
        int codeCount,
        int receptionistCount,
        List<StrictDeptCleanupPreview.DistrictGroup> districts) {}

    private static final class DistrictAccumulator {
        private int codeCount;
        private int memberCount;
    }
}
