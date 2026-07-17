package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.SchoolDetailDTO;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.DistrictManagerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchoolService 学校自助查询")
class SchoolServiceTest {

    @Mock private SchoolRepository schoolRepository;
    @Mock private QrCodeRepository qrCodeRepository;
    @Mock private DistrictManagerRepository districtManagerRepository;
    @Mock private SystemConfigRepository systemConfigRepository;
    @Mock private WecomApiClient wecomApiClient;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private SchoolService schoolService;

    @Test
    @DisplayName("getSchoolDetail — 活码可用时返回学校详情")
    void shouldReturnSchoolDetailWithQrAvailable() {
        School school = School.builder()
                .schoolId("BJ-001").schoolName("北京第一中学")
                .regionCity("北京").regionDistrict("海淀区").hasQrcode(true).build();
        QrCode qrCode = QrCode.builder()
                .id(1L).schoolId("BJ-001").qrUrl("https://qr.example.com/BJ-001.png")
                .status(QrCode.QrCodeStatus.active).build();

        when(schoolRepository.findBySchoolIdAndDeletedFalse("BJ-001")).thenReturn(Optional.of(school));
        when(qrCodeRepository.findBySchoolId("BJ-001")).thenReturn(Optional.of(qrCode));
        when(qrCodeRepository.findFirstServiceAgentName(1L)).thenReturn("张老师");

        SchoolDetailDTO detail = schoolService.getSchoolDetail("BJ-001");

        assertThat(detail.getSchoolName()).isEqualTo("北京第一中学");
        assertThat(detail.isQrAvailable()).isTrue();
        assertThat(detail.getQrUrl()).isEqualTo("https://qr.example.com/BJ-001.png");
        assertThat(detail.getContactName()).isEqualTo("张老师");
    }

    @Test
    @DisplayName("getSchoolDetail — 学校不存在抛异常")
    void shouldThrowWhenSchoolNotFound() {
        when(schoolRepository.findBySchoolIdAndDeletedFalse("NOT-EXIST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> schoolService.getSchoolDetail("NOT-EXIST"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("getSchoolDetail — 活码不可用时走区县负责人降级链")
    void shouldFallbackToDistrictManagerWhenQrUnavailable() {
        School school = School.builder()
                .schoolId("BJ-001").schoolName("北京第一中学")
                .regionCity("北京").regionDistrict("海淀区").build();
        QrCode qrCode = QrCode.builder()
                .id(1L).schoolId("BJ-001")
                .status(QrCode.QrCodeStatus.no_agent).build(); // 无可用客服

        DistrictManager dm = DistrictManager.builder()
                .id(1L).regionCity("北京").regionDistrict("海淀区")
                .managerUserid("manager1").managerName("李主任")
                .qrUrl("https://qr.example.com/fallback.png").build();

        when(schoolRepository.findBySchoolIdAndDeletedFalse("BJ-001")).thenReturn(Optional.of(school));
        when(qrCodeRepository.findBySchoolId("BJ-001")).thenReturn(Optional.of(qrCode));
        when(qrCodeRepository.findFirstServiceAgentName(1L)).thenReturn(null);
        when(districtManagerRepository.findByRegionCityAndRegionDistrict("北京", "海淀区"))
                .thenReturn(Optional.of(dm));

        SchoolDetailDTO detail = schoolService.getSchoolDetail("BJ-001");

        assertThat(detail.isQrAvailable()).isFalse();
        assertThat(detail.getFallbackManagerName()).isEqualTo("李主任");
        assertThat(detail.getFallbackQrUrl()).isEqualTo("https://qr.example.com/fallback.png");
        assertThat(detail.isGlobalFallback()).isFalse();
    }

    @Test
    @DisplayName("getSchoolDetail — 无区县负责人时降级到全局联系人")
    void shouldFallbackToGlobalContactWhenNoDistrictManager() {
        School school = School.builder()
                .schoolId("BJ-001").schoolName("北京第一中学")
                .regionCity("北京").regionDistrict("海淀区").build();
        QrCode qrCode = QrCode.builder()
                .id(1L).schoolId("BJ-001")
                .status(QrCode.QrCodeStatus.paused).build();

        when(schoolRepository.findBySchoolIdAndDeletedFalse("BJ-001")).thenReturn(Optional.of(school));
        when(qrCodeRepository.findBySchoolId("BJ-001")).thenReturn(Optional.of(qrCode));
        when(qrCodeRepository.findFirstServiceAgentName(1L)).thenReturn(null);
        when(districtManagerRepository.findByRegionCityAndRegionDistrict("北京", "海淀区"))
                .thenReturn(Optional.empty());
        when(systemConfigRepository.findById("global_contact_name"))
                .thenReturn(Optional.of(new SystemConfig("global_contact_name", "全局联系人名称", "火马客服", null)));
        when(systemConfigRepository.findById("global_contact_qr_url"))
                .thenReturn(Optional.of(new SystemConfig("global_contact_qr_url", "全局联系人二维码URL", "", null)));

        SchoolDetailDTO detail = schoolService.getSchoolDetail("BJ-001");

        assertThat(detail.getFallbackManagerName()).isEqualTo("火马客服");
        assertThat(detail.isGlobalFallback()).isTrue();
    }

    @Test
    @DisplayName("getCities — 返回城市列表及区县数")
    void shouldReturnCitiesWithDistrictCount() {
        when(schoolRepository.findDistinctCities()).thenReturn(List.of("北京", "上海"));
        when(schoolRepository.findDistrictCountsByCity("北京"))
                .thenReturn((List) List.of(new Object[]{"海淀区", 5L}, new Object[]{"朝阳区", 3L}));
        when(schoolRepository.findDistrictCountsByCity("上海"))
                .thenReturn((List) List.of(new Object[]{"浦东新区", 2L}));

        var cities = schoolService.getCities();

        assertThat(cities).hasSize(2);
        assertThat(cities.get(0).getCityName()).isEqualTo("北京");
        assertThat(cities.get(0).getDistrictCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("searchSchools — 关键词搜索")
    void shouldSearchSchoolsByKeyword() {
        when(schoolRepository.searchByKeyword("第一"))
                .thenReturn(List.of(School.builder().schoolName("北京第一中学").build()));

        var schools = schoolService.searchSchools("第一");

        assertThat(schools).hasSize(1);
        assertThat(schools.get(0).getSchoolName()).isEqualTo("北京第一中学");
    }

    @Test
    @DisplayName("updateGlobalConfig — 更新系统配置")
    void shouldUpdateGlobalConfig() {
        SystemConfig existing = new SystemConfig();
        existing.setConfigKey("global_contact_name");
        when(systemConfigRepository.findById("global_contact_name")).thenReturn(Optional.of(existing));

        schoolService.updateGlobalConfig("global_contact_name", "新名称");

        verify(systemConfigRepository).save(any(SystemConfig.class));
    }
}
