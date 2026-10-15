package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.SchoolCategoryService;
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
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SchoolCategoryController.class)
@Import(SecurityConfig.class)
@DisplayName("分类管理页渲染：编辑表单 action 归属自身接口")
class SchoolCategoryControllerRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SchoolCategoryService categoryService;
    @MockBean private SchoolCategoryRepository categoryRepo;
    @MockBean private SchoolRepository schoolRepo;
    @MockBean private FormTemplateService formTemplateService;
    @MockBean private EmployeeRepository employeeRepository;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("分类管理页含自身 action 脚本，且不含 district-managers 劫持")
    void pageKeepsOwnEditFormAction() throws Exception {
        when(categoryRepo.findAllByOrderBySortOrderAscName()).thenReturn(Collections.emptyList());
        when(formTemplateService.listAll()).thenReturn(Collections.emptyList());
        when(schoolRepo.countSchoolsByCategory()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "getElementById('editForm').action = '/admin/categories/' + id + '/update'")))
                .andExpect(content().string(not(containsString("/admin/district-managers/"))));
    }
}
