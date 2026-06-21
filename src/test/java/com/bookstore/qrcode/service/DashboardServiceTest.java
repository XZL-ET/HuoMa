package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.DashboardStatsDTO;
import com.bookstore.qrcode.entity.DailyReport;
import com.bookstore.qrcode.repository.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 仪表盘统计")
class DashboardServiceTest {

    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private GlobalAgentPoolRepository poolRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private AgentAlertRepository alertRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private DailyReportRepository dailyReportRepo;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    @Disabled("getDashboardStats uses CompletableFuture + parallel streams — needs @SpringBootTest")
    @DisplayName("getDashboardStats — 返回完整的 DashboardStatsDTO")
    void shouldReturnDashboardStats() {
        when(qrCodeRepo.count()).thenReturn(10L);
        when(poolRepo.count()).thenReturn(15L);
        when(customerRepo.count()).thenReturn(1000L);
        when(customerRepo.countByAddTimeBetween(any(), any())).thenReturn(50L);
        when(customerRepo.countByAddTimeBetweenAndStatus(any(), any(), any())).thenReturn(5L);
        when(alertRepo.countByCreatedAtBetween(any(), any())).thenReturn(3L);
        when(agentRepo.countByOverallStatus(any())).thenReturn(0L);
        when(employeeRepo.count()).thenReturn(20L);
        when(dailyReportRepo.findFirstByOrderByDateDesc())
                .thenReturn(java.util.Optional.empty());

        DashboardStatsDTO stats = dashboardService.getDashboardStats();

        assertThat(stats.getTotalQrCodes()).isEqualTo(10);
        assertThat(stats.getTotalAgents()).isEqualTo(20);
    }

    @Test
    @DisplayName("getDailyReportsForExport — 返回日报列表")
    void shouldReturnDailyReportsForExport() {
        when(dailyReportRepo.findByDateBetweenOrderByDateAsc(any(), any()))
                .thenReturn(List.of(new DailyReport(), new DailyReport()));

        List<DailyReport> reports = dashboardService.getDailyReportsForExport(
                LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(reports).hasSize(2);
    }

    @Test
    @Disabled("getTrendData uses Live data merge — needs @SpringBootTest")
    @DisplayName("getTrendData — 返回趋势数据结构")
    void shouldReturnTrendData() {
        when(dailyReportRepo.findByDateBetweenOrderByDateAsc(any(), any()))
                .thenReturn(List.of());
        when(customerRepo.countByAddTimeBetween(any(), any())).thenReturn(10L);
        when(alertRepo.countByCreatedAtBetween(any(), any())).thenReturn(1L);

        Map<String, Object> trends = dashboardService.getTrendData(7);

        assertThat(trends).containsKeys("labels", "adds", "alerts");
    }
}
