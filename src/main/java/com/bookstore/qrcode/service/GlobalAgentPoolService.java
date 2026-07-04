package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局员工池服务 — 替代每码独立后备池。
 *
 * <p>核心职责：从全局池按优先级取人、标记满员、更新日计数、
 * 确保员工在池中、统计余量、每日重置。</p>
 *
 * <h3>与旧后备池的区别</h3>
 * <ul>
 *   <li>旧：每个活码独立维护后备池（{@code qr_backup_pool}），员工可重复出现在不同码的后备池中</li>
 *   <li>新：全局唯一池（{@code global_agent_pool}），每个员工只有一条记录，任一活码都能从池中取人</li>
 *   <li>旧：日限额按活码独立计算</li>
 *   <li>新：日限额全局统一计算（{@code agent:daily:total:{userid}}），匹配企微实际限制</li>
 * </ul>
 *
 * @author Bookstore Dev Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalAgentPoolService {

    private final GlobalAgentPoolRepository poolRepo;
    private final AgentRepository agentRepo;
    private final EmployeeRepository employeeRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

    /** 部门树缓存：parentId → 所有子孙部门 ID。企微部门结构很少变动，实例级缓存即可。 */
    private final Map<Long, Collection<Long>> deptTreeCache = new ConcurrentHashMap<>();

    /**
     * 从全局池取优先级最高（sortOrder 最小）的 standby 员工，排除指定 userid 集合。
     *
     * <p>排除列表用于防止取到已在目标活码上的员工（同一员工可服务于多个活码，
     * 但不能在同一活码上重复添加）。按 sortOrder 升序遍历，返回第一个不在排除列表中的 standby。</p>
     *
     * <h3>公平轮转</h3>
     * <p>取走员工后自动将其 sortOrder 推至队尾（max+1），确保下次取人时优先选用
     * 尚未被取过的员工，避免同一批人反复被选中。</p>
     *
     * <h3>懒清理</h3>
     * <p>遍历过程中遇到不可用员工（离职、企微未激活/禁用、封号/熔断）时，
     * 自动从全局池物理删除，防止堵塞队首影响后续取人效率。</p>
     *
     * @param excludeUserids 需要排除的企微 userid 集合，可为 {@code null} 或空集
     * @return 池中优先级最高的可用 standby 员工，池空或全部不可用时返回 {@code null}
     */
    @Transactional
    public GlobalAgentPool takeStandby(Set<String> excludeUserids) {
        List<GlobalAgentPool> standbys = poolRepo
            .findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby);
        if (standbys.isEmpty()) {
            log.warn("全局员工池无 standby 员工可用！");
            return null;
        }
        int skippedInactive = 0, skippedBlocked = 0, skippedWechatUnavailable = 0;
        for (GlobalAgentPool p : standbys) {
            // 排除已在活码上的员工
            if (excludeUserids != null && excludeUserids.contains(p.getAgentUserid())) {
                continue;
            }
            // 过滤已离职员工（企微通讯录已标记 inactive）→ 懒清理出池
            Employee emp = employeeRepo.findByUserid(p.getAgentUserid()).orElse(null);
            if (emp != null && !emp.getActive()) {
                skippedInactive++;
                poolRepo.delete(p);
                log.info("跳过并清理离职员工: userid={}", p.getAgentUserid());
                continue;
            }
            // 过滤企微侧不可用员工（未激活、已禁用、已离职等，Layer 1 主动防御）→ 懒清理出池
            if (emp != null && emp.getWechatStatus() != null && emp.getWechatStatus() != 1) {
                skippedWechatUnavailable++;
                poolRepo.delete(p);
                log.info("跳过并清理企微不可用员工: userid={}, wechatStatus={}",
                    p.getAgentUserid(), emp.getWechatStatus());
                continue;
            }
            // 过滤封号/熔断员工（企微侧已不可用）→ 懒清理出池
            Agent agent = agentRepo.findById(p.getAgentUserid()).orElse(null);
            if (agent != null && (
                agent.getOverallStatus() == Agent.OverallStatus.blocked
                || agent.getOverallStatus() == Agent.OverallStatus.melted)) {
                skippedBlocked++;
                poolRepo.delete(p);
                log.info("跳过并清理封号/熔断员工: userid={}", p.getAgentUserid());
                continue;
            }
            // 过滤服务老师/双角色 → 懒清理出池（不应在全局池中，防止被其他活码借走）
            if (agent != null
                && (agent.getRole() == Agent.AgentRole.service
                 || agent.getRole() == Agent.AgentRole.dual)) {
                skippedBlocked++;
                poolRepo.delete(p);
                log.info("跳过并清理服务老师/双角色: userid={}, role={}", p.getAgentUserid(), agent.getRole());
                continue;
            }
            // 取走后移至队尾，确保下次活码创建时补充到不同员工（公平轮转）
            int maxOrder = poolRepo.findFirstByOrderBySortOrderDesc()
                .map(GlobalAgentPool::getSortOrder).orElse(0);
            p.setSortOrder(maxOrder + 1);
            poolRepo.save(p);
            log.info("全局池取走员工: userid={}, 移至队尾 sortOrder={}", p.getAgentUserid(), p.getSortOrder());
            return p;
        }
        log.warn("全局员工池无可用 standby（排除={}, 清理离职={}, 清理企微不可用={}, 清理封号/熔断={}）",
            excludeUserids != null ? excludeUserids.size() : 0,
            skippedInactive, skippedWechatUnavailable, skippedBlocked);
        return null;
    }

    /**
     * 从全局池取 standby 员工，优先匹配指定部门。
     *
     * <h3>取人优先级</h3>
     * <ol>
     *   <li>同部门（含子孙部门）standby → 按 sort_order 取</li>
     *   <li>同部门无可用 → 降级为全局取人（任意部门）</li>
     *   <li>全局枯竭 → 返回 null</li>
     * </ol>
     *
     * @param excludeUserids 排除的 userid 集合
     * @param preferredDepartmentId 优先部门 ID，null 时直接全局取人
     * @return 可用 standby，或无可用时返回 null
     */
    @Transactional
    public GlobalAgentPool takeStandby(Set<String> excludeUserids, Long preferredDepartmentId) {
        // ① 收集子孙部门 ID（用于 IN 查询）
        Collection<Long> deptIds = null;
        if (preferredDepartmentId != null) {
            deptIds = collectDescendantDeptIds(preferredDepartmentId);
        }

        // ② 查询同部门 standby（带行锁）
        if (deptIds != null && !deptIds.isEmpty()) {
            List<GlobalAgentPool> standbys = poolRepo.findStandbysByDeptForUpdate(deptIds,
                GlobalAgentPool.PoolStatus.standby);
            if (!standbys.isEmpty()) {
                GlobalAgentPool result = selectFromDeptCandidates(standbys, excludeUserids);
                if (result != null) return result;
                log.info("同部门无可用 standby，降级为全局取人: deptId={}", preferredDepartmentId);
            } else {
                log.info("同部门({})无 standby 记录（池中无该部门员工），降级为全局取人", preferredDepartmentId);
            }
        }

        // ③ 降级：全局取人（调用原方法）
        return takeStandby(excludeUserids);
    }

    /**
     * 从同部门候选列表中选取第一个可用员工（懒清理 + 公平轮转）。
     *
     * <p>逻辑与 {@link #takeStandby(Set)} 保持一致，
     * 复用相同的懒清理规则（离职/企微不可用/封号熔断）。</p>
     */
    private GlobalAgentPool selectFromDeptCandidates(List<GlobalAgentPool> candidates,
                                                      Set<String> excludeUserids) {
        int skippedInactive = 0, skippedBlocked = 0, skippedWechatUnavailable = 0;
        for (GlobalAgentPool p : candidates) {
            if (excludeUserids != null && excludeUserids.contains(p.getAgentUserid())) {
                continue;
            }
            // 过滤已离职员工 → 懒清理出池
            Employee emp = employeeRepo.findByUserid(p.getAgentUserid()).orElse(null);
            if (emp != null && !emp.getActive()) {
                skippedInactive++;
                poolRepo.delete(p);
                continue;
            }
            // 过滤企微侧不可用员工 → 懒清理出池
            if (emp != null && emp.getWechatStatus() != null && emp.getWechatStatus() != 1) {
                skippedWechatUnavailable++;
                poolRepo.delete(p);
                continue;
            }
            // 过滤封号/熔断员工 → 懒清理出池
            Agent agent = agentRepo.findById(p.getAgentUserid()).orElse(null);
            if (agent != null && (
                agent.getOverallStatus() == Agent.OverallStatus.blocked
                || agent.getOverallStatus() == Agent.OverallStatus.melted)) {
                skippedBlocked++;
                poolRepo.delete(p);
                continue;
            }
            // 过滤服务老师/双角色 → 懒清理出池（不应在全局池中，防止被其他活码借走）
            if (agent != null
                && (agent.getRole() == Agent.AgentRole.service
                 || agent.getRole() == Agent.AgentRole.dual)) {
                skippedBlocked++;
                poolRepo.delete(p);
                log.info("跳过并清理服务老师/双角色: userid={}, role={}", p.getAgentUserid(), agent.getRole());
                continue;
            }
            // 取走 → 推到队尾（公平轮转）
            int currentMax = poolRepo.findFirstByOrderBySortOrderDesc()
                .map(GlobalAgentPool::getSortOrder).orElse(0);
            p.setSortOrder(currentMax + 1);
            poolRepo.save(p);
            return p;
        }
        if (skippedInactive > 0 || skippedWechatUnavailable > 0 || skippedBlocked > 0) {
            log.info("同部门懒清理: 跳过离职={}, 企微不可用={}, 封号/熔断={}",
                skippedInactive, skippedWechatUnavailable, skippedBlocked);
        }
        return null;
    }

    /**
     * 收集指定部门的所有子孙部门 ID（带实例级缓存，避免重复调用企微 API）。
     *
     * <p>优先从 {@link #deptTreeCache} 获取，未命中时调用企微 API 拉取并缓存。
     * 企微部门结构很少变动，实例级缓存生命周期同应用进程。</p>
     */
    private Collection<Long> collectDescendantDeptIds(Long parentId) {
        return deptTreeCache.computeIfAbsent(parentId, this::doCollectDescendantDeptIds);
    }

    /**
     * 递归拉取子孙部门 ID（无缓存，实际调用企微 API）。
     */
    private Collection<Long> doCollectDescendantDeptIds(Long parentId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(parentId);
        try {
            JsonNode resp = wecomApi.listDepartments(parentId);
            if (resp.has("department") && resp.get("department").isArray()) {
                for (JsonNode dept : resp.get("department")) {
                    long childId = dept.get("id").asLong();
                    if (!ids.contains(childId)) {
                        ids.addAll(doCollectDescendantDeptIds(childId));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("拉取子孙部门失败: parentId={}, 仅用直接部门ID", parentId, e);
        }
        return ids;
    }

    /**
     * 标记员工日限到达，状态从 standby 转为 full。
     *
     * @param agentUserid 企微员工 userid
     */
    @Transactional
    public void markFull(String agentUserid) {
        poolRepo.findByAgentUserid(agentUserid).ifPresent(pool -> {
            pool.setStatus(GlobalAgentPool.PoolStatus.full);
            pool.setLastResetAt(LocalDateTime.now());
            poolRepo.save(pool);
            log.info("全局池标记满员: userid={}", agentUserid);
        });
    }

    /**
     * 同步员工今日全局累计接待数到 DB。
     *
     * @param agentUserid 企微员工 userid
     * @param count       今日全局累计接待数
     */
    @Transactional
    public void updateDailyCurrent(String agentUserid, int count) {
        poolRepo.findByAgentUserid(agentUserid).ifPresent(pool -> {
            pool.setDailyCurrent(count);
            poolRepo.save(pool);
        });
    }

    /**
     * 确保员工在全局池中存在，不存在则创建。
     *
     * <p>同时确保 {@link Agent} 全局表中存在该员工记录（懒初始化）。
     * 新员工排在当前最大 sortOrder 之后。</p>
     *
     * @param userid   企微员工 userid
     * @param dailyMax 全局日接待上限
     */
    @Transactional
    public void ensureInPool(String userid, int dailyMax) {
        GlobalAgentPool existing = poolRepo.findByAgentUserid(userid).orElse(null);

        // 优先从本地 Employee 表取部门（每 30 分钟从企微同步，命中率极高）
        Employee emp = employeeRepo.findByUserid(userid).orElse(null);
        Long deptId = emp != null ? extractPrimaryDeptId(emp.getDepartment()) : null;

        // 已有记录但 departmentId 为空 → 回填
        if (existing != null && existing.getDepartmentId() == null && deptId != null) {
            existing.setDepartmentId(deptId);
            poolRepo.save(existing);
            log.info("回填已有池记录的 departmentId: userid={}, deptId={}", userid, deptId);
        }

        if (existing != null) return;

        // 确保 Agent 全局表存在
        String name = emp != null ? emp.getName() : null;
        if (!agentRepo.existsById(userid)) {
            // 本地没有则回退到企微 API（仅新员工首次同步时可能走到此分支）
            if (name == null || name.isEmpty()) {
                name = userid;
                try {
                    JsonNode result = wecomApi.getUserSimplelist();
                    // parseAndCheck 保证 errcode=0
                    for (JsonNode u : result.get("userlist")) {
                        if (userid.equals(u.get("userid").asText())) {
                            name = u.get("name").asText();
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取员工姓名失败: userid={}", userid, e);
                }
            }
            agentRepo.save(Agent.builder()
                .userid(userid).name(name)
                .role(Agent.AgentRole.receptionist)
                .dailyTotalCap(500).build());
        }

        int maxOrder = poolRepo.findFirstByOrderBySortOrderDesc()
            .map(GlobalAgentPool::getSortOrder).orElse(0);

        poolRepo.save(GlobalAgentPool.builder()
            .agentUserid(userid).dailyMax(dailyMax)
            .sortOrder(maxOrder + 1)
            .departmentId(deptId)
            .status(GlobalAgentPool.PoolStatus.standby).build());
        log.info("全局池新增员工: userid={}, dailyMax={}, deptId={}", userid, dailyMax, deptId);
    }

    /**
     * 从 Employee.department JSON 数组中提取主部门 ID（取第一个元素）。
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

    /**
     * 统计 standby 状态员工数，供巡检和后台展示。
     *
     * @return standby 员工数量
     */
    public long countStandby() {
        return poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
    }

    /**
     * 获取所有 standby 员工列表（按优先级排序），供管理后台展示。
     *
     * @return standby 员工列表
     */
    public List<GlobalAgentPool> listStandby() {
        return poolRepo.findByStatusOrderBySortOrder(
            GlobalAgentPool.PoolStatus.standby);
    }

    /**
     * 每日重置 — 将所有员工日计数清零，满员恢复为待命并移到队尾。
     *
     * <p>由 {@code DailyResetWorker} 在凌晨 00:00 调用。
     * blocked 状态的员工不在此处理（需人工解除）。</p>
     *
     * <p><b>轮转公平性：</b>满过的员工排到队尾（max+1），确保次日优先使用
     * 未被耗尽过的 standby 员工，避免同一批人每天被先消耗。</p>
     */
    @Transactional
    public void dailyReset() {
        LocalDateTime now = LocalDateTime.now();

        // 1. full → standby，移往队尾（批量 UPDATE）
        int updated = poolRepo.batchUpdateStatus(
            GlobalAgentPool.PoolStatus.full,
            GlobalAgentPool.PoolStatus.standby,
            10000, // offset 足够大，确保移到现有队尾之后
            now);
        log.info("dailyReset 批量更新: {} 人 full→standby", updated);

        // 2. 全部 standby 员工日计数归零（批量 UPDATE）
        int reset = poolRepo.batchResetDailyCurrent(
            GlobalAgentPool.PoolStatus.standby);
        log.info("dailyReset 计数清零: {} 人", reset);
    }

    /**
     * Layer 2 自愈：封锁企微侧不可用员工并移出全局池。
     *
     * <p>调用时机：企微 API 返回 40098（未实名）或 41054（未激活）时。
     * 将员工在 agent 表标记为 blocked，并从 global_agent_pool 物理删除，
     * 确保后续 takeStandby 不会再次选中。</p>
     *
     * @param agentUserid 企微员工 userid
     * @param errcode     企微 API 返回的错误码
     */
    @Transactional
    public void blockAgentForWechatIssue(String agentUserid, int errcode) {
        // 标记 agent 为 blocked，记录原因
        Agent agent = agentRepo.findById(agentUserid).orElse(null);
        if (agent != null) {
            agent.setOverallStatus(Agent.OverallStatus.blocked);
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", "企微不可用: errcode=" + errcode);
            reason.put("errcode", errcode);
            reason.put("blocked_at", LocalDateTime.now().toString());
            try {
                agent.setStatusReason(objectMapper.writeValueAsString(reason));
            } catch (Exception ignored) {
                agent.setStatusReason("企微不可用: errcode=" + errcode);
            }
            agentRepo.save(agent);
        }

        // 从全局员工池移除（物理删除，确保 takeStandby 不选到）
        poolRepo.findByAgentUserid(agentUserid).ifPresent(pool -> {
            poolRepo.delete(pool);
            log.warn("Layer2自愈: 全局池已移除不可用员工 userid={}, errcode={}", agentUserid, errcode);
        });
    }
}
