package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.EmployeeSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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

        // ── 状态筛选参数解析 ──
        GlobalAgentPool.PoolStatus statusFilter = null;
        if (!status.isBlank()) {
            try {
                statusFilter = GlobalAgentPool.PoolStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {
                // 非法状态值 → 忽略，等同于"全部"
            }
        }

        // ── 分页查询（按筛选条件选择查询方法）──
        Page<GlobalAgentPool> poolPage;
        if (!keyword.isBlank()) {
            // 收集匹配 userid 集合：
            // 1) 池中 userid 模糊匹配（DB 层 LIKE，不加载全量实体）
            // 2) Agent 表姓名搜索
            // 3) Employee 表姓名搜索
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

            if (!keywordUserIds.isEmpty()) {
                List<String> matchList = new ArrayList<>(keywordUserIds);
                poolPage = statusFilter != null
                    ? poolRepo.findByAgentUseridInAndStatusOrderBySortOrder(matchList, statusFilter, pageable)
                    : poolRepo.findByAgentUseridInOrderBySortOrder(matchList, pageable);
            } else {
                // 无匹配 → 返回空页
                poolPage = Page.empty(pageable);
            }
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

        // ── 构建异常状态标签映射（userid → 异常描述）──
        // 优先级：Agent blocked/melted > Agent warning > Employee wechatStatus 异常
        Map<String, String> agentAnomalyMap = new LinkedHashMap<>();
        Map<String, String> agentAnomalyClassMap = new LinkedHashMap<>(); // bg-danger / bg-warning
        for (GlobalAgentPool p : poolPage.getContent()) {
            String uid = p.getAgentUserid();
            Agent a = agentMap.get(uid);
            Employee emp = employeeMap.get(uid);

            if (a != null && a.getOverallStatus() == Agent.OverallStatus.blocked) {
                String reason = a.getStatusReason();
                if (reason != null && reason.contains("40098")) {
                    agentAnomalyMap.put(uid, "未实名");
                } else if (reason != null && reason.contains("41054")) {
                    agentAnomalyMap.put(uid, "未加入组织");
                } else {
                    agentAnomalyMap.put(uid, "已停用");
                }
                agentAnomalyClassMap.put(uid, "bg-danger");
            } else if (a != null && a.getOverallStatus() == Agent.OverallStatus.melted) {
                agentAnomalyMap.put(uid, "已熔断");
                agentAnomalyClassMap.put(uid, "bg-danger");
            } else if (a != null && a.getOverallStatus() == Agent.OverallStatus.warning) {
                agentAnomalyMap.put(uid, "预警");
                agentAnomalyClassMap.put(uid, "bg-warning text-dark");
            } else if (emp != null && emp.getWechatStatus() != null) {
                int ws = emp.getWechatStatus();
                if (ws == 5) {
                    agentAnomalyMap.put(uid, "已离职");
                    agentAnomalyClassMap.put(uid, "bg-danger");
                } else if (ws == 4) {
                    agentAnomalyMap.put(uid, "未激活");
                    agentAnomalyClassMap.put(uid, "bg-warning text-dark");
                } else if (ws == 2) {
                    agentAnomalyMap.put(uid, "已禁用");
                    agentAnomalyClassMap.put(uid, "bg-warning text-dark");
                }
                // ws == 1 (已激活) → 不添加异常标签
            }
        }

        model.addAttribute("poolPage", poolPage);
        model.addAttribute("agentNameMap", agentNameMap);
        model.addAttribute("agentQrNames", agentQrNames);
        model.addAttribute("agentAnomalyMap", agentAnomalyMap);
        model.addAttribute("agentAnomalyClassMap", agentAnomalyClassMap);
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
}
