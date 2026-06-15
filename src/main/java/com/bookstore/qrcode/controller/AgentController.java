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

        // ── 构建员工姓名映射（Employee 表 + Agent 表双源互补）──
        // Employee 表每 30 分钟从企微通讯录同步，姓名最新最全
        Map<String, String> agentNameMap = new LinkedHashMap<>();
        for (Employee emp : employeeRepo.findAll()) {
            agentNameMap.putIfAbsent(emp.getUserid(), emp.getName());
        }
        // Agent 表补漏：手工创建的员工可能不在 Employee 表中
        for (Agent agent : agentRepo.findAll()) {
            agentNameMap.putIfAbsent(agent.getUserid(), agent.getName());
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

        model.addAttribute("poolPage", poolPage);
        model.addAttribute("agentNameMap", agentNameMap);
        model.addAttribute("agentQrNames", agentQrNames);
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
