package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.AllEmployeeRow;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.EmployeeSyncService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import com.bookstore.qrcode.service.OperationLogService;
import com.bookstore.qrcode.service.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工管理页面控制器 — 展示全局员工池。
 *
 * <p>展示全局池中所有员工的当前状态、日用量、优先级，
 * 以及每人在哪些活码上担任接待员。支持关键词搜索、状态筛选和分页。</p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/agents")
@RequiredArgsConstructor
public class AgentController {

    private final GlobalAgentPoolRepository poolRepo;
    private final GlobalAgentPoolService poolService;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeService qrCodeService;
    private final EmployeeSyncService employeeSyncService;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;
    private final OperationLogService operationLogService;

    /**
     * GET {@code /agents} — 全局员工池列表（分页 + 筛选）。
     *
     * <p>数据维度：
     * <ul>
     *   <li>按状态分组统计（standby / full / blocked），使用 COUNT 查询</li>
     *   <li>支持按姓名/userid 关键词搜索、按状态筛选</li>
     *   <li>支持分页，默认每页 50 条</li>
     *   <li>每人显示所在活码列表（取 active 状态的 QrAgent 关联）</li>
     *   <li>显示全局日用量 vs 日上限</li>
     * </ul>
     */
    @GetMapping
    public String list(Model model,
                       @RequestParam(defaultValue = "") String keyword,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "") String anomaly,
                       @RequestParam(defaultValue = "pool") String view,
                       @PageableDefault(size = 50) Pageable pageable) {

        // ── 全量视图分支 ──
        if ("all".equals(view)) {
            return listAllView(model, keyword, status, anomaly, pageable);
        }

        // ── 统计（COUNT 查询，不依赖分页）──
        long standbyCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
        long fullCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.full);
        long blockedCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.blocked);
        long totalEmployeeCount = employeeRepo.count();  // 企微同步的员工总数（含不可用）

        // ── 构建员工姓名映射 + Employee/Agent 快照（仅加载池中 userid，避免全表扫描）──
        Set<String> poolUserIds = new LinkedHashSet<>(poolRepo.findAllAgentUserids());
        Map<String, String> agentNameMap = new LinkedHashMap<>();
        Map<String, Employee> employeeMap = new LinkedHashMap<>();
        if (!poolUserIds.isEmpty()) {
            for (Employee emp : employeeRepo.findByUseridIn(poolUserIds)) {
                agentNameMap.putIfAbsent(emp.getUserid(), emp.getName());
                employeeMap.putIfAbsent(emp.getUserid(), emp);
            }
        }
        Map<String, Agent> agentMap = new LinkedHashMap<>();
        for (Agent agent : agentRepo.findAllById(poolUserIds)) {
            agentNameMap.putIfAbsent(agent.getUserid(), agent.getName());
            agentMap.putIfAbsent(agent.getUserid(), agent);
        }

        // ── 参数解析 ──
        final GlobalAgentPool.PoolStatus statusFilter;
        if (!status.isBlank()) {
            GlobalAgentPool.PoolStatus parsed = null;
            try {
                parsed = GlobalAgentPool.PoolStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {}
            statusFilter = parsed;
        } else {
            statusFilter = null;
        }

        // ── 分页查询 ──
        // 异常筛选需要计算每条记录的异常标签，无法下推到 DB，因此走
        // "全量加载 → 标签计算 → Java 过滤 → 手动分页" 路径。
        // 无异常筛选时走 DB 分页，避免加载全量。
        Page<GlobalAgentPool> poolPage;
        boolean anomalyFilterActive = !anomaly.isBlank();

        if (!keyword.isBlank()) {
            // 收集匹配 userid 集合
            Set<String> keywordUserIds = new LinkedHashSet<>();
            for (GlobalAgentPool p : poolRepo.findByAgentUseridContaining(keyword)) {
                keywordUserIds.add(p.getAgentUserid());
            }
            for (Agent a : agentRepo.findByNameContaining(keyword)) {
                keywordUserIds.add(a.getUserid());
            }
            for (Employee emp : employeeRepo.findByNameContaining(keyword)) {
                keywordUserIds.add(emp.getUserid());
            }

            if (keywordUserIds.isEmpty()) {
                poolPage = Page.empty(pageable);
            } else if (anomalyFilterActive) {
                // 关键词 + 异常 双筛选 → 全量加载后 Java 过滤 + 手动分页
                List<GlobalAgentPool> matched = poolRepo.findAll().stream()
                    .filter(p -> keywordUserIds.contains(p.getAgentUserid()))
                    .filter(p -> statusFilter == null || p.getStatus() == statusFilter)
                    .filter(p -> anomaly.equals(getAnomalyLabel(p.getAgentUserid(), agentMap, employeeMap)))
                    .sorted(Comparator.comparingInt(GlobalAgentPool::getSortOrder))
                    .toList();
                poolPage = manualPage(matched, pageable);
            } else {
                List<String> matchList = new ArrayList<>(keywordUserIds);
                poolPage = statusFilter != null
                    ? poolRepo.findByAgentUseridInAndStatusOrderBySortOrder(matchList, statusFilter, pageable)
                    : poolRepo.findByAgentUseridInOrderBySortOrder(matchList, pageable);
            }
        } else if (anomalyFilterActive) {
            // 仅异常筛选 → 全量加载后 Java 过滤 + 手动分页
            List<GlobalAgentPool> matched = poolRepo.findAll().stream()
                .filter(p -> anomaly.equals(getAnomalyLabel(p.getAgentUserid(), agentMap, employeeMap)))
                .sorted(Comparator.comparingInt(GlobalAgentPool::getSortOrder))
                .toList();
            poolPage = manualPage(matched, pageable);
        } else {
            poolPage = statusFilter != null
                ? poolRepo.findByStatusOrderBySortOrder(statusFilter, pageable)
                : poolRepo.findAllByOrderBySortOrder(pageable);
        }

        // ── 每人对应的活码列表 ──
        Map<String, List<String>> agentQrNames = loadQrNames(poolUserIds);

        // ── 构建当前页的异常状态标签映射（复用 getAnomalyLabel 统一逻辑）──
        Map<String, String> agentAnomalyMap = new LinkedHashMap<>();
        Map<String, String> agentAnomalyClassMap = new LinkedHashMap<>();
        Map<String, String> agentStatusReasonMap = new LinkedHashMap<>();
        for (GlobalAgentPool p : poolPage.getContent()) {
            String label = getAnomalyLabel(p.getAgentUserid(), agentMap, employeeMap);
            if (label != null) {
                agentAnomalyMap.put(p.getAgentUserid(), label);
                agentAnomalyClassMap.put(p.getAgentUserid(), getAnomalyClass(label));
            }
            // 熔断/停用的原因文本，供确认弹窗展示
            Agent a = agentMap.get(p.getAgentUserid());
            if (a != null && a.getStatusReason() != null
                && (a.getOverallStatus() == Agent.OverallStatus.melted
                    || a.getOverallStatus() == Agent.OverallStatus.blocked)) {
                agentStatusReasonMap.put(p.getAgentUserid(), a.getStatusReason());
            }
        }

        model.addAttribute("view", "pool");
        model.addAttribute("poolPage", poolPage);
        model.addAttribute("agentNameMap", agentNameMap);
        model.addAttribute("agentQrNames", agentQrNames);
        model.addAttribute("agentAnomalyMap", agentAnomalyMap);
        model.addAttribute("agentAnomalyClassMap", agentAnomalyClassMap);
        model.addAttribute("agentStatusReasonMap", agentStatusReasonMap);
        model.addAttribute("anomalyFilter", anomaly);
        model.addAttribute("standbyCount", standbyCount);
        model.addAttribute("fullCount", fullCount);
        model.addAttribute("blockedCount", blockedCount);
        model.addAttribute("keyword", keyword);
        model.addAttribute("statusFilter", status);
        model.addAttribute("totalCount", standbyCount + fullCount + blockedCount);
        model.addAttribute("totalEmployeeCount", totalEmployeeCount);
        model.addAttribute("title", "员工管理");
        return "agent/list";
    }

    /**
     * POST {@code /agents/sync} — 手动触发：从企微通讯录同步新员工到全局池。
     *
     * <p>已在池中的员工不会重复添加。新员工排在队尾，日上限默认 100。</p>
     */
    @PostMapping("/sync")
    public String syncFromWecom(RedirectAttributes redirect) {
        try {
            int added = employeeSyncService.syncToGlobalPool();
            if (added > 0) {
                redirect.addFlashAttribute("message", "已从企微同步 " + added + " 名新员工到全局池");
            } else {
                redirect.addFlashAttribute("message", "所有企微在职员工已在全局池中，无需同步");
            }
        } catch (Exception e) {
            // 不暴露企微 API 原始错误信息给前端（可能含 access_token 等敏感信息）
            log.warn("从企微同步员工失败", e);
            redirect.addFlashAttribute("error", "同步失败，请稍后重试或联系管理员");
        }
        return "redirect:/agents";
    }

    /**
     * POST {@code /agents/restore} — 一键恢复：将熔断/停用员工恢复为正常状态。
     *
     * <p>清零熔断计数、清除状态原因，使员工可重新参与活码分配。
     * 支持从池内视图和全量视图发起，恢复后保持在当前视图。
     * 恢复前记录审计日志，恢复后接待员自动回池。
     * 服务老师/双角色自动恢复到原活码（重新激活 full/removed 的 QrAgent 记录），
     * 并同步企微侧联系人列表。</p>
     */
    @Transactional
    @PostMapping("/restore")
    public String restoreAgent(@RequestParam String userid,
                               @RequestParam(defaultValue = "pool") String view,
                               @RequestParam(defaultValue = "") String redirectUrl,
                               RedirectAttributes redirect) {
        Agent agent = agentRepo.findByIdForUpdate(userid).orElse(null);
        if (agent == null) {
            redirect.addFlashAttribute("error", "员工不存在: " + userid);
            return redirectTo(redirectUrl, view);
        }
        Agent.OverallStatus prev = agent.getOverallStatus();
        boolean needsStatusReset = prev == Agent.OverallStatus.melted
                                || prev == Agent.OverallStatus.blocked;

        // ── 审计日志（恢复前记录，确保可追溯）──
        if (needsStatusReset) {
            String prevReason = agent.getStatusReason();
            operationLogService.log(
                SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName() : "system",
                "restore_agent",
                "agent",
                userid,
                String.format("恢复员工 %s 从 %s 到 normal，原熔断原因: %s",
                    agent.getName(), prev.name(),
                    prevReason != null ? prevReason : "未知")
            );
            agent.setOverallStatus(Agent.OverallStatus.normal);
            agent.setStatusReason(null);
            agent.setMeltedCount24h(0);
            agentRepo.save(agent);
            log.info("管理员恢复员工 {}: {} → normal", userid, prev);
        }

        // ── 查询该员工在所有活码上的历史记录（含 active/full/removed）──
        List<QrAgent> qrAgents = qrAgentRepo.findByAgentUserid(userid);
        Set<Long> alreadyActiveQrIds = new HashSet<>(qrAgents.stream()
            .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.active)
            .map(QrAgent::getQrCodeId)
            .collect(Collectors.toSet()));

        List<String> restoredCodes = new ArrayList<>();      // 本次恢复的活码名称
        List<String> otherCodes = new ArrayList<>();         // 仍在码上的活码名称
        Set<Long> syncedQrIds = new HashSet<>();             // 需要同步到企微的活码 ID

        for (QrAgent qa : qrAgents) {
            QrCode qr = qrCodeRepo.findById(qa.getQrCodeId()).orElse(null);
            String qrName = qr != null ? qr.getSchoolName() : "活码#" + qa.getQrCodeId();

            if (qa.getStatus() == QrAgent.AgentStatus.active) {
                otherCodes.add(qrName);
            } else {
                // 任何非 active 状态（full/removed/null/其他）都重新激活
                // 防止重复：同一活码已有 active 记录则跳过
                if (alreadyActiveQrIds.contains(qa.getQrCodeId())) {
                    otherCodes.add(qrName + "(已在码上)");
                    continue;
                }
                QrAgent.AgentStatus prevQaStatus = qa.getStatus();
                qa.setStatus(QrAgent.AgentStatus.active);
                qa.setDailyCurrent(0); // 重置当日计数
                qrAgentRepo.save(qa);
                restoredCodes.add(qrName);
                syncedQrIds.add(qa.getQrCodeId());
                alreadyActiveQrIds.add(qa.getQrCodeId()); // 防止同活码多条 removed 记录重复激活
                log.info("恢复员工到活码: userid={}, qrCodeId={}, schoolName={}, role={}, 原QrAgent状态={}",
                    userid, qa.getQrCodeId(), qrName, agent.getRole(), prevQaStatus);
            }
        }

        // ── 接待员回池（熔断恢复时确保入池；仅重新上码时可能已在池中，ensureInPool 幂等）──
        if (agent.getRole() == Agent.AgentRole.receptionist && (needsStatusReset || !restoredCodes.isEmpty())) {
            poolService.ensureInPool(userid, 150);
            log.info("接待员恢复后入池: userid={}", userid);
        }

        // ── 同步企微：事务提交后再推送到企微，失败不回滚 DB 改动 ──
        if (!syncedQrIds.isEmpty()) {
            final Set<Long> qrIdsToSync = new HashSet<>(syncedQrIds);
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (Long qrId : qrIdsToSync) {
                            try {
                                qrCodeService.syncQrUsersToWechat(qrId);
                                log.info("恢复后同步企微活码成功: qrCodeId={}", qrId);
                            } catch (Exception e) {
                                log.error("恢复后同步企微活码失败（需手动同步）: qrCodeId={}, error={}",
                                    qrId, e.getMessage());
                            }
                        }
                    }
                });
        }

        // ── 构造详细反馈消息 ──
        StringBuilder msg = new StringBuilder();
        if (needsStatusReset) {
            msg.append("✅ 已恢复 ").append(agent.getName()).append("（").append(userid).append("）为正常状态");
        }
        if (!restoredCodes.isEmpty()) {
            if (needsStatusReset) {
                msg.append("，已恢复到活码：");
            } else {
                msg.append("✅ 已将 ").append(agent.getName()).append("（").append(userid).append("）重新上码：");
            }
            msg.append(String.join("、", restoredCodes));
        }
        if (!otherCodes.isEmpty()) {
            msg.append("；关联活码：");
            msg.append(String.join("、", otherCodes));
        }
        if (agent.getRole() == Agent.AgentRole.receptionist && (needsStatusReset || !restoredCodes.isEmpty())) {
            msg.append("；已自动加入全局池");
        }
        if (qrAgents.isEmpty()) {
            if (needsStatusReset) {
                msg.append("（该员工未关联任何活码，请手动分配到活码）");
            } else {
                msg.append("ℹ️ ").append(agent.getName()).append("（").append(userid).append("）状态正常，未关联任何活码");
            }
        }
        if (msg.isEmpty()) {
            msg.append("ℹ️ ").append(agent.getName()).append("（").append(userid).append("）当前无需操作");
        }
        redirect.addFlashAttribute("message", msg.toString());
        return redirectTo(redirectUrl, view);
    }

    /** 重定向到指定 URL 或 agents 列表页 */
    private String redirectTo(String redirectUrl, String view) {
        if (!redirectUrl.isBlank()) {
            return "redirect:" + redirectUrl;
        }
        return "redirect:/agents?view=" + view;
    }

    /**
     * 计算员工的异常状态标签（供筛选和展示共用）。
     *
     * <p>判定优先级：Agent blocked（含 40098/41054 细分）> Agent melted >
     * Agent warning > Employee wechatStatus 异常。正常员工返回 {@code null}。</p>
     *
     * @param userid      企微员工 userid
     * @param agentMap    全量 Agent 快照
     * @param employeeMap 全量 Employee 快照
     * @return 异常标签如"未实名"，正常则返回 {@code null}
     */
    private String getAnomalyLabel(String userid,
                                   Map<String, Agent> agentMap,
                                   Map<String, Employee> employeeMap) {
        Agent a = agentMap.get(userid);
        Employee emp = employeeMap.get(userid);

        if (a != null && a.getOverallStatus() == Agent.OverallStatus.blocked) {
            String reason = a.getStatusReason();
            if (reason != null && reason.contains("40098")) return "未实名";
            if (reason != null && reason.contains("41054")) return "未加入组织";
            return "已停用";
        }
        if (a != null && a.getOverallStatus() == Agent.OverallStatus.melted) {
            return "已熔断";
        }
        if (a != null && a.getOverallStatus() == Agent.OverallStatus.warning) {
            return "预警";
        }
        if (emp != null && emp.getWechatStatus() != null) {
            int ws = emp.getWechatStatus();
            if (ws == 5) return "已离职";
            if (ws == 4) return "未激活";
            if (ws == 2) return "已禁用";
        }
        return null; // 正常
    }

    // ── 共享工具方法 ──

    /**
     * 手动分页：对已过滤排序的列表做内存切片，包装为 {@link Page}。
     */
    private <T> Page<T> manualPage(List<T> list, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), list.size());
        List<T> slice = start < list.size()
            ? list.subList(start, end) : Collections.emptyList();
        return new PageImpl<>(slice, pageable, list.size());
    }

    /**
     * 批量加载指定 userid 集合的活码名称映射。
     *
     * @return userid → 所在活码学校名称列表（可能为空列表）
     */
    private Map<String, List<String>> loadQrNames(Set<String> userids) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (userids.isEmpty()) return result;

        List<QrAgent> qrAgents = qrAgentRepo.findByAgentUseridIn(userids);
        Set<Long> qrCodeIds = qrAgents.stream()
            .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.active)
            .map(QrAgent::getQrCodeId)
            .collect(Collectors.toSet());
        Map<Long, String> qrNameMap = qrCodeIds.isEmpty()
            ? Collections.emptyMap()
            : qrCodeRepo.findAllById(qrCodeIds).stream()
                .collect(Collectors.toMap(QrCode::getId, QrCode::getSchoolName, (a, b) -> a));
        for (QrAgent qa : qrAgents) {
            if (qa.getStatus() == QrAgent.AgentStatus.active) {
                String schoolName = qrNameMap.getOrDefault(qa.getQrCodeId(),
                    "活码#" + qa.getQrCodeId());
                result.computeIfAbsent(qa.getAgentUserid(), k -> new ArrayList<>())
                    .add(schoolName);
            }
        }
        return result;
    }

    /**
     * 解析全量视图的 wechatStatus 筛选参数。
     *
     * @return 1/2/4/5 表示对应企微状态，null 表示不过滤
     */
    private Integer parseWechatStatus(String status) {
        if (status.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(status);
            if (parsed == 1 || parsed == 2 || parsed == 4 || parsed == 5) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /**
     * 异常标签 → CSS class 映射。
     */
    private String getAnomalyClass(String label) {
        if (label == null) return null;
        return switch (label) {
            case "已离职", "已熔断", "已停用", "未实名", "未加入组织" -> "bg-danger";
            default -> "bg-warning text-dark";
        };
    }

    // ── 全量视图 ──

    /**
     * 全量员工视图 — 分页展示所有已同步的企微员工（含离职/禁用等不可用状态）。
     */
    private String listAllView(Model model, String keyword, String status,
                                String anomaly, Pageable pageable) {
        long totalEmployeeCount = employeeRepo.count();

        // 解析 wechatStatus 筛选（全量视图下 status 参数含义不同）
        final Integer wechatStatusFilter = parseWechatStatus(status);

        boolean anomalyFilterActive = !anomaly.isBlank();

        // 提前加载 Agent 全量快照（关键词匹配 + 异常标签计算都需要）
        Map<String, Agent> allAgentMap = agentRepo.findAll().stream()
            .collect(Collectors.toMap(Agent::getUserid, a -> a, (a, b) -> a));

        Page<Employee> empPage;

        // ── 分支：按关键词 / 状态 / 异常筛选 ──
        if (keyword.isBlank()) {
            // 无关键词
            if (!anomalyFilterActive) {
                // 纯 DB 分页（无异常筛选）
                empPage = wechatStatusFilter != null
                    ? employeeRepo.findByWechatStatusOrderByName(wechatStatusFilter, pageable)
                    : employeeRepo.findAllByOrderByName(pageable);
            } else {
                // 有异常筛选 → 全量加载 + Java 过滤 + 手动分页
                List<Employee> preFiltered = wechatStatusFilter != null
                    ? employeeRepo.findByWechatStatusOrderByName(wechatStatusFilter)
                    : employeeRepo.findAllByOrderByName();
                List<Employee> matched = preFiltered.stream()
                    .filter(e -> {
                        Map<String, Employee> empMap = Map.of(e.getUserid(), e);
                        return anomaly.equals(getAnomalyLabel(e.getUserid(), allAgentMap, empMap));
                    })
                    .toList();
                empPage = manualPage(matched, pageable);
            }
        } else {
            // 有关键词 → 收集匹配 userid
            Set<String> keywordUserIds = new LinkedHashSet<>();
            for (Employee emp : employeeRepo.findByNameContaining(keyword)) {
                keywordUserIds.add(emp.getUserid());
            }
            for (Employee emp : employeeRepo.findByUseridContaining(keyword)) {
                keywordUserIds.add(emp.getUserid());
            }
            for (Agent a : agentRepo.findByNameContaining(keyword)) {
                keywordUserIds.add(a.getUserid());
            }

            if (keywordUserIds.isEmpty()) {
                empPage = Page.empty(pageable);
            } else {
                List<String> matchList = new ArrayList<>(keywordUserIds);
                List<Employee> keywordMatched = employeeRepo.findByUseridIn(matchList);

                // 应用 wechatStatus 筛选
                if (wechatStatusFilter != null) {
                    keywordMatched = keywordMatched.stream()
                        .filter(e -> wechatStatusFilter.equals(e.getWechatStatus()))
                        .toList();
                }

                // 应用异常筛选
                if (anomalyFilterActive) {
                    keywordMatched = keywordMatched.stream()
                        .filter(e -> {
                            Map<String, Employee> empMap = Map.of(e.getUserid(), e);
                            return anomaly.equals(getAnomalyLabel(e.getUserid(), allAgentMap, empMap));
                        })
                        .toList();
                }

                // 按姓名排序后手动分页
                keywordMatched = keywordMatched.stream()
                    .sorted(Comparator.comparing(Employee::getName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                    .toList();
                empPage = manualPage(keywordMatched, pageable);
            }
        }

        // ── 当前页的 userid 集合 ──
        Set<String> pageUserIds = empPage.getContent().stream()
            .map(Employee::getUserid)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // ── 批量加载 Agent 和 Pool 数据 ──
        Map<String, Agent> pageAgentMap = pageUserIds.isEmpty()
            ? Collections.emptyMap()
            : agentRepo.findAllById(pageUserIds).stream()
                .collect(Collectors.toMap(Agent::getUserid, a -> a, (a, b) -> a));
        Map<String, GlobalAgentPool> pagePoolMap = pageUserIds.isEmpty()
            ? Collections.emptyMap()
            : poolRepo.findByAgentUseridIn(new ArrayList<>(pageUserIds)).stream()
                .collect(Collectors.toMap(GlobalAgentPool::getAgentUserid, p -> p, (a, b) -> a));
        Map<String, Employee> pageEmpMap = empPage.getContent().stream()
            .collect(Collectors.toMap(Employee::getUserid, e -> e, (a, b) -> a));

        // ── 活码名称 ──
        Map<String, List<String>> agentQrNames = loadQrNames(pageUserIds);

        // ── 构建 AllEmployeeRow 列表 ──
        List<AllEmployeeRow> rows = new ArrayList<>();
        for (Employee emp : empPage.getContent()) {
            Agent agent = pageAgentMap.get(emp.getUserid());
            GlobalAgentPool pool = pagePoolMap.get(emp.getUserid());

            // 解析显示名称
            String displayName = emp.getName();
            if ((displayName == null || displayName.isEmpty()) && agent != null) {
                displayName = agent.getName();
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = emp.getUserid();
            }

            String anomalyLabel = getAnomalyLabel(emp.getUserid(), pageAgentMap, pageEmpMap);

            rows.add(AllEmployeeRow.builder()
                .userid(emp.getUserid())
                .name(displayName)
                .active(emp.getActive())
                .wechatStatus(emp.getWechatStatus())
                .agentOverallStatus(agent != null ? agent.getOverallStatus().name() : null)
                .agentRole(agent != null ? agent.getRole().name() : null)
                .poolStatus(pool != null ? pool.getStatus().name() : null)
                .dailyCurrent(pool != null ? pool.getDailyCurrent() : null)
                .dailyMax(pool != null ? pool.getDailyMax() : null)
                .sortOrder(pool != null ? pool.getSortOrder() : null)
                .anomalyLabel(anomalyLabel)
                .anomalyClass(anomalyLabel != null ? getAnomalyClass(anomalyLabel) : null)
                .statusReason(agent != null ? agent.getStatusReason() : null)
                .qrCodeNames(agentQrNames.getOrDefault(emp.getUserid(), List.of()))
                .build());
        }

        model.addAttribute("view", "all");
        model.addAttribute("allEmployeePage", empPage);
        model.addAttribute("allEmployeeRows", rows);
        model.addAttribute("totalEmployeeCount", totalEmployeeCount);
        model.addAttribute("keyword", keyword);
        model.addAttribute("statusFilter", status);
        model.addAttribute("anomalyFilter", anomaly);
        model.addAttribute("title", "员工管理");
        return "agent/list";
    }
}
