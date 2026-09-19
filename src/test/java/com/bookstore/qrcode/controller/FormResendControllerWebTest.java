package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.FormSubmissionRepository;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.PaperLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FormResendController.class)
@Import(SecurityConfig.class)
@DisplayName("补发表单 — 提交接口匿名放行")
class FormResendControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private CustomerRepository customerRepo;
    @MockBean private FormTemplateRepository formTemplateRepo;
    @MockBean private FormSubmissionRepository submissionRepo;
    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private SystemConfigRepository systemConfigRepo;
    @MockBean private FormTemplateService formTemplateService;
    @MockBean private PaperLinkService paperLinkService;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;
    @MockBean private EmployeeRepository employeeRepo;

    @Test
    @DisplayName("匿名提交补发表单无需 CSRF token，返回 200 而非 302/403")
    void submit_匿名可访问() throws Exception {
        mockMvc.perform(post("/api/form/resend-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
