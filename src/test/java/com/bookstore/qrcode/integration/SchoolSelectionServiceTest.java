package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.SchoolSelectionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Import(WecomApiMockConfig.class)
@DisplayName("县区选校服务测试")
class SchoolSelectionServiceTest extends BaseIntegrationTest {

    @Autowired private SchoolSelectionService service;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrCodeGroupRepository groupRepo;

    private QrCode countyQr;

    @BeforeEach
    void setUp() {
        schoolRepo.deleteAll();
        groupRepo.deleteAll();
        qrCodeRepo.deleteAll();

        countyQr = new QrCode();
        countyQr.setSchoolName("白银区");
        countyQr.setSchoolId("county:白银区");
        countyQr.setRegionCity("白银市");
        countyQr.setRegionDistrict("白银区");
        countyQr.setStatus(QrCode.QrCodeStatus.active);
        countyQr.setScene(Scene.daily_push);
        countyQr.setCreateMode(QrCode.CreateMode.manual);
        countyQr = qrCodeRepo.save(countyQr);
    }

    @Test
    void 检测县区码() {
        assertThat(service.isCountyCode(countyQr)).isTrue();
        QrCode schoolQr = new QrCode();
        schoolQr.setSchoolName("白银一小");
        schoolQr.setSchoolId("s1");
        schoolQr.setRegionCity("白银市");
        schoolQr.setRegionDistrict("白银区");
        schoolQr = qrCodeRepo.save(schoolQr);
        assertThat(service.isCountyCode(schoolQr)).isFalse();
    }

    @Test
    void 按学段列出县区学校() {
        SchoolCategory primary = categoryRepo.save(SchoolCategory.builder()
            .name("小学").sortOrder(1).build());
        schoolRepo.save(School.builder().schoolId("s1").schoolName("白银一小")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s2").schoolName("白银一中")
            .regionCity("白银市").regionDistrict("白银区").categoryId(null).deleted(false).build());

        List<SchoolSelectionService.SchoolOption> primarySchools =
            service.listSchools(countyQr.getId(), "小学");
        assertThat(primarySchools).extracting(SchoolSelectionService.SchoolOption::schoolName)
            .containsExactly("白银一小");

        List<SchoolSelectionService.SchoolOption> all = service.listSchools(countyQr.getId(), "全部");
        assertThat(all).hasSize(2);
    }

    @Test
    void 按学段返回年级枚举() {
        assertThat(service.listGrades("小学"))
            .containsExactly("一年级", "二年级", "三年级", "四年级", "五年级", "六年级");
        assertThat(service.listGrades("幼儿园")).containsExactly("小班", "中班", "大班");
        assertThat(service.listGrades("不存在的学段")).isEmpty();
    }
}
