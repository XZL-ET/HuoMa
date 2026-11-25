package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StrictDeptController.class)
@Import(SecurityConfig.class)
@DisplayName("严格同部门清退页渲染")
class StrictDeptTemplateRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private StrictDeptCleanupService cleanupService;
    @MockBean private StrictDeptConfigService strictDeptConfigService;
    @MockBean private GlobalAgentPoolRepository poolRepo;
    @MockBean private GlobalAgentPoolService poolService;
    @MockBean private QrAgentRepository qrAgentRepo;
    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private QrCodeService qrCodeService;
    @MockBean private EmployeeSyncService employeeSyncService;
    @MockBean private EmployeeRepository employeeRepo;
    @MockBean private AgentRepository agentRepo;
    @MockBean private OperationLogService operationLogService;
    @MockBean private DepartmentWhitelistService whitelistService;
    @MockBean private DepartmentWhitelistApplyService applyService;
    @MockBean private DepartmentService departmentService;
    @MockBean private PoolAllowedDepartmentRepository whitelistRepo;
    @MockBean private ReceptionistDailyMaxService receptionistDailyMaxService;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /strict-dept 正常渲染")
    void rendersPage() throws Exception {
        when(strictDeptConfigService.isStrictModeEnabled()).thenReturn(true);
        when(strictDeptConfigService.getExemptUserids()).thenReturn(Set.of("svc1"));
        when(cleanupService.countDriftedActiveReceptionists()).thenReturn(3L);

        mockMvc.perform(get("/strict-dept"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("严格同部门")))
            .andExpect(content().string(containsString("预览")))
            .andExpect(content().string(containsString("确认执行清退")));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("非 ADMIN 访问被拒绝")
    void nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/strict-dept"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 缺少 CSRF 被拒绝")
    void postWithoutCsrfForbidden() throws Exception {
        mockMvc.perform(post("/strict-dept/toggle").param("enabled", "true"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 携带 CSRF 正常重定向")
    void postWithCsrfRedirects() throws Exception {
        mockMvc.perform(post("/strict-dept/toggle").param("enabled", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/strict-dept"));
    }
}
