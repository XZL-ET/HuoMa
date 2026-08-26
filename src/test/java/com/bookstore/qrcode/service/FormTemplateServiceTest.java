package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
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
@Import(FormTemplateService.class)
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("FormTemplateService 县区码默认模板")
class FormTemplateServiceTest {

    @Autowired private FormTemplateService service;
    @Autowired private FormTemplateRepository templateRepo;

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
}
