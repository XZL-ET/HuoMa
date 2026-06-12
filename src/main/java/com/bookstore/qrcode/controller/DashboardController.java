package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentAlertRepository;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据看板页面控制器。
 *
 * <p>展示平台核心运营指标：活码状态分布、全局员工池余量、
 * 今日新增客户数、异常告警数、封号/熔断员工数。</p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final QrCodeRepository qrCodeRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final CustomerRepository customerRepo;
    private final AgentAlertRepository alertRepo;
    private final AgentRepository agentRepo;

    /**
     * GET {@code /dashboard} — 数据看板首页。
     *
     * <p>展示维度：
     * <ul>
     *   <li>总活码数 / 活跃数 / 满员数</li>
     *   <li>全局池 standby / full / blocked 人数</li>
     *   <li>今日新增客户数</li>
     *   <li>今日告警数</li>
     *   <li>封号 / 熔断员工数</li>
     * </ul>
     */
    @GetMapping
    public String index(Model model) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        // ── 活码统计 ──
        long totalQr = qrCodeRepo.count();
        long activeQr = qrCodeRepo.countByStatus(QrCode.QrCodeStatus.active);
        long fullQr = qrCodeRepo.countByStatus(QrCode.QrCodeStatus.full);
        model.addAttribute("totalQr", totalQr);
        model.addAttribute("activeQr", activeQr);
        model.addAttribute("fullQr", fullQr);

        // ── 全局员工池 ──
        long poolStandby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
        long poolFull = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.full);
        long poolBlocked = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.blocked);
        model.addAttribute("poolStandby", poolStandby);
        model.addAttribute("poolFull", poolFull);
        model.addAttribute("poolBlocked", poolBlocked);
        model.addAttribute("poolTotal", poolStandby + poolFull + poolBlocked);

        // ── 今日新增客户 ──
        long todayAdd = customerRepo.countByAddTimeBetween(todayStart, todayEnd);
        model.addAttribute("todayAdd", todayAdd);

        // ── 今日告警 ──
        long todayAlerts = alertRepo.countByCreatedAtBetween(todayStart, todayEnd);
        model.addAttribute("todayAlerts", todayAlerts);

        // ── 异常员工 ──
        long blockedAgents = agentRepo.findByOverallStatus(Agent.OverallStatus.blocked).size();
        long meltedAgents = agentRepo.findByOverallStatus(Agent.OverallStatus.melted).size();
        model.addAttribute("blockedAgents", blockedAgents);
        model.addAttribute("meltedAgents", meltedAgents);

        model.addAttribute("title", "数据看板");
        return "dashboard/index";
    }
}
