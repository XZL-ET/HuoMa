package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.EmployeeSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
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
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeSyncService employeeSyncService;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;

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
                       @PageableDefault(size = 50) Pageable pageable) {

        // ── 统计（COUNT 查询，不依赖分页）──
        long standbyCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
        long fullCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.full);
        long blockedCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.blocked);

        // ── 构建员工姓名映射 + Employee/Agent 快照（供模板展示异常状态）──
        Map<String, String> agentNameMap = new LinkedHashMap<>();
        Map<String, Employee> employeeMap = new LinkedHashMap<>();
        for (Employee emp : employeeRepo.findAll()) {
            agentNameMap.putIfAbsent(emp.getUserid(), emp.getName());
            employeeMap.putIfAbsent(emp.getUserid(), emp);
        }
        Map<String, Agent> agentMap = new LinkedHashMap<>();
        for (Agent agent : agentRepo.findAll()) {
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
            for (Agent a : agentRepo.findAll()) {
                if (a.getName() != null && a.getName().contains(keyword)) {
                    keywordUserIds.add(a.getUserid());
                }
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
                int start = (int) pageable.getOffset();
                int end = Math.min(start + pageable.getPageSize(), matched.size());
                List<GlobalAgentPool> slice = start < matched.size()
                    ? matched.subList(start, end) : Collections.emptyList();
                poolPage = new PageImpl<>(slice, pageable, matched.size());
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
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), matched.size());
            List<GlobalAgentPool> slice = start < matched.size()
                ? matched.subList(start, end) : Collections.emptyList();
            poolPage = new PageImpl<>(slice, pageable, matched.size());
        } else {
            poolPage = statusFilter != null
                ? poolRepo.findByStatusOrderBySortOrder(statusFilter, pageable)
                : poolRepo.findAllByOrderBySortOrder(pageable);
        }

        // ── 每人对应的活码列表（userid -> 活码名称列表）──
        Map<String, List<String>> agentQrNames = new LinkedHashMap<>();
        List<QrAgent> allQrAgents = qrAgentRepo.findAll();
        Map<Long, String> qrNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getId, QrCode::getSchoolName, (a, b) -> a));

        for (QrAgent qa : allQrAgents) {
            if (qa.getStatus() == QrAgent.AgentStatus.active) {
                String schoolName = qrNameMap.getOrDefault(qa.getQrCodeId(),
                    "活码#" + qa.getQrCodeId());
                agentQrNames.computeIfAbsent(qa.getAgentUserid(), k -> new ArrayList<>())
                    .add(schoolName);
            }
        }

        // ── 构建当前页的异常状态标签映射（复用 getAnomalyLabel 统一逻辑）──
        Map<String, String> agentAnomalyMap = new LinkedHashMap<>();
        Map<String, String> agentAnomalyClassMap = new LinkedHashMap<>();
        for (GlobalAgentPool p : poolPage.getContent()) {
            String label = getAnomalyLabel(p.getAgentUserid(), agentMap, employeeMap);
            if (label != null) {
                agentAnomalyMap.put(p.getAgentUserid(), label);
                agentAnomalyClassMap.put(p.getAgentUserid(),
                    "已离职".equals(label) || "已熔断".equals(label)
                    || "已停用".equals(label) || "未实名".equals(label)
                    || "未加入组织".equals(label) ? "bg-danger" : "bg-warning text-dark");
            }
        }

        model.addAttribute("poolPage", poolPage);
        model.addAttribute("agentNameMap", agentNameMap);
        model.addAttribute("agentQrNames", agentQrNames);
        model.addAttribute("agentAnomalyMap", agentAnomalyMap);
        model.addAttribute("agentAnomalyClassMap", agentAnomalyClassMap);
        model.addAttribute("anomalyFilter", anomaly);
        model.addAttribute("standbyCount", standbyCount);
        model.addAttribute("fullCount", fullCount);
        model.addAttribute("blockedCount", blockedCount);
        model.addAttribute("keyword", keyword);
        model.addAttribute("statusFilter", status);
        model.addAttribute("totalCount", standbyCount + fullCount + blockedCount);
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
}
