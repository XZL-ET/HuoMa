package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.StrictDeptCleanupPreview;
import com.bookstore.qrcode.dto.StrictDeptCleanupResult;
import com.bookstore.qrcode.service.DepartmentWhitelistService;
import com.bookstore.qrcode.service.StrictDeptCleanupService;
import com.bookstore.qrcode.service.StrictDeptConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("严格同部门清退控制器")
class StrictDeptControllerTest {

    private MockMvc mockMvc;
    private final StrictDeptCleanupService cleanupService = mock(StrictDeptCleanupService.class);
    private final StrictDeptConfigService configService = mock(StrictDeptConfigService.class);

    @BeforeEach
    void setUp() {
        StrictDeptController controller = new StrictDeptController(cleanupService, configService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST preview 返回 JSON")
    void previewReturnsJson() throws Exception {
        when(cleanupService.preview(isNull())).thenReturn(new StrictDeptCleanupPreview(
            true, Set.of("svc1"), 2, 3, List.of(), List.of(), List.of()));

        mockMvc.perform(post("/strict-dept/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.codeCount").isNumber())
            .andExpect(jsonPath("$.codeCount").value(2))
            .andExpect(jsonPath("$.strictModeEnabled").value(true));
    }

    @Test
    @DisplayName("POST preview 部门树不可用返回 409")
    void previewReturnsConflictWhenTreeUnavailable() throws Exception {
        when(cleanupService.preview(isNull())).thenThrow(
            new DepartmentWhitelistService.DeptTreeUnavailableException("down"));

        mockMvc.perform(post("/strict-dept/preview"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("部门树拉取失败，请稍后重试"));
    }

    @Test
    @DisplayName("POST apply 重定向回清退页并带 flash 统计")
    void applyRedirectsWithFlash() throws Exception {
        when(cleanupService.apply(isNull(), anyString())).thenReturn(
            new StrictDeptCleanupResult(3, 1, 5, 0, 2, List.of()));

        mockMvc.perform(post("/strict-dept/apply"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/strict-dept"))
            .andExpect(flash().attribute("message", containsString("处理 3 码")))
            .andExpect(flash().attribute("message", containsString("撤 active 5 人")));

        verify(cleanupService).apply(isNull(), anyString());
    }

    @Test
    @DisplayName("POST apply 有失败清单时独立透出 applyFailures")
    void applyRedirectsWithFailures() throws Exception {
        when(cleanupService.apply(isNull(), anyString())).thenReturn(
            new StrictDeptCleanupResult(3, 1, 5, 0, 2, List.of("活码 A 轮换进行中，未处理")));

        mockMvc.perform(post("/strict-dept/apply"))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("applyFailures", hasItem(containsString("活码 A 轮换进行中"))));
    }

    @Test
    @DisplayName("POST apply 锁占用时 flash 原样错误")
    void applyLockedRedirectsWithErrorMessage() throws Exception {
        when(cleanupService.apply(isNull(), anyString())).thenThrow(
            new IllegalStateException("另一个清退任务正在进行，请稍后重试"));

        mockMvc.perform(post("/strict-dept/apply"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/strict-dept"))
            .andExpect(flash().attribute("error", "另一个清退任务正在进行，请稍后重试"));
    }

    @Test
    @DisplayName("POST toggle 写入开关并重定向")
    void toggleWritesConfig() throws Exception {
        mockMvc.perform(post("/strict-dept/toggle").param("enabled", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/strict-dept"))
            .andExpect(flash().attribute("message", containsString("已开启")));

        verify(configService).setStrictModeEnabled(true);
    }
}
