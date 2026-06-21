package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("DashboardController 数据看板")
class DashboardControllerTest {

    private MockMvc mockMvc;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService)).build();
    }

    @Test
    @DisplayName("GET /dashboard — 返回看板页面")
    void shouldReturnDashboardPage() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"));
    }

    @Test
    @DisplayName("GET /dashboard/api/stats — 返回 JSON 统计数据")
    void shouldReturnStatsJson() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("adds", 100L);
        stats.put("alerts", 5L);
        when(dashboardService.gatherStats("today")).thenReturn(stats);

        mockMvc.perform(get("/dashboard/api/stats").param("range", "today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adds").value(100))
                .andExpect(jsonPath("$.alerts").value(5));
    }
}
