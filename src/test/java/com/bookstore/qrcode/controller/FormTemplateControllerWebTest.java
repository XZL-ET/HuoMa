package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.FileStorageService;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.GradeTextbookCoverService;
import com.bookstore.qrcode.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FormTemplateController.class)
@Import(SecurityConfig.class)
@DisplayName("表单模板 — 年级封面图上传")
class FormTemplateControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private FormTemplateService templateService;
    @MockBean private SystemConfigRepository systemConfigRepo;
    @MockBean private GradeTextbookCoverService gradeTextbookCoverService;
    @MockBean private TagService tagService;
    @MockBean private FileStorageService fileStorageService;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;
    @MockBean private EmployeeRepository employeeRepo;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("上传封面图 POST 无需 CSRF token（页面内 fetch 调用）")
    void uploadGradeCover_无需CSRF() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png", new byte[]{1, 2, 3, 4});
        mockMvc.perform(multipart("/admin/form-templates/grade-cover")
                        .file(file)
                        .param("grade", "一年级"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("删除封面图 POST 无需 CSRF token（页面内 fetch 调用）")
    void deleteGradeCover_无需CSRF() throws Exception {
        mockMvc.perform(post("/admin/form-templates/grade-cover/delete")
                        .param("grade", "一年级"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("企微标签组候选接口 GET 返回 groups 字段")
    void wecomTagGroups_返回组名列表() throws Exception {
        when(tagService.listWecomGroupNames()).thenReturn(java.util.List.of("年级", "班级"));
        mockMvc.perform(get("/admin/form-templates/wecom-tag-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0]").value("年级"))
                .andExpect(jsonPath("$.groups[1]").value("班级"));
    }
}
