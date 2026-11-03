package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.AlertTriageService;
import com.bookstore.qrcode.service.AlertTriageService.TriageCard;
import com.bookstore.qrcode.service.AlertTriageService.TriageSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import(SecurityConfig.class)
@DisplayName("告警页渲染")
class AlertPageRenderTest {

    @Autowired MockMvc mockMvc;
    @MockBean AlertService alertService;
    @MockBean AlertTriageService triageService;
    @MockBean AgentRepository agentRepo;
    @MockBean QrCodeRepository qrCodeRepo;
    @MockBean EmployeeRepository employeeRepository;
    @MockBean(name = "rateLimitRedisTemplate") StringRedisTemplate rateLimitRedisTemplate;
    @MockBean LoginSuccessHandler loginSuccessHandler;

    /** 分页查询（size=20）与卡片统计（size=2000）使用不同的 Pageable，借此区分两次 findAlerts。 */
    private static Pageable pageableOfSize(int size) {
        return argThat(p -> p != null && p.getPageSize() == size);
    }

    /** 页面按属性访问统计字段，键必须齐全，否则 Thymeleaf 抛异常。 */
    private static Map<String, Long> stats() {
        return Map.of(
            "totalCount", 10L, "openCount", 3L, "resolvedCount", 4L,
            "autoResolvedCount", 3L, "highCount", 2L, "mediumCount", 5L,
            "lowCount", 3L, "todayCount", 1L, "weekCount", 6L);
    }

    @Test
    @WithMockUser
    @DisplayName("有卡片时渲染标题/建议动作/去处理，且不再出现一键解决全部")
    void shouldRenderTriageCards() throws Exception {
        var card = new TriageCard("qr_agent_anomaly", "异常员工（巡检）", "查看异常员工并处理",
            AgentAlert.AlertSeverity.high, 3, 2, 3, LocalDateTime.now(),
            "/qrcodes/verify-all-agents", "查看异常员工");
        var summary = new TriageSummary(List.of(card), List.of(), 3, 0);
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(stats());
        when(triageService.buildSummary(any())).thenReturn(summary);

        mockMvc.perform(get("/alerts"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("异常员工（巡检）")))
            .andExpect(content().string(containsString("查看异常员工并处理")))
            .andExpect(content().string(containsString("/qrcodes/verify-all-agents")))
            .andExpect(content().string(containsString("归档可自动恢复/过期项")))
            .andExpect(content().string(not(containsString("一键解决全部"))));
    }

    @Test
    @WithMockUser
    @DisplayName("明细表渲染最近发生/次数列与去处理深链，无链接类型不渲染按钮")
    void shouldRenderAlertRowsWithDeepLink() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        AgentAlert linked = AgentAlert.builder()
            .id(42L)
            .alertType("qr_agent_anomaly")
            .severity(AgentAlert.AlertSeverity.high)
            .status(AgentAlert.AlertStatus.open)
            .agentUserid("zhangsan")
            .detail("{\"reason\":\"link-row-marker\"}")
            .autoAction(AgentAlert.AutoAction.none)
            .occurrenceCount(3)
            .qrCodeId(7L)
            .createdAt(now.minusHours(2))
            .lastSeenAt(now)
            .build();
        AgentAlert noLink = AgentAlert.builder()
            .id(43L)
            .alertType("wechat_48002")
            .severity(AgentAlert.AlertSeverity.low)
            .status(AgentAlert.AlertStatus.open)
            .detail("{\"reason\":\"nolink-row-marker\"}")
            .autoAction(AgentAlert.AutoAction.none)
            .occurrenceCount(1)
            .createdAt(now.minusHours(1))
            .lastSeenAt(now)
            .build();

        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), pageableOfSize(20)))
            .thenReturn(new PageImpl<>(List.of(linked, noLink)));
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), pageableOfSize(2000)))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(stats());
        when(triageService.buildSummary(any()))
            .thenReturn(new TriageSummary(List.of(), List.of(), 0, 0));

        String html = mockMvc.perform(get("/alerts"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("最近发生")))
            .andExpect(content().string(containsString("次数")))
            .andExpect(content().string(containsString("活码员工异常")))
            .andExpect(content().string(containsString("/qrcodes/verify-all-agents")))
            .andExpect(content().string(containsString("企微 48002 能力缺失")))
            .andReturn().getResponse().getContentAsString();

        String linkedRow = rowOf(html, "link-row-marker");
        org.assertj.core.api.Assertions.assertThat(linkedRow)
            .as("有深链的行渲染去处理按钮").contains("去处理");

        // 无深链的类型（wechat_48002）所在行不得出现任何链接
        String noLinkRow = rowOf(html, "nolink-row-marker");
        org.assertj.core.api.Assertions.assertThat(noLinkRow)
            .as("无深链的行不渲染链接").doesNotContain("<a ");
    }

    @Test
    @WithMockUser
    @DisplayName("「全部状态」翻页链接继续带上空的 status 参数")
    void shouldKeepAllStatusFilterOnPagination() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<AgentAlert> rows = java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> AgentAlert.builder()
                .id((long) i + 1)
                .alertType("wechat_48002")
                .severity(AgentAlert.AlertSeverity.low)
                .status(AgentAlert.AlertStatus.resolved)
                .autoAction(AgentAlert.AutoAction.none)
                .occurrenceCount(1)
                .createdAt(now)
                .build())
            .toList();
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), pageableOfSize(20)))
            .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), 21));
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), pageableOfSize(2000)))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(stats());
        when(triageService.buildSummary(any()))
            .thenReturn(new TriageSummary(List.of(), List.of(), 0, 0));

        String html = mockMvc.perform(get("/alerts").param("status", ""))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .as("翻页链接必须保留 status=all（全部状态），否则会退回默认 open 视图")
            .containsPattern("href=\"/alerts\\?status=all[^\"]*page=1[^\"]*\"");
    }

    private static String rowOf(String html, String marker) {
        for (String row : html.split("<tr")) {
            if (row.contains(marker)) {
                return row;
            }
        }
        return "";
    }
}
