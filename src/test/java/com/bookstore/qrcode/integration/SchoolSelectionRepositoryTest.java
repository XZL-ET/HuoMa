package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Import(WecomApiMockConfig.class)
@DisplayName("县区学校查询 Repository 测试")
class SchoolSelectionRepositoryTest extends BaseIntegrationTest {

    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private QrCodeGroupRepository groupRepo;

    @BeforeEach
    void clean() {
        schoolRepo.deleteAll();
        groupRepo.deleteAll();
    }

    @Test
    void 按区县和分类查学校() {
        SchoolCategory primary = categoryRepo.save(SchoolCategory.builder()
            .name("小学").sortOrder(1).build());
        SchoolCategory middle = categoryRepo.save(SchoolCategory.builder()
            .name("初中").sortOrder(2).build());

        schoolRepo.save(School.builder().schoolId("s1").schoolName("白银一小")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s2").schoolName("白银一中")
            .regionCity("白银市").regionDistrict("白银区").categoryId(middle.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s3").schoolName("无分类学校")
            .regionCity("白银市").regionDistrict("白银区").categoryId(null).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s4").schoolName("已删除学校")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(true).build());
        schoolRepo.save(School.builder().schoolId("s5").schoolName("别区小学")
            .regionCity("白银市").regionDistrict("平川区").categoryId(primary.getId()).deleted(false).build());

        List<School> primarySchools =
            schoolRepo.findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName("白银区", primary.getId());
        assertThat(primarySchools).extracting(School::getSchoolName).containsExactly("白银一小");

        List<School> uncategorized =
            schoolRepo.findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName("白银区");
        assertThat(uncategorized).extracting(School::getSchoolName).containsExactly("无分类学校");

        List<School> allInDistrict =
            schoolRepo.findByRegionDistrictAndDeletedFalseOrderBySchoolName("白银区");
        assertThat(allInDistrict).hasSize(3);
    }

    @Test
    void 按组类型查联盟() {
        groupRepo.save(QrCodeGroup.builder().name("白银联盟").regionCity("白银市")
            .regionDistrict("白银区").groupType("alliance")
            .schoolList("白银一小\n白银二小").build());
        groupRepo.save(QrCodeGroup.builder().name("别的组").regionCity("白银市")
            .regionDistrict("白银区").groupType("other").schoolList("x").build());

        List<QrCodeGroup> alliances = groupRepo.findByGroupType("alliance");
        assertThat(alliances).hasSize(1);
        assertThat(alliances.get(0).getName()).isEqualTo("白银联盟");
    }
}
