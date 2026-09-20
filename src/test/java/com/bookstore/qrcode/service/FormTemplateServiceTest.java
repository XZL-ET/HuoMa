package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.config.ObjectMapperTestConfig;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({FormTemplateService.class, ObjectMapperTestConfig.class})
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("FormTemplateService 县区码默认模板")
class FormTemplateServiceTest {

    @Autowired private FormTemplateService service;
    @Autowired private FormTemplateRepository templateRepo;
    @Autowired private SystemConfigRepository systemConfigRepo;

    @Test
    void ensureCountyTemplate_幂等_两次调用返回同一条记录() {
        FormTemplate first = service.ensureCountyTemplate();
        FormTemplate second = service.ensureCountyTemplate();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(templateRepo.findAll().stream()
                .filter(t -> "县区码默认模板".equals(t.getName())).count()).isEqualTo(1);
    }

    @Test
    void ensureCountyTemplate_fields与tagMapping正确() {
        FormTemplate t = service.ensureCountyTemplate();
        assertThat(t.getFields()).isEqualTo("[]");
        assertThat(t.getTagMapping()).isEqualTo("{\"grade\":\"tag\",\"class\":\"tag\"}");
    }

    @Test
    void ensureCountyTemplate_预置同名后不新建() {
        FormTemplate existing = templateRepo.save(FormTemplate.builder()
                .name("县区码默认模板").fields("[]").tagMapping("{}").build());
        FormTemplate got = service.ensureCountyTemplate();
        assertThat(got.getId()).isEqualTo(existing.getId());
        assertThat(templateRepo.findAll().stream()
                .filter(t -> "县区码默认模板".equals(t.getName())).count()).isEqualTo(1);
    }

    @Test
    void resolveImageCopy_缺省返回默认文案() {
        Map<String, String> copy = service.resolveImageCopy();
        assertThat(copy.get("imageHeadingLine1")).isEqualTo("您是 {school} 的学生");
        assertThat(copy.get("imageHeadingLine2")).isEqualTo("请您选择正在使用的教材版本");
        assertThat(copy.get("imageSubtitle")).isEmpty();
        assertThat(copy.get("imageGradeHint")).isEqualTo("请选择年级");
        assertThat(copy.get("imageButtonText")).isEqualTo("确认");
        assertThat(copy.get("privacyNotice")).contains("新华书店");
    }

    @Test
    void resolveImageCopy_配置后返回配置值() {
        systemConfigRepo.save(SystemConfig.builder()
                .configKey(FormTemplateService.IMAGE_BUTTON_TEXT_KEY)
                .configName("图片版按钮文字")
                .configValue("下一步").build());
        Map<String, String> copy = service.resolveImageCopy();
        assertThat(copy.get("imageButtonText")).isEqualTo("下一步");
        assertThat(copy.get("imageHeadingLine1")).isEqualTo("您是 {school} 的学生");
    }

    @Test
    void resolveImageCopy_配置空白回退默认() {
        systemConfigRepo.save(SystemConfig.builder()
                .configKey(FormTemplateService.IMAGE_HEADING_LINE1_KEY)
                .configValue("   ").build());
        Map<String, String> copy = service.resolveImageCopy();
        assertThat(copy.get("imageHeadingLine1")).isEqualTo("您是 {school} 的学生");
    }
}
