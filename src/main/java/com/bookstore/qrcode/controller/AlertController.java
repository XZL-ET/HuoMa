package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 异常告警中心控制器。
 * <p>
 * 提供告警列表页（分页 + 多维度筛选）、告警详情查看、
 * 手动解决/一键解决等操作。告警数据来源于员工添加失败、欢迎语失败、
 * 熔断、后备池空等系统异常事件。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.10
 */
@Slf4j
@Controller
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;

    /**
     * GET {@code /alerts} — 告警列表页（分页 + 多维度筛选）。
     *
     * <p>支持以下筛选维度：
     * <ul>
     *   <li>status — 告警状态：open / resolved / auto_resolved</li>
     *   <li>severity — 严重程度：low / medium / high</li>
     *   <li>type — 告警类型：add_fail / greeting_fail / melt / empty_backup</li>
     *   <li>agent — 员工账号模糊搜索</li>
     *   <li>startDate / endDate — 时间范围</li>
     * </ul>
     */
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String severity,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String agent,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        // 空字符串 → null
        if (status != null && status.isBlank()) status = null;
        if (severity != null && severity.isBlank()) severity = null;
        if (type != null && type.isBlank()) type = null;
        if (agent != null && agent.isBlank()) agent = null;

        // 解析枚举参数
        AgentAlert.AlertStatus statusFilter = null;
        if (status != null) {
            try { statusFilter = AgentAlert.AlertStatus.valueOf(status); }
            catch (IllegalArgumentException e) { /* 非法值当全部 */ }
        }

        AgentAlert.AlertSeverity severityFilter = null;
        if (severity != null) {
            try { severityFilter = AgentAlert.AlertSeverity.valueOf(severity); }
            catch (IllegalArgumentException e) { /* 非法值当全部 */ }
        }

        // 查询分页数据
        Page<AgentAlert> alertPage = alertService.findAlerts(
            statusFilter, severityFilter, type, agent, null,
            startDate, endDate, PageRequest.of(page, size));

        // 批量加载关联数据 — 员工姓名 + 活码名称
        Set<String> userIds = alertPage.getContent().stream()
            .map(AgentAlert::getAgentUserid)
            .filter(uid -> uid != null && !uid.isEmpty())
            .collect(Collectors.toSet());
        Set<Long> qrIds = alertPage.getContent().stream()
            .map(AgentAlert::getQrCodeId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());

        Map<String, String> agentNameMap = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            agentRepo.findAllById(userIds).forEach(a ->
                agentNameMap.put(a.getUserid(), a.getName()));
        }

        Map<Long, String> qrNameMap = new LinkedHashMap<>();
        if (!qrIds.isEmpty()) {
            qrCodeRepo.findAllById(qrIds).forEach(q ->
                qrNameMap.put(q.getId(), q.getSchoolName()));
        }

        // 统计摘要
        Map<String, Long> stats = alertService.getAlertStats();

        // 分页范围（最多展示 5 个页码）
        int totalPages = alertPage.getTotalPages();
        int current = alertPage.getNumber();
        int pageStart = Math.max(0, current - 2);
        int pageEnd = Math.min(totalPages - 1, current + 2);

        // 模型属性
        model.addAttribute("title", "异常告警");
        model.addAttribute("alertPage", alertPage);
        model.addAttribute("agentNameMap", agentNameMap);
        model.addAttribute("qrNameMap", qrNameMap);
        model.addAttribute("stats", stats);
        model.addAttribute("statusFilter", status);
        model.addAttribute("severityFilter", severity);
        model.addAttribute("typeFilter", type);
        model.addAttribute("agentFilter", agent);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("pageStart", pageStart);
        model.addAttribute("pageEnd", pageEnd);
        model.addAttribute("page", page);
        model.addAttribute("size", size);

        return "alert/list";
    }

    /**
     * POST {@code /alerts/{id}/resolve} — 手动解决单条告警。
     */
    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable Long id,
                          Principal principal,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String severity,
                          @RequestParam(required = false) String type,
                          @RequestParam(required = false) String agent,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                          RedirectAttributes redirect) {
        String adminUser = principal != null ? principal.getName() : "system";
        try {
            alertService.resolveAlert(id, adminUser);
            redirect.addFlashAttribute("message", "告警 #" + id + " 已标记为已解决");
        } catch (Exception e) {
            log.error("解决告警 #{} 失败", id, e);
            redirect.addFlashAttribute("error", "操作失败: " + e.getMessage());
        }
        return buildRedirect(status, severity, type, agent, startDate, endDate, page);
    }

    /**
     * POST {@code /alerts/resolve-all} — 一键解决所有未处理告警。
     */
    @PostMapping("/resolve-all")
    public String resolveAll(Principal principal,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String severity,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String agent,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                             RedirectAttributes redirect) {
        String adminUser = principal != null ? principal.getName() : "system";
        try {
            int count = alertService.resolveAllAlerts(adminUser);
            redirect.addFlashAttribute("message", "已批量解决 " + count + " 条告警");
        } catch (Exception e) {
            log.error("批量解决告警失败", e);
            redirect.addFlashAttribute("error", "操作失败: " + e.getMessage());
        }
        return buildRedirect(status, severity, type, agent, startDate, endDate, page);
    }

    /** 构建保留筛选条件的重定向 URL */
    private String buildRedirect(String status, String severity, String type,
                                  String agent, LocalDate startDate, LocalDate endDate,
                                  int page) {
        StringBuilder sb = new StringBuilder("redirect:/alerts?page=").append(page);
        if (status != null && !status.isBlank()) sb.append("&status=").append(status);
        if (severity != null && !severity.isBlank()) sb.append("&severity=").append(severity);
        if (type != null && !type.isBlank()) sb.append("&type=").append(type);
        if (agent != null && !agent.isBlank()) sb.append("&agent=").append(agent);
        if (startDate != null) sb.append("&startDate=").append(startDate);
        if (endDate != null) sb.append("&endDate=").append(endDate);
        return sb.toString();
    }
}
