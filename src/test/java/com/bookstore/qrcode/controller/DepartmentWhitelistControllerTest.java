package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.DepartmentWhitelistApplyResult;
import com.bookstore.qrcode.dto.DepartmentWhitelistPreview;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("部门准入控制器")
class DepartmentWhitelistControllerTest {

    private MockMvc mockMvc;
    private final DepartmentWhitelistApplyService applyService = mock(DepartmentWhitelistApplyService.class);
    private final DepartmentWhitelistService whitelistService = mock(DepartmentWhitelistService.class);

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
            mock(GlobalAgentPoolRepository.class),
            mock(GlobalAgentPoolService.class),
            mock(QrAgentRepository.class),
            mock(QrCodeRepository.class),
            mock(QrCodeService.class),
            mock(EmployeeSyncService.class),
            mock(EmployeeRepository.class),
            mock(AgentRepository.class),
            mock(OperationLogService.class),
            whitelistService,
            applyService,
            mock(DepartmentService.class),
            mock(PoolAllowedDepartmentRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST preview 返回 JSON")
    void previewReturnsJson() throws Exception {
        when(applyService.preview(List.of(100L))).thenReturn(
            new DepartmentWhitelistPreview(true, List.of(), List.of(), List.of(), 0, List.of()));

        mockMvc.perform(post("/agents/departments/preview").param("deptIds", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST preview 部门树不可用返回 409")
    void previewReturnsConflictWhenTreeUnavailable() throws Exception {
        when(applyService.preview(List.of(100L))).thenThrow(
            new DepartmentWhitelistService.DeptTreeUnavailableException("down"));

        mockMvc.perform(post("/agents/departments/preview").param("deptIds", "100"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("部门树拉取失败，请稍后重试"));
    }

    @Test
    @DisplayName("POST apply 重定向回配置页并展示统计")
    void applyRedirects() throws Exception {
        when(applyService.apply(eq(List.of(100L)), anyString())).thenReturn(
            new DepartmentWhitelistApplyResult(1, 0, 0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(post("/agents/departments/apply").param("deptIds", "100"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/agents/departments"))
            .andExpect(flash().attribute("message", containsString("移出池 0 人")));

        verify(applyService).apply(eq(List.of(100L)), anyString());
    }

    @Test
    @DisplayName("POST disable 重定向并清空白名单")
    void disableRedirectsAndClearsWhitelist() throws Exception {
        mockMvc.perform(post("/agents/departments/disable"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/agents/departments"));

        verify(whitelistService).clearWhitelist();
    }
}
