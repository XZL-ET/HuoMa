package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.config.ObjectMapperTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({FormTemplateService.class, ObjectMapperTestConfig.class})
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("FormTemplateService 图片版模板与版本解析")
class FormTemplateVersionServiceTest {

    @Autowired private FormTemplateService service;
    @MockBean private FormTemplateInsertService insertService;
    @Autowired private FormTemplateRepository templateRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrCodeGroupRepository groupRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private SystemConfigRepository configRepo;

    @BeforeEach
    void stubInsertToDelegateToRepo() {
        // @DataJpaTest 下让 mock 委托真实 save，避免 REQUIRES_NEW 独立提交污染测试事务
        when(insertService.insert(any(FormTemplate.class)))
            .thenAnswer(inv -> templateRepo.save(inv.getArgument(0)));
    }

    private QrCode saveQr() {
        return qrCodeRepo.save(QrCode.builder()
                .schoolName("测试学校").schoolId("s" + System.nanoTime())
                .regionCity("兰州市").regionDistrict("城关区").build());
    }

    @Test
    void ensureImageTemplate_幂等_两次调用返回同一条记录() {
        FormTemplate first = service.ensureImageTemplate();
        FormTemplate second = service.ensureImageTemplate();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(templateRepo.findAll().stream()
                .filter(t -> FormTemplateService.IMAGE_TEMPLATE_NAME.equals(t.getName())).count())
                .isEqualTo(1);
    }

    @Test
    void ensureResendTemplate_年级用七年级非初一() {
        FormTemplate t = service.ensureResendTemplate();
        assertThat(t.getFields())
                .contains("七年级").contains("八年级").contains("九年级")
                .doesNotContain("初一").doesNotContain("初二").doesNotContain("初三");
    }

    @Test
    void ensureImageTemplate_fields含12个年级选项() {
        FormTemplate t = service.ensureImageTemplate();
        assertThat(t.getFields())
                .contains("一年级").contains("八年级").contains("高三");
        assertThat(t.getTagMapping()).isEqualTo("{\"grade\":\"tag\"}");
    }

    @Test
    void resolveTemplateId_image模式_返回图片版模板() {
        configRepo.save(SystemConfig.builder()
                .configKey(FormTemplateService.VERSION_CONFIG_KEY)
                .configValue(FormTemplateService.IMAGE_VERSION).build());
        QrCode qr = saveQr();
        assertThat(service.resolveTemplateId(qr)).isEqualTo(service.ensureImageTemplate().getId());
    }

    @Test
    void resolveTemplateId_image模式_县区码活码不套用图片版() {
        configRepo.save(SystemConfig.builder()
                .configKey(FormTemplateService.VERSION_CONFIG_KEY)
                .configValue(FormTemplateService.IMAGE_VERSION).build());
        FormTemplate custom = templateRepo.save(FormTemplate.builder()
                .name("县区码模板").fields("[]").tagMapping("{}").build());
        QrCode qr = qrCodeRepo.save(QrCode.builder()
                .schoolName("县区码活码").schoolId("county:" + System.nanoTime())
                .regionCity("兰州市").regionDistrict("城关区")
                .formTemplateId(custom.getId()).build());
        assertThat(service.resolveTemplateId(qr)).isEqualTo(custom.getId());
    }

    @Test
    void resolveTemplateId_text模式_按活码formTemplateId() {
        FormTemplate custom = templateRepo.save(FormTemplate.builder()
                .name("自定义模板").fields("[]").tagMapping("{}").build());
        QrCode qr = saveQr();
        qr.setFormTemplateId(custom.getId());
        qr = qrCodeRepo.save(qr);
        assertThat(service.resolveTemplateId(qr)).isEqualTo(custom.getId());
    }

    @Test
    void resolveTemplateId_text模式_活码null时走分组默认() {
        FormTemplate groupTpl = templateRepo.save(FormTemplate.builder()
                .name("分组模板").fields("[]").tagMapping("{}").build());
        QrCodeGroup g = groupRepo.save(QrCodeGroup.builder()
                .name("联盟").regionDistrict("城关区").defaultFormTemplateId(groupTpl.getId()).build());
        QrCode qr = saveQr();
        qr.setGroupId(g.getId());
        qr = qrCodeRepo.save(qr);
        assertThat(service.resolveTemplateId(qr)).isEqualTo(groupTpl.getId());
    }

