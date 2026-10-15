package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.QrCodeGroupService;
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

@WebMvcTest(QrCodeGroupController.class)
@Import(SecurityConfig.class)
@DisplayName("联盟管理页渲染：编辑表单 action 归属自身接口")
class QrCodeGroupControllerRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private QrCodeGroupService groupService;
    @MockBean private FormTemplateService formTemplateService;
    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private EmployeeRepository employeeRepository;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("联盟管理页含自身 action 脚本，且不含 district-managers 劫持")
    void pageKeepsOwnEditFormAction() throws Exception {
        when(groupService.search(any(), any())).thenReturn(Page.<QrCodeGroup>empty());
        when(formTemplateService.listAll()).thenReturn(Collections.emptyList());
        when(qrCodeRepo.findUnassignedTreeProjection()).thenReturn(Collections.emptyList());
        when(qrCodeRepo.findAllTreeProjection()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/groups"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "getElementById('editForm').action = '/admin/groups/' + id + '/update'")))
                .andExpect(content().string(not(containsString("/admin/district-managers/"))));
    }
}
