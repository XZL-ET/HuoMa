package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.EmployeeSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工管理页面控制器 — 展示全局员工池。
 *
 * <p>展示全局池中所有员工的当前状态、日用量、优先级，
 * 以及每人在哪些活码上担任接待员。</p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/agents")
@RequiredArgsConstructor
public class AgentController {

    private final GlobalAgentPoolRepository poolRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeSyncService employeeSyncService;

    /**
     * GET {@code /agents} — 全局员工池列表。
     *
     * <p>数据维度：
     * <ul>
     *   <li>按状态分组（standby / full / blocked），组内按 sortOrder 排序</li>
     *   <li>每人显示所在活码列表（取 active 状态的 QrAgent 关联）</li>
     *   <li>显示全局日用量 vs 日上限</li>
     * </ul>
     */
    @GetMapping
    public String list(Model model) {
        // ── 全局池所有员工按 sortOrder 排序 ──
        List<GlobalAgentPool> all = poolRepo.findAll();
        all.sort(Comparator.comparingInt(GlobalAgentPool::getSortOrder));

        // ── 统计 ──
        long standbyCount = all.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.standby).count();
        long fullCount = all.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.full).count();
        long blockedCount = all.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.blocked).count();

        model.addAttribute("poolEntries", all);
        model.addAttribute("standbyCount", standbyCount);
        model.addAttribute("fullCount", fullCount);
        model.addAttribute("blockedCount", blockedCount);

        // ── 每人对应的活码列表（userid -> 活码名称列表） ──
        Map<String, List<String>> agentQrNames = new HashMap<>();
        // 批量查询：收集所有 agent entries，一次性查所有 QrAgent 关联
        List<QrAgent> allQrAgents = qrAgentRepo.findAll();
        // QrCode ID → name 映射
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
        model.addAttribute("agentQrNames", agentQrNames);

        model.addAttribute("title", "员工管理");
        return "agent/list";
    }

    /**
     * POST {@code /agents/sync} — 手动触发：从企微通讯录同步新员工到全局池。
     *
     * <p>已在池中的员工不会重复添加。新员工排在队尾，日上限默认 200。</p>
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
            redirect.addFlashAttribute("error", "同步失败: " + e.getMessage());
        }
        return "redirect:/agents";
    }
}
