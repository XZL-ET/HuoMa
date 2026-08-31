package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工同步服务 —— 企微通讯录 ↔ 本地 Employee 表 ↔ 全局员工池。
 *
 * <p>三层数据流：
 * <ol>
 *   <li><b>企微 → Employee 表：</b>每 30 分钟全量同步，新增/更新/标记离职</li>
 *   <li><b>Employee 表 → 全局池：</b>按需触发（手动按钮 / 巡检自动补人），
 *       将在职但不在池中的员工写入 {@link GlobalAgentPool}</li>
 *   <li><b>离职清理：</b>同步发现离职员工时，同时从全局池移除</li>
 * </ol>
 *
 * @author Bookstore Dev
 * @since 1.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeSyncService {

    private final EmployeeRepository employeeRepo;
    private final WecomApiClient wecomApi;
    private final GlobalAgentPoolService poolService;
    private final GlobalAgentPoolRepository poolRepo;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    /**
     * 应用启动后回填存量池记录的 departmentId，确保部门匹配立即可用。
     * 用 ApplicationReadyEvent 替代 PostConstruct，确保事务代理已就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initBackfillDepartmentIds() {
        int backfilled = backfillPoolDepartmentIds();
        if (backfilled > 0) {
            log.info("启动时回填 departmentId 完成: {} 条记录", backfilled);
        }
    }

    /**
     * 定时全量同步 — 每 30 分钟执行一次（偏移 7 分钟避免整点争抢资源）。
     */
    @Scheduled(cron = "0 7,37 * * * *")
    public void scheduledSync() {
        log.info("定时员工同步开始");
        try {
            int count = syncFromWecom();
            log.info("定时员工同步完成: {} 人", count);
        } catch (Exception e) {
            log.error("定时员工同步失败", e);
        }
    }

    /**
     * 全量同步企微员工到本地 Employee 表。
     *
     * @return 同步的员工总数
     */
    @Transactional
    public int syncFromWecom() {
        JsonNode resp = wecomApi.getUserList();

        if (!resp.has("userlist") || !resp.get("userlist").isArray()) {
            log.warn("企微员工列表为空或格式异常");
            return 0;
        }

        List<String> activeUserIds = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        Map<String, Integer> wecomStatusMap = new HashMap<>();

        for (JsonNode u : resp.get("userlist")) {
            String userid = u.get("userid").asText();
            String name = u.has("name") ? u.get("name").asText() : "";
            String dept = u.has("department") ? u.get("department").toString() : null;
            Integer wechatStatus = u.has("status") ? u.get("status").asInt() : null;

            if (userid.isEmpty()) continue;
            activeUserIds.add(userid);
            wecomStatusMap.put(userid, wechatStatus);

            Optional<Employee> existing = employeeRepo.findByUserid(userid);
            if (existing.isPresent()) {
                Employee emp = existing.get();
                boolean changed = false;
                if (!name.equals(emp.getName())) {
                    emp.setName(name);
                    changed = true;
                }
                if (!emp.getActive()) {
                    emp.setActive(true);
                    changed = true;
                }
                if (dept != null && !dept.equals(emp.getDepartment())) {
                    emp.setDepartment(dept);
                    changed = true;
                }
                if (wechatStatus != null && !wechatStatus.equals(emp.getWechatStatus())) {
                    emp.setWechatStatus(wechatStatus);
                    changed = true;
                }
                if (changed) {
                    emp.setLastSyncTime(LocalDateTime.now());
                    employeeRepo.save(emp);
                    updated++;
                }
            } else {
                Employee emp = Employee.builder()
                    .userid(userid)
                    .name(name)
                    .department(dept)
                    .active(true)
                    .wechatStatus(wechatStatus)
                    .lastSyncTime(LocalDateTime.now())
                    .build();
                employeeRepo.save(emp);
                inserted++;
            }
        }

        // 标记已不在企微通讯录中的员工为离职
        int deactivated = 0;
        if (!activeUserIds.isEmpty()) {
            // 级联清理：先查出即将被标记离职的 userid，再同步更新 agent / qr_agent
            // 避免离职员工的 userid 继续被推送到企微 API 导致 60111
            List<String> toDeactivate = employeeRepo.findAllByActiveTrueOrderByName().stream()
                .map(Employee::getUserid)
                .filter(uid -> !activeUserIds.contains(uid))
                .toList();

            deactivated = employeeRepo.deactivateNotIn(activeUserIds);

            if (!toDeactivate.isEmpty()) {
                // 安全网：如果要标记离职的人数超过当前在职员工的 30%，可能是 API 返回异常
                // 此时跳过级联清理，只标记 employee.active=false（可恢复），避免误伤 agent/qr_agent
                // 注：activeBefore 在 deactivateNotIn 之后查询，实际值略小于真实值，
                //     这会使比率偏高（更保守），不影响安全性
                int activeAfterDeactivate = employeeRepo.findAllByActiveTrueOrderByName().size();
                double ratio = (double) toDeactivate.size() / Math.max(activeAfterDeactivate + toDeactivate.size(), 1);
                if (ratio > 0.30) {
                    log.error("员工离职比例异常({}/{}={}%)，跳过级联清理防止误伤。userids={}",
                        toDeactivate.size(), activeAfterDeactivate + toDeactivate.size(),
                        (int)(ratio * 100),
                        toDeactivate.size() <= 20 ? toDeactivate : toDeactivate.subList(0, 20) + "…");
                } else {
                    // 先找出服务老师（在下码之前查），后续单独发告警
                    List<String> serviceUserIds = qrAgentRepo.findServiceUseridsIn(toDeactivate);

                    // 所有离职员工统一封禁 agent + 下码（防止 60111）
                    int blocked = agentRepo.batchBlockByUserids(toDeactivate);
                    int removed = qrAgentRepo.batchRemoveByAgentUserids(toDeactivate);

                    // 服务老师/双角色额外创建告警，提醒管理员做在职继承
                    int alerted = 0;
                    if (!serviceUserIds.isEmpty()) {
                        for (String uid : serviceUserIds) {
                            try {
                                // 下码后查询该老师所有受影响活码（此时 status 已变为 removed）
                                List<String> qrNames = qrAgentRepo.findByAgentUserid(uid).stream()
                                    .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.removed
                                        && toDeactivate.contains(qa.getAgentUserid()))
                                    .map(qa -> "活码" + qa.getQrCodeId())
                                    .distinct()
                                    .toList();
                                alertService.createAlert(uid, "employee_departed_service",
                                    AgentAlert.AlertSeverity.high,
                                    String.format("服务老师 %s 已从企微离职，已自动下码，请手动处理在职继承。关联活码: %s",
                                        uid, String.join(", ", qrNames)),
                                    AgentAlert.AutoAction.none, null);
                                alerted++;
                            } catch (Exception e) {
                                log.error("服务老师离职告警失败: userid={}", uid, e);
                            }
                        }
                    }

                    log.info("员工离职级联清理: 标记离职{}人, agent封禁{}人, qr_agent移除{}人, 服务老师告警{}人, userids={}",
                        deactivated, blocked, removed, alerted,
                        toDeactivate.size() <= 10 ? toDeactivate : toDeactivate.subList(0, 10) + "…共" + toDeactivate.size() + "人");
                }
            }
        }

        // 自动解封已恢复员工（企微侧正常但本地仍 blocked 的 40098/41054 封禁）
        autoUnblockRecoveredAgents(wecomStatusMap);

        log.info("员工同步结果: 新增{}人, 更新{}人, 标记离职{}人, 在职共{}人",
            inserted, updated, deactivated, activeUserIds.size());

        return activeUserIds.size();
    }

    /**
     * 自动解封已恢复员工：企微侧已恢复正常（status=1）但本地 agent 仍卡在
     * blocked（原因 40098 未实名 / 41054 未激活）的员工，恢复为 normal 并重新入池。
     * <p>只解封「临时不可用」类封禁；「通讯录已移除」封禁代表真实离职，需人工在职继承，
     * 不在自动解封范围内。</p>
     */
    private void autoUnblockRecoveredAgents(Map<String, Integer> wecomStatusMap) {
        try {
            List<Agent> blockedAgents = agentRepo.findByOverallStatus(Agent.OverallStatus.blocked);
            int unblocked = 0;
            for (Agent agent : blockedAgents) {
                String reason = agent.getStatusReason();
                if (reason == null || (!reason.contains("40098") && !reason.contains("41054"))) {
                    continue;
                }
                Integer ws = wecomStatusMap.get(agent.getUserid());
                if (ws == null || ws != 1) {
                    continue; // 企微侧仍异常或已不在通讯录，保持封禁
                }
                agent.setOverallStatus(Agent.OverallStatus.normal);
                agent.setStatusReason(null);
                agent.setMeltedCount24h(0);
                agentRepo.save(agent);
                if (poolService.isPoolEligible(agent.getUserid())) {
                    poolService.ensureInPool(agent.getUserid(), 150);
                }
                unblocked++;
                log.info("自动解封已恢复员工: userid={}, name={}, role={}",
                    agent.getUserid(), agent.getName(), agent.getRole());
            }
            if (unblocked > 0) {
                log.info("自动解封完成：{} 名员工已恢复并重新入池", unblocked);
            }
        } catch (Exception e) {
            log.error("自动解封失败（不影响员工同步）", e);
        }
    }

    /**
     * 将在职但不在全局池中的员工批量写入池（不调企微 API，从本地 employee 表取数据）。
     *
     * <p>调用时机：
     * <ul>
     *   <li>管理员在员工管理页点击「从企微同步」按钮</li>
     *   <li>巡检发现全局池 standby 不足时自动触发</li>
     * </ul>
     *
     * <p>新入池员工排在队尾（sortOrder = 当前最大 + 1），日上限默认 100。</p>
     *
     * @return 新增入池的员工数
     */
    @Transactional
    public int syncToGlobalPool() {
        // 0. 校准角色漂移（agent.role 全量重算），确保后续过滤基于准确角色
        recomputeAgentRoles();

        // 1. 清理已在池中的离职员工（企微通讯录已标记 inactive 但仍留在池中）
        //    使用轻量投影查询，避免加载完整 Employee 实体
        Set<String> inactiveUserIds = employeeRepo.findByActiveFalse().stream()
            .map(Employee::getUserid)
            .collect(Collectors.toSet());
        int cleaned = 0;
        if (!inactiveUserIds.isEmpty()) {
            // 池中离职员工：直接用 JPQL 查询，无需加载全部池记录再过滤
            List<GlobalAgentPool> toRemove = poolRepo
                .findByAgentUseridIn(new ArrayList<>(inactiveUserIds));
            if (!toRemove.isEmpty()) {
                poolRepo.deleteAll(toRemove);
                cleaned = toRemove.size();
                log.info("全局池同步：移除 {} 个已离职员工: {}", cleaned,
                    toRemove.stream().map(GlobalAgentPool::getAgentUserid).toList());
            }
        }

        // 2. 回填已有池记录的 departmentId（存量数据兼容：旧记录该字段为 NULL）
        int backfilled = backfillPoolDepartmentIds();
        if (backfilled > 0) {
            log.info("全局池同步：回填 {} 条记录的 departmentId", backfilled);
        }

        // 3. 已在池中的 userid 集合（轻量投影，仅查 userid 列）
        Set<String> pooledUserIds = new HashSet<>(poolRepo.findAllAgentUserids());

        // 在职但不在池中的员工（排除企微侧明确不可用的：已禁用/未激活/已离职）
        List<Employee> activeNotInPool = employeeRepo.findAllByActiveTrueOrderByName().stream()
            .filter(e -> !pooledUserIds.contains(e.getUserid()))
            .filter(e -> e.getWechatStatus() == null || e.getWechatStatus() == 1)
            .toList();

        // 额外排除 Agent 侧已封禁/已熔断的员工（防止刚被 blockAgentForWechatIssue
        // 或 meltAgent 清理出池的员工又被 syncToGlobalPool 加回来）
        if (!activeNotInPool.isEmpty()) {
            List<String> candidateUserIds = activeNotInPool.stream()
                .map(Employee::getUserid).toList();
            Map<String, Agent> agentSnapshot = agentRepo.findAllById(candidateUserIds).stream()
                .collect(Collectors.toMap(Agent::getUserid, a -> a, (a, b) -> a));
            int before = activeNotInPool.size();
            activeNotInPool = activeNotInPool.stream()
                .filter(e -> {
                    Agent a = agentSnapshot.get(e.getUserid());
                    return a == null
                        || (a.getOverallStatus() != Agent.OverallStatus.blocked
                            && a.getOverallStatus() != Agent.OverallStatus.melted);
                })
                .toList();
            if (activeNotInPool.size() < before) {
                log.info("全局池同步：排除 {} 个已封禁/熔断员工",
                    before - activeNotInPool.size());
            }
            // 排除纯服务老师（无活跃接待员绑定）员工（避免被其他活码借走）
            // 角色漂移员工（在别的活码仍担任接待员）仍可入池
            int beforeSvc = activeNotInPool.size();
            Set<String> eligibleUserids = poolService.filterPoolEligible(
                activeNotInPool.stream().map(Employee::getUserid).toList());
            activeNotInPool = activeNotInPool.stream()
                .filter(e -> eligibleUserids.contains(e.getUserid()))
                .toList();
            if (activeNotInPool.size() < beforeSvc) {
                log.info("全局池同步：排除 {} 个纯服务老师员工",
                    beforeSvc - activeNotInPool.size());
            }
        }

        if (activeNotInPool.isEmpty()) {
            log.info("全局池同步：所有在职员工已在池中，无需新增");
            return 0;
        }

        int maxOrder = poolRepo.findFirstByOrderBySortOrderDesc()
            .map(GlobalAgentPool::getSortOrder).orElse(0);

        // 已在 Agent 表中的 userid（减少查询次数）
        Set<String> existingAgentIds = agentRepo.findAll().stream()
            .map(Agent::getUserid)
            .collect(Collectors.toSet());

        List<GlobalAgentPool> batch = new ArrayList<>();
        List<Agent> agentBatch = new ArrayList<>();

        for (Employee emp : activeNotInPool) {
            // 确保 Agent 主数据表有记录（用本地 employee 表的 name，不调企微 API）
            if (!existingAgentIds.contains(emp.getUserid())) {
                agentBatch.add(Agent.builder()
                    .userid(emp.getUserid())
                    .name(emp.getName() != null && !emp.getName().isEmpty()
                        ? emp.getName() : emp.getUserid())
                    .role(Agent.AgentRole.receptionist)
                    .dailyTotalCap(500)
                    .build());
                existingAgentIds.add(emp.getUserid());
            }

            maxOrder++;
            // 取主部门：Employee.department 是 JSON 数组如 "[1,2,3]"
            Long primaryDeptId = extractPrimaryDeptId(emp.getDepartment());
            batch.add(GlobalAgentPool.builder()
                .agentUserid(emp.getUserid())
                .dailyMax(150)
                .sortOrder(maxOrder)
                .departmentId(primaryDeptId)
                .status(GlobalAgentPool.PoolStatus.standby)
                .build());
        }

        // 批量写入
        if (!agentBatch.isEmpty()) {
            agentRepo.saveAll(agentBatch);
        }
        poolRepo.saveAll(batch);

        log.info("全局池同步完成：新增 {} 人入池，清理离职 {} 人，池总数 {} 人",
            batch.size(), cleaned, pooledUserIds.size() + batch.size());
        return batch.size();
    }

    /**
     * 全量重算 {@link Agent#getRole()}，校准「只升级不降级」造成的角色漂移。
     *
     * <p>权威信号为 {@code qr_agent} 活跃绑定 + {@code QrCode.transferTargetUserid}，
     * 其中已下码（full）的服务老师/双角色绑定仍计入（临时下码不丢身份）：
     * <ul>
     *   <li>含 dual 绑定 → dual</li>
     *   <li>同时含 receptionist + service 绑定 → dual</li>
     *   <li>仅 receptionist → receptionist</li>
     *   <li>仅 service → service</li>
     *   <li>无绑定且是某活码继承目标 → service（不入池）</li>
     *   <li>无绑定且非继承目标 → receptionist（自由人，可入池）</li>
     * </ul>
     * 跳过 blocked 员工（已停用/离职，不参与接待）。</p>
     *
     * @return 本次校准（角色变化）的员工数
     */
    @Transactional
    public int recomputeAgentRoles() {
        List<Agent> agents = agentRepo.findAll().stream()
            .filter(a -> a.getOverallStatus() != Agent.OverallStatus.blocked)
            .toList();
        if (agents.isEmpty()) return 0;

        List<String> userids = agents.stream().map(Agent::getUserid).toList();

        // 每个 userid 的活跃绑定角色集合
        Map<String, Set<QrAgent.AgentRole>> rolesByUser = new HashMap<>();
        for (Object[] row : qrAgentRepo.findActiveRolesByUserids(userids)) {
            String uid = (String) row[0];
            QrAgent.AgentRole role = (QrAgent.AgentRole) row[1];
            rolesByUser.computeIfAbsent(uid, k -> new HashSet<>()).add(role);
        }
        // 已下码（full）的服务老师/双角色绑定仍应保留 service 身份，
        // 避免日限下码后被降级为 receptionist 并误入全局池。
        for (Object[] row : qrAgentRepo.findFullServiceRolesByUserids(userids)) {
            String uid = (String) row[0];
            QrAgent.AgentRole role = (QrAgent.AgentRole) row[1];
            rolesByUser.computeIfAbsent(uid, k -> new HashSet<>()).add(role);
        }

        Set<String> transferTargets = qrCodeRepo.findTransferTargetUseridsIn(userids);

        int updated = 0;
        for (Agent a : agents) {
            Set<QrAgent.AgentRole> roles = rolesByUser.getOrDefault(a.getUserid(), Set.of());
            Agent.AgentRole target = deriveRole(roles, transferTargets.contains(a.getUserid()));
            if (a.getRole() != target) {
                a.setRole(target);
                agentRepo.save(a);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("重算 agent.role 完成：校准 {} 人", updated);
        }
        return updated;
    }

    /**
     * 由活跃绑定角色集合 + 是否继承目标，推导目标全局角色。
     */
    private Agent.AgentRole deriveRole(Set<QrAgent.AgentRole> roles, boolean isTransferTarget) {
        if (roles.contains(QrAgent.AgentRole.dual)) return Agent.AgentRole.dual;
        boolean hasReceptionist = roles.contains(QrAgent.AgentRole.receptionist);
        boolean hasService = roles.contains(QrAgent.AgentRole.service);
        if (hasReceptionist && hasService) return Agent.AgentRole.dual;
        if (hasReceptionist) return Agent.AgentRole.receptionist;
        if (hasService) return Agent.AgentRole.service;
        return isTransferTarget ? Agent.AgentRole.service : Agent.AgentRole.receptionist;
    }

    /**
     * 回填已有池记录的 departmentId（存量数据兼容：旧记录该字段为 NULL）。
     *
     * <p>遍历所有 departmentId 为 NULL 的池记录，从本地 Employee 表查找对应部门。
     * 每次 syncToGlobalPool 调用时执行，逐步修复存量数据。</p>
     *
     * @return 本次回填的记录数
     */
    private int backfillPoolDepartmentIds() {
        // 先用 COUNT 判断是否需要回填，避免每次都加载全表
        long nullCount = poolRepo.countWithNullDepartmentId();
        if (nullCount == 0) return 0;

        List<GlobalAgentPool> nullDeptRecords = poolRepo.findWithNullDepartmentId();
        List<GlobalAgentPool> toUpdate = new ArrayList<>();
        for (GlobalAgentPool p : nullDeptRecords) {
            Employee emp = employeeRepo.findByUserid(p.getAgentUserid()).orElse(null);
            if (emp != null) {
                Long deptId = extractPrimaryDeptId(emp.getDepartment());
                if (deptId != null) {
                    p.setDepartmentId(deptId);
                    toUpdate.add(p);
                }
            }
        }
        if (!toUpdate.isEmpty()) {
            poolRepo.saveAll(toUpdate);
        }
        return toUpdate.size();
    }

    /**
     * 从 Employee.department JSON 数组字符串中提取主部门 ID。
     *
     * <p>企微返回的 department 是 JSON 数组如 [1,2,3]，
     * 取第一个元素作为主部门。如果解析失败或为空，返回 null。</p>
     */
    private Long extractPrimaryDeptId(String departmentJson) {
        if (departmentJson == null || departmentJson.isBlank()) return null;
        try {
            JsonNode arr = objectMapper.readTree(departmentJson);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).asLong();
            }
        } catch (Exception e) {
            log.warn("解析部门 JSON 失败: {}", departmentJson, e);
        }
        return null;
    }
}
