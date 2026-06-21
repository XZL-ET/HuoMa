package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("SchoolRepository 自定义查询")
class SchoolRepositoryTest {

    @Autowired
    private SchoolRepository schoolRepository;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void setUp() {
        em.persistFlushFind(School.builder()
                .schoolId("BJ-001")
                .schoolName("北京第一中学")
                .regionCity("北京")
                .regionDistrict("海淀区")
                .hasQrcode(true)
                .deleted(false)
                .build());
        em.persistFlushFind(School.builder()
                .schoolId("BJ-002")
                .schoolName("北京第二中学")
                .regionCity("北京")
                .regionDistrict("朝阳区")
                .hasQrcode(false)
                .deleted(false)
                .build());
        em.persistFlushFind(School.builder()
                .schoolId("SH-001")
                .schoolName("上海第一中学")
                .regionCity("上海")
                .regionDistrict("浦东新区")
                .hasQrcode(true)
                .deleted(false)
                .build());
        em.persistFlushFind(School.builder()
                .schoolId("DEL-001")
                .schoolName("已删除学校")
                .regionCity("北京")
                .regionDistrict("海淀区")
                .hasQrcode(false)
                .deleted(true)
                .build());
    }

    @Test
    @DisplayName("findDistinctCities — 去重城市列表")
    void findDistinctCities() {
        List<String> cities = schoolRepository.findDistinctCities();
        assertThat(cities).containsExactly("上海", "北京");
    }

    @Test
    @DisplayName("findDistrictCountsByCity — 城市下区县学校数量")
    void findDistrictCountsByCity() {
        List<Object[]> counts = schoolRepository.findDistrictCountsByCity("北京");
        assertThat(counts).hasSize(2); // 海淀区 and 朝阳区 (已删除的不计入)
    }

    @Test
    @DisplayName("findByRegionCityAndRegionDistrictAndDeletedFalseOrderBySchoolName — 按市州区县查询")
    void findByCityAndDistrict() {
        List<School> schools = schoolRepository
                .findByRegionCityAndRegionDistrictAndDeletedFalseOrderBySchoolName("北京", "海淀区");
        assertThat(schools).hasSize(1);
        assertThat(schools.get(0).getSchoolName()).isEqualTo("北京第一中学");
    }

    @Test
    @DisplayName("searchByKeyword — 关键词搜索")
    void searchByKeyword() {
        // 搜索"第一"应匹配两个第一中学
        List<School> results = schoolRepository.searchByKeyword("第一");
        assertThat(results).hasSize(2);
        assertThat(results).extracting("schoolName")
                .contains("北京第一中学", "上海第一中学");
    }

    @Test
    @DisplayName("findBySchoolIdAndDeletedFalse — 按 school_id 查询未删除的")
    void findBySchoolIdAndDeletedFalse() {
        assertThat(schoolRepository.findBySchoolIdAndDeletedFalse("BJ-001")).isPresent();
        assertThat(schoolRepository.findBySchoolIdAndDeletedFalse("DEL-001")).isEmpty(); // 已删除
        assertThat(schoolRepository.findBySchoolIdAndDeletedFalse("NOT-EXIST")).isEmpty();
    }

    @Test
    @DisplayName("findByFilters — 分页筛选")
    void findByFilters() {
        Page<School> page = schoolRepository.findByFilters(
                "北京", null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2); // 2 北京+未删除

        page = schoolRepository.findByFilters(
                "北京", "海淀区", PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getSchoolName()).isEqualTo("北京第一中学");
    }

    @Test
    @Disabled("MySQL native query: BOOLEAN vs INTEGER type comparison not supported in H2")
    @DisplayName("syncHasQrcodeFromQrCode — native query 同步 has_qrcode 状态")
    void syncHasQrcodeFromQrCode() {
        // 创建关联 QR code
        QrCode qr = QrCode.builder()
                .schoolName("北京第一中学")
                .schoolId("BJ-001")
                .regionCity("北京")
                .regionDistrict("海淀区")
                .status(QrCode.QrCodeStatus.active)
                .rotateMode(QrCode.RotateMode.auto)
                .createMode(QrCode.CreateMode.manual)
                .build();
        em.persistAndFlush(qr);

        int updated = schoolRepository.syncHasQrcodeFromQrCode();
        assertThat(updated).isPositive();

        em.clear();
        School school = schoolRepository.findBySchoolIdAndDeletedFalse("BJ-001").orElseThrow();
        assertThat(school.getHasQrcode()).isTrue();
    }

    @Test
    @Disabled("MySQL native query: INSERT IGNORE not supported in H2")
    @DisplayName("importSchoolsFromQrCode — native query 从 QR code 导入学校")
    void importSchoolsFromQrCode() {
        QrCode qr = QrCode.builder()
                .schoolName("新导入学校")
                .schoolId("NEW-001")
                .regionCity("广州")
                .regionDistrict("天河区")
                .status(QrCode.QrCodeStatus.active)
                .rotateMode(QrCode.RotateMode.auto)
                .createMode(QrCode.CreateMode.manual)
                .build();
        em.persistAndFlush(qr);

        int imported = schoolRepository.importSchoolsFromQrCode();
        assertThat(imported).isEqualTo(1);

        em.clear();
        assertThat(schoolRepository.findBySchoolIdAndDeletedFalse("NEW-001")).isPresent();
    }
}
