package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSchoolController.class)
@Import(SecurityConfig.class)
@DisplayName("学校列表页渲染：编辑表单 action 不得被 layout 脚本劫持")
class AdminSchoolControllerRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SchoolRepository schoolRepository;
    @MockBean private QrCodeRepository qrCodeRepository;
    @MockBean private QrAccessLogRepository qrAccessLogRepository;
    @MockBean private SchoolCategoryRepository categoryRepo;
    @MockBean private EmployeeRepository employeeRepository;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("学校列表页不得包含劫持 #editForm 的 district-managers 脚本")
    void schoolsPageDoesNotHijackEditForm() throws Exception {
        when(schoolRepository.search(any(), any(), any(), any(), any())).thenReturn(Page.<School>empty());
        when(qrAccessLogRepository.findSchoolAccessStats()).thenReturn(Collections.emptyList());
        when(categoryRepo.findAllByOrderBySortOrderAscName()).thenReturn(Collections.emptyList());
        when(schoolRepository.findDistinctCities()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/schools"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/admin/schools/save\"")))
                .andExpect(content().string(not(containsString("/admin/district-managers/"))));
    }
}
