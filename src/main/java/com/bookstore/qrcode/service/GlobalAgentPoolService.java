package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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
    private final WecomApiClient wecomApi;

    /**
     * 从全局池取优先级最高（sortOrder 最小）的 standby 员工，排除指定 userid 集合。
     *
     * <p>排除列表用于防止取到已在目标活码上的员工（同一员工可服务于多个活码，
     * 但不能在同一活码上重复添加）。按 sortOrder 升序遍历，返回第一个不在排除列表中的 standby。</p>
     *
     * @param excludeUserids 需要排除的企微 userid 集合，可为 {@code null} 或空集
     * @return 池中优先级最高的可用 standby 员工，池空或全部被排除时返回 {@code null}
     */
    @Transactional
    public GlobalAgentPool takeStandby(Set<String> excludeUserids) {
        List<GlobalAgentPool> standbys = poolRepo
            .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
        if (standbys.isEmpty()) {
            log.warn("全局员工池无 standby 员工可用！");
            return null;
        }
        for (GlobalAgentPool p : standbys) {
            if (excludeUserids == null || !excludeUserids.contains(p.getAgentUserid())) {
                return p;
            }
        }
        log.warn("全局员工池所有 standby 均在排除列表中（排除 {} 人），无可用员工",
            excludeUserids != null ? excludeUserids.size() : 0);
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
            String name = userid;
            try {
                JsonNode result = wecomApi.getUserSimplelist();
                if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                    for (JsonNode u : result.get("userlist")) {
                        if (userid.equals(u.get("userid").asText())) {
                            name = u.get("name").asText();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取员工姓名失败: userid={}", userid, e);
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
        // 1. 全部员工日计数归零（Redis key 在每日过期，此处同步 DB 持久化值）
        List<GlobalAgentPool> all = poolRepo.findAll();
        for (GlobalAgentPool p : all) {
            if (p.getDailyCurrent() > 0) {
                p.setDailyCurrent(0);
                poolRepo.save(p);
            }
        }

        // 2. full → standby，移往队尾
        List<GlobalAgentPool> fulls = poolRepo
            .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.full);
        if (fulls.isEmpty()) return;

        // 取当前最大 sortOrder，满员恢复后依次排到队尾
        int maxOrder = poolRepo.findFirstByOrderBySortOrderDesc()
            .map(GlobalAgentPool::getSortOrder).orElse(0);

        for (GlobalAgentPool p : fulls) {
            p.setStatus(GlobalAgentPool.PoolStatus.standby);
            p.setSortOrder(++maxOrder);
            p.setLastResetAt(LocalDateTime.now());
            poolRepo.save(p);
        }
        log.info("全局池日重置: 恢复 {} 个 full 员工，已移至队尾 (maxOrder={})",
            fulls.size(), maxOrder);
    }
}
