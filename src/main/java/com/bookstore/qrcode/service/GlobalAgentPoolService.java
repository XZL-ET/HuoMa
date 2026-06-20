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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (poolRepo.findByAgentUserid(userid).isPresent()) return;

        // 确保 Agent 全局表存在
        if (!agentRepo.existsById(userid)) {
            // 优先从本地 Employee 表取姓名（每 30 分钟从企微同步，命中率极高）
            String name = employeeRepo.findByUserid(userid)
                .map(Employee::getName)
                .orElse(null);
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
            .status(GlobalAgentPool.PoolStatus.standby).build());
        log.info("全局池新增员工: userid={}, dailyMax={}", userid, dailyMax);
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
