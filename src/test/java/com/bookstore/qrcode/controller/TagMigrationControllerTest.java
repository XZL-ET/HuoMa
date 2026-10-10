package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GradeTagMigrationProgressRepository;
import com.bookstore.qrcode.service.GradeTagMigrationService;
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
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(TagMigrationController.class)
@Import(SecurityConfig.class)
class TagMigrationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private GradeTagMigrationService service;
    @MockBean private GradeTagMigrationProgressRepository progressRepo;
    @MockBean private EmployeeRepository employeeRepo;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    private GradeTagMigrationService.WecomTagIndex emptyIndex() {
        return new GradeTagMigrationService.WecomTagIndex(
            Map.of(), Map.of(), Map.of(), Set.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("看板页渲染 Phase D 校验、待删清单与进度计数")
    void dashboardRenders() throws Exception {
        var index = emptyIndex();
        when(service.loadWecomTagIndex()).thenReturn(index);
        when(service.planSchoolGroupGradeDeletions(index)).thenReturn(List.of());
        when(service.verify(index)).thenReturn(new GradeTagMigrationService.VerifyResult(0, 0, 0));
        when(progressRepo.countByRowClass("R1")).thenReturn(1L);
        when(progressRepo.countByRowClass("R2")).thenReturn(2L);
        when(progressRepo.countByRowClass("R3")).thenReturn(3L);
        when(progressRepo.countByRequiresReview(true)).thenReturn(0L);
        when(progressRepo.findTop200ByRequiresReviewOrderByIdAsc(true)).thenReturn(List.of());

        mockMvc.perform(get("/admin/tag-migration"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/tag-migration"))
            .andExpect(model().attributeExists("deletePlan", "verify", "counts", "reviewList"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST run-a dryRun=true 同步跑 dry-run，并回填 flash 结果")
    void runADryRun() throws Exception {
        when(service.runPhaseA(true)).thenReturn(new GradeTagMigrationService.MigrationReport(
            1, 1, 1, 0, 0, 0, 0, 0, 0, true));

        mockMvc.perform(post("/admin/tag-migration/run-a").with(csrf())
                .param("dryRun", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/tag-migration"))
            .andExpect(flash().attributeExists("message"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST run-a dryRun=false 只投递后台任务（HTTP 线程不等 30 分钟）")
    void runAAsync() throws Exception {
        mockMvc.perform(post("/admin/tag-migration/run-a").with(csrf())
                .param("dryRun", "false"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/tag-migration"))
            .andExpect(flash().attributeExists("message"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("已登录但非 ADMIN 访问看板被拒（403）")
    void requiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/tag-migration")).andExpect(status().isForbidden());
    }
}
