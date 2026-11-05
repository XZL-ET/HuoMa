package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.dto.ReleaseItem;
import com.bookstore.qrcode.dto.TimelineEntry;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.service.ReleaseNotesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReleaseNotesController.class)
@Import(SecurityConfig.class)
@DisplayName("版本更新页渲染")
class ReleaseNotesPageRenderTest {

    @Autowired MockMvc mockMvc;
    @MockBean ReleaseNotesService releaseNotesService;
    @MockBean LoginSuccessHandler loginSuccessHandler;
    @MockBean(name = "rateLimitRedisTemplate") StringRedisTemplate rateLimitRedisTemplate;
    @MockBean EmployeeRepository employeeRepository;

    private static List<TimelineEntry> timeline() {
        return List.of(
            new TimelineEntry(LocalDate.of(2026, 11, 5), "接待员日限可自助配置", true,
                List.of(new ReleaseItem("新增", "系统配置页新增「接待员日限」卡片"),
                        new ReleaseItem(null, "修复「应用到全部」报 500 的问题")),
                List.of()),
            new TimelineEntry(LocalDate.of(2026, 11, 4), null, false, List.of(),
                List.of("add department whitelist eligibility service")));
    }

    @Test
    @WithMockUser
    @DisplayName("定稿条目显示标题与带标签的条目，未整理日期折叠显示原始提交")
    void rendersCuratedEntriesAndCollapsedRawCommits() throws Exception {
        when(releaseNotesService.timeline(false)).thenReturn(timeline());
        when(releaseNotesService.buildTime()).thenReturn(Optional.of(LocalDateTime.of(2026, 11, 5, 22, 40)));

        mockMvc.perform(get("/release-notes"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("接待员日限可自助配置")))
            .andExpect(content().string(containsString("系统配置页新增「接待员日限」卡片")))
            .andExpect(content().string(containsString("修复「应用到全部」报 500 的问题")))
            .andExpect(content().string(containsString(">新增<")))
            .andExpect(content().string(containsString("开发记录（未整理）")))
            .andExpect(content().string(containsString("add department whitelist eligibility service")))
            .andExpect(content().string(containsString("当前运行版本")))
            .andExpect(content().string(containsString("2026-11-05 22:40")))
            .andExpect(content().string(containsString("查看更早的更新")));
    }

    @Test
    @WithMockUser
    @DisplayName("all=true 时取全量时间线且不再显示「查看更早的更新」")
    void showsEverythingWhenAllRequested() throws Exception {
        when(releaseNotesService.timeline(true)).thenReturn(timeline());
        when(releaseNotesService.buildTime()).thenReturn(Optional.empty());

        mockMvc.perform(get("/release-notes").param("all", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("接待员日限可自助配置")))
            .andExpect(content().string(not(containsString("查看更早的更新"))))
            .andExpect(content().string(not(containsString("当前运行版本"))));
    }

    @Test
    @WithMockUser
    @DisplayName("没有任何记录时给出空态提示")
    void rendersEmptyState() throws Exception {
        when(releaseNotesService.timeline(false)).thenReturn(List.of());
        when(releaseNotesService.buildTime()).thenReturn(Optional.empty());

        mockMvc.perform(get("/release-notes"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("还没有更新记录")));
    }
}
