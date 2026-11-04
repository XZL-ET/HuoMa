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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
@DisplayName("部门准入页渲染")
class DepartmentWhitelistPageRenderTest {

    @Autowired private MockMvc mockMvc;

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
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /agents/departments 正常渲染")
    void rendersPage() throws Exception {
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));
        when(whitelistRepo.findAllByOrderByDeptIdAsc()).thenReturn(List.of());

        mockMvc.perform(get("/agents/departments"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("部门准入")));
    }
}