    @Test
    void resolveTemplateId_text模式_走学校分类默认() {
        FormTemplate catTpl = templateRepo.save(FormTemplate.builder()
                .name("分类模板").fields("[]").tagMapping("{}").build());
        SchoolCategory cat = categoryRepo.save(SchoolCategory.builder()
                .name("分类X").defaultFormTemplateId(catTpl.getId()).build());
        School school = schoolRepo.save(School.builder()
                .schoolId("sc" + System.nanoTime()).schoolName("某校")
                .regionCity("兰州市").regionDistrict("城关区").categoryId(cat.getId()).build());
        QrCode qr = saveQr();
        qr.setSchoolId(school.getSchoolId());
        qr = qrCodeRepo.save(qr);
        assertThat(service.resolveTemplateId(qr)).isEqualTo(catTpl.getId());
    }

    @Test
    void resolveTemplateId_均无配置_返回null() {
        assertThat(service.resolveTemplateId(saveQr())).isNull();
    }

    private QrCode saveQrWithCategory(String categoryName, String gradeStages) {
        SchoolCategory cat = categoryRepo.save(SchoolCategory.builder()
                .name(categoryName).gradeStages(gradeStages).build());
        School school = schoolRepo.save(School.builder()
                .schoolId("sc" + System.nanoTime()).schoolName("某校")
                .regionCity("兰州市").regionDistrict("城关区").categoryId(cat.getId()).build());
        QrCode qr = saveQr();
        qr.setSchoolId(school.getSchoolId());
        return qrCodeRepo.save(qr);
    }

    @Test
    void resolveImageGradeOptions_小学学校_返回六个小学年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("小学", "小学")))
                .containsExactly("一年级", "二年级", "三年级", "四年级", "五年级", "六年级");
    }

    @Test
    void resolveImageGradeOptions_初中学校_返回七年级至九年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("初中", "初中")))
                .containsExactly("七年级", "八年级", "九年级");
    }

    @Test
    void resolveImageGradeOptions_高中学校_返回高中年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("高中", "高中")))
                .containsExactly("高一", "高二", "高三");
    }

    @Test
    void resolveImageGradeOptions_无学校记录_返回全部12年级() {
        assertThat(service.resolveImageGradeOptions(saveQr()))
                .isEqualTo(FormTemplateService.GRADE_OPTIONS);
    }

    @Test
    void resolveImageGradeOptions_学校无学段分类_返回全部12年级() {
        School school = schoolRepo.save(School.builder()
                .schoolId("sc" + System.nanoTime()).schoolName("某校")
                .regionCity("兰州市").regionDistrict("城关区").build());
        QrCode qr = saveQr();
        qr.setSchoolId(school.getSchoolId());
        qr = qrCodeRepo.save(qr);
        assertThat(service.resolveImageGradeOptions(qr))
                .isEqualTo(FormTemplateService.GRADE_OPTIONS);
    }

    @Test
    void resolveImageGradeOptions_幼儿园分类_返回小班中班大班() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("幼儿园", "幼儿园")))
                .containsExactly("小班", "中班", "大班");
    }

    @Test
    void filterImageGradeOptions_小学学校_替换grade选项为小学年级() {
        String fieldsJson = service.ensureImageTemplate().getFields();
        String filtered = service.filterImageGradeOptions(fieldsJson, saveQrWithCategory("小学", "小学"));
        assertThat(filtered).contains("\"一年级\"").contains("\"六年级\"")
                .doesNotContain("七年级").doesNotContain("高一").doesNotContain("高三");
    }

    @Test
    void resolveImageGradeOptions_九年一贯制_返回小学加初中九个年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("九年一贯制", "小学,初中")))
                .containsExactly("一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
                        "七年级", "八年级", "九年级");
    }

    @Test
    void resolveImageGradeOptions_完全中学_返回初中加高中六个年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("完全中学", "初中,高中")))
                .containsExactly("七年级", "八年级", "九年级", "高一", "高二", "高三");
    }

    @Test
    void resolveImageGradeOptions_十二年制_返回全部12年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("十二年制", "小学,初中,高中")))
                .isEqualTo(FormTemplateService.GRADE_OPTIONS);
    }

    @Test
    void resolveImageGradeOptions_幼小_返回幼儿园加小学() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("幼小", "幼儿园,小学")))
                .containsExactly("小班", "中班", "大班",
                        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级");
    }

    @Test
    void resolveImageGradeOptions_幼小初_返回幼儿园加小学加初中() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("幼小初", "幼儿园,小学,初中")))
                .containsExactly("小班", "中班", "大班",
                        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
                        "七年级", "八年级", "九年级");
    }

    @Test
    void resolveImageGradeOptions_中等专业学校_返回高中年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("中等专业学校", "高中")))
                .containsExactly("高一", "高二", "高三");
    }

    @Test
    void resolveImageGradeOptions_分类未配置学段_返回全部12年级() {
        assertThat(service.resolveImageGradeOptions(saveQrWithCategory("某特殊分类", null)))
                .isEqualTo(FormTemplateService.GRADE_OPTIONS);
    }
}
