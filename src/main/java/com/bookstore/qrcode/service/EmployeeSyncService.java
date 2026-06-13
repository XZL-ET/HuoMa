package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        JsonNode resp = wecomApi.getUserSimplelist();

        if (!resp.has("userlist") || !resp.get("userlist").isArray()) {
            log.warn("企微员工列表为空或格式异常");
            return 0;
        }

        List<String> activeUserIds = new ArrayList<>();
        int inserted = 0;
        int updated = 0;

        for (JsonNode u : resp.get("userlist")) {
            String userid = u.get("userid").asText();
            String name = u.has("name") ? u.get("name").asText() : "";
            String dept = u.has("department") ? u.get("department").toString() : null;

            if (userid.isEmpty()) continue;
            activeUserIds.add(userid);

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
                    .lastSyncTime(LocalDateTime.now())
                    .build();
                employeeRepo.save(emp);
                inserted++;
            }
        }

        // 标记已不在企微通讯录中的员工为离职
        int deactivated = 0;
        if (!activeUserIds.isEmpty()) {
            deactivated = employeeRepo.deactivateNotIn(activeUserIds);
        }

        log.info("员工同步结果: 新增{}人, 更新{}人, 标记离职{}人, 在职共{}人",
            inserted, updated, deactivated, activeUserIds.size());

        return activeUserIds.size();
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
     * <p>新入池员工排在队尾（sortOrder = 当前最大 + 1），日上限默认 200。</p>
     *
     * @return 新增入池的员工数
     */
    @Transactional
    public int syncToGlobalPool() {
        // 1. 清理已在池中的离职员工（企微通讯录已标记 inactive 但仍留在池中）
        Set<String> inactiveUserIds = employeeRepo.findAll().stream()
            .filter(e -> !e.getActive())
            .map(Employee::getUserid)
            .collect(Collectors.toSet());
        int cleaned = 0;
        if (!inactiveUserIds.isEmpty()) {
            List<GlobalAgentPool> toRemove = poolRepo.findAll().stream()
                .filter(p -> inactiveUserIds.contains(p.getAgentUserid()))
                .toList();
            if (!toRemove.isEmpty()) {
                poolRepo.deleteAll(toRemove);
                cleaned = toRemove.size();
                log.info("全局池同步：移除 {} 个已离职员工: {}", cleaned,
                    toRemove.stream().map(GlobalAgentPool::getAgentUserid).toList());
            }
        }

        // 2. 已在池中的 userid 集合（刷新，因为上面可能删了）
        Set<String> pooledUserIds = poolRepo.findAll().stream()
            .map(GlobalAgentPool::getAgentUserid)
            .collect(Collectors.toSet());

        // 在职但不在池中的员工
        List<Employee> activeNotInPool = employeeRepo.findAllByActiveTrueOrderByName().stream()
            .filter(e -> !pooledUserIds.contains(e.getUserid()))
            .toList();

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
            batch.add(GlobalAgentPool.builder()
                .agentUserid(emp.getUserid())
                .dailyMax(200)
                .sortOrder(maxOrder)
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
}
