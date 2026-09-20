package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.job.DeletionReportJob;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.DeletionReportService;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.ServiceTeacherDailyMaxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminSystemConfigController.class)
@Import(SecurityConfig.class)
@DisplayName("系统配置 — 服务老师日限接口")
class AdminSystemConfigControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SystemConfigRepository configRepository;
    @MockBean private ServiceTeacherDailyMaxService serviceTeacherDailyMaxService;
    @MockBean private DeletionReportService deletionReportService;
    @MockBean private DeletionReportJob deletionReportJob;
    @MockBean private FormTemplateService formTemplateService;
    @MockBean private EmployeeRepository employeeRepo;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET 页面回显当前默认值")
    void indexExposesDefaultValue() throws Exception {
        when(configRepository.findAll()).thenReturn(Collections.emptyList());
        when(serviceTeacherDailyMaxService.resolveDefault()).thenReturn(300);
        when(deletionReportService.getEffectiveRecipients()).thenReturn("admin1,admin2");
        when(deletionReportService.resolvePushTime()).thenReturn(LocalTime.of(9, 0));
        when(formTemplateService.resolveImageCopy()).thenReturn(Collections.emptyMap());

        mockMvc.perform(get("/admin/system-config"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/system-config"))
                .andExpect(model().attribute("serviceTeacherDailyMax", 300))
                .andExpect(model().attribute("deletionReportRecipients", "admin1,admin2"))
                .andExpect(model().attribute("deletionReportTime", "09:00"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 只保存默认值，不应用到全部")
    void saveOnlyDoesNotApply() throws Exception {
        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "300")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService).saveDefault(300);
        verify(serviceTeacherDailyMaxService, never()).applyToAll(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST apply=true 保存并应用到全部")
    void saveAndApply() throws Exception {
        when(serviceTeacherDailyMaxService.applyToAll(300)).thenReturn(3370);

        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "300")
                        .param("apply", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService).saveDefault(300);
        verify(serviceTeacherDailyMaxService).applyToAll(300);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST value=0 拒绝保存，不调用服务")
    void rejectsZeroValue() throws Exception {
        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "0")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService, never()).saveDefault(anyInt());
        verify(serviceTeacherDailyMaxService, never()).applyToAll(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST value 为负数拒绝保存，不调用服务")
    void rejectsNegativeValue() throws Exception {
        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "-100")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService, never()).saveDefault(anyInt());
        verify(serviceTeacherDailyMaxService, never()).applyToAll(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST value 超过上限 800 拒绝保存，不调用服务")
    void rejectsValueAboveMax() throws Exception {
        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "801")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService, never()).saveDefault(anyInt());
        verify(serviceTeacherDailyMaxService, never()).applyToAll(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST value=800 边界值接受保存")
    void acceptsBoundaryValue() throws Exception {
        mockMvc.perform(post("/admin/system-config/service-teacher-daily-max")
                        .param("value", "800")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(serviceTeacherDailyMaxService).saveDefault(800);
        verify(serviceTeacherDailyMaxService, never()).applyToAll(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 保存日报接收人并规范化逗号分隔")
    void savesDeletionReportRecipients() throws Exception {
        when(configRepository.findByConfigKey(DeletionReportService.RECIPIENTS_CONFIG_KEY))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/system-config/deletion-report-recipients")
                        .param("recipients", " zhangsan , lisi,,wangwu ")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        ArgumentCaptor<SystemConfig> captor = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("zhangsan,lisi,wangwu");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 手动推送昨日日报")
    void pushesDeletionReport() throws Exception {
        when(deletionReportService.reportWithLock(any(LocalDate.class))).thenReturn(3);

        mockMvc.perform(post("/admin/system-config/deletion-report/push")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(deletionReportService).reportWithLock(
                LocalDate.now(DeletionReportService.REPORT_ZONE).minusDays(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 手动推送时锁被占用仍重定向")
    void pushesDeletionReportWhenBusy() throws Exception {
        when(deletionReportService.reportWithLock(any(LocalDate.class)))
                .thenReturn(DeletionReportService.LOCK_BUSY);

        mockMvc.perform(post("/admin/system-config/deletion-report/push")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 保存日报推送时间并重新排程")
    void savesDeletionReportTime() throws Exception {
        when(configRepository.findByConfigKey(DeletionReportService.TIME_CONFIG_KEY))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/system-config/deletion-report-time")
                        .param("time", "18:30")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        ArgumentCaptor<SystemConfig> captor = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("18:30");
        verify(deletionReportJob).reschedule(LocalTime.of(18, 30));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 推送时间非法时不保存、不排程")
    void rejectsInvalidTime() throws Exception {
        mockMvc.perform(post("/admin/system-config/deletion-report-time")
                        .param("time", "abc")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(configRepository, never()).save(any(SystemConfig.class));
        verify(deletionReportJob, never()).reschedule(any(LocalTime.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 保存图片版文案（6 项）")
    void savesImageCopy() throws Exception {
        mockMvc.perform(post("/admin/system-config/image-copy")
                        .param("headingLine1", "您是 {school} 的学生")
                        .param("headingLine2", "请选择教材")
                        .param("subtitle", "欢迎填写")
                        .param("gradeHint", "请选年级")
                        .param("buttonText", "下一步")
                        .param("privacyNotice", "隐私说明")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/system-config"));

        verify(configRepository, times(6)).save(any(SystemConfig.class));
    }
}
