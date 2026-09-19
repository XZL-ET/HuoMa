package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.FormSubmissionRepository;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.GradeTextbookCoverService;
import com.bookstore.qrcode.service.SchoolSelectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FormController.class)
@Import(SecurityConfig.class)
@DisplayName("表单 — 县区码选校接口匿名放行")
class FormControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private FormTemplateRepository formTemplateRepo;
    @MockBean private FormSubmissionRepository submissionRepo;
    @MockBean private CustomerRepository customerRepo;
    @MockBean private QrCodeGroupRepository groupRepo;
    @MockBean private SchoolSelectionService schoolSelectionService;
    @MockBean private FormTemplateService formTemplateService;
    @MockBean private GradeTextbookCoverService gradeTextbookCoverService;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;
    @MockBean private EmployeeRepository employeeRepo;

    @Test
    @DisplayName("匿名访问选校接口返回 200（县区码三级级联 fetch）")
    void schools_匿名可访问() throws Exception {
        when(schoolSelectionService.listSchools(any(), anyString()))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/form/schools")
                        .param("qrCodeId", "1")
                        .param("category", "小学"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名访问年级接口返回 200（县区码三级级联 fetch）")
    void grades_匿名可访问() throws Exception {
        when(schoolSelectionService.listGrades(anyString()))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/form/grades")
                        .param("category", "小学"))
                .andExpect(status().isOk());
    }
}
