package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DistrictManagerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DistrictManagerController.class)
@Import(SecurityConfig.class)
@DisplayName("区县负责人页渲染：编辑模态框仍改写为更新接口")
class DistrictManagerControllerRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private DistrictManagerService districtManagerService;
    @MockBean private QrCodeRepository qrCodeRepository;
    @MockBean private EmployeeRepository employeeRepository;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("区县负责人页包含把 #editForm 指向更新接口的脚本")
    void pageKeepsEditFormActionScript() throws Exception {
        when(districtManagerService.findAll()).thenReturn(Collections.emptyList());
        when(qrCodeRepository.findDistinctRegionCity()).thenReturn(Collections.emptyList());
        when(qrCodeRepository.findDistinctRegionDistrict()).thenReturn(Collections.emptyList());
        when(employeeRepository.findAllByActiveTrueOrderByName()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/district-managers"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("'/admin/district-managers/' + id + '/update'")));
    }
}
