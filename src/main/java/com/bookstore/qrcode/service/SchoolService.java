package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.SchoolCityDTO;
import com.bookstore.qrcode.dto.SchoolDetailDTO;
import com.bookstore.qrcode.dto.SchoolDistrictDTO;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.DistrictManagerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 学校自助查询核心服务。
 * <p>
 * 提供市州/区县/学校三级查询、活码详情组装、
 * 以及区县负责人/全局联系人活码的自动创建与降级逻辑。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final QrCodeRepository qrCodeRepository;
    private final DistrictManagerRepository districtManagerRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final WecomApiClient wecomApiClient;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // 市州 & 区县查询（带缓存）
    // ========================================================================

    @Cacheable(value = "cities", unless = "#result == null || #result.isEmpty()")
    public List<SchoolCityDTO> getCities() {
        List<String> cityNames = schoolRepository.findDistinctCities();
        return cityNames.stream().map(city -> {
            // 统计该市下所有未删除学校属于多少个不同的区县
            List<Object[]> districtCounts = schoolRepository.findDistrictCountsByCity(city);
            long distinctDistricts = districtCounts.size();
            return new SchoolCityDTO(city, distinctDistricts);
        }).collect(Collectors.toList());
    }

    @Cacheable(value = "districts", unless = "#result == null || #result.isEmpty()")
    public List<SchoolDistrictDTO> getDistricts(String city) {
        List<Object[]> rows = schoolRepository.findDistrictCountsByCity(city);
        return rows.stream()
                .map(row -> new SchoolDistrictDTO((String) row[0], (Long) row[1]))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // 学校查询
    // ========================================================================

    public List<School> getSchools(String city, String district) {
        return schoolRepository.findByRegionCityAndRegionDistrictAndDeletedFalseOrderBySchoolName(city, district);
    }

    public List<School> searchSchools(String keyword) {
        return schoolRepository.searchByKeyword(keyword);
    }

    // ========================================================================
    // 学校详情（核心：LEFT JOIN qr_code + 降级链）
    // ========================================================================

    public SchoolDetailDTO getSchoolDetail(String schoolId) {
        School school = schoolRepository.findBySchoolIdAndDeletedFalse(schoolId)
                .orElseThrow(() -> new NoSuchElementException("学校不存在: " + schoolId));

        Optional<QrCode> qrCodeOpt = qrCodeRepository.findBySchoolId(schoolId);

        SchoolDetailDTO.SchoolDetailDTOBuilder builder = SchoolDetailDTO.builder()
                .schoolId(school.getSchoolId())
                .schoolName(school.getSchoolName())
                .regionCity(school.getRegionCity())
                .regionDistrict(school.getRegionDistrict());

        if (qrCodeOpt.isPresent()) {
            QrCode qr = qrCodeOpt.get();
            builder.hasQrcode(true)
                   .qrStatus(qr.getStatus().name())
                   .qrUrl(qr.getQrUrl());
            // 取第一个 active 的服务老师作为联系人
            String contactName = qrCodeRepository.findFirstServiceAgentName(qr.getId());
            builder.contactName(contactName != null ? contactName : "");
        } else {
            builder.hasQrcode(false).qrStatus(null).qrUrl(null).contactName("");
        }

        // 如果活码不可用，走降级链
        boolean qrAvailable = builder.build().isQrAvailable();
        if (!qrAvailable) {
            applyFallback(builder, school.getRegionCity(), school.getRegionDistrict());
        }

        return builder.build();
    }

    // ========================================================================
    // 降级链：区县负责人 → 全局联系人
    // ========================================================================

    private void applyFallback(SchoolDetailDTO.SchoolDetailDTOBuilder builder,
                                String city, String district) {
        // 第一级：区县负责人
        Optional<DistrictManager> dmOpt =
                districtManagerRepository.findByRegionCityAndRegionDistrict(city, district);
        if (dmOpt.isPresent()) {
            DistrictManager dm = dmOpt.get();
            String qrUrl = ensureManagerQrCode(dm);
            builder.fallbackManagerName(dm.getManagerName())
                   .fallbackQrUrl(qrUrl)
                   .isGlobalFallback(false);
            return;
        }

        // 第二级：全局联系人
        builder.fallbackManagerName(getGlobalConfig("global_contact_name"))
               .fallbackQrUrl(ensureGlobalContactQrCode())
               .isGlobalFallback(true);
    }

    // ========================================================================
    // 负责人活码自动创建
    // ========================================================================

    private String ensureManagerQrCode(DistrictManager dm) {
        if (dm.getQrUrl() != null && !dm.getQrUrl().isEmpty()) {
            return dm.getQrUrl();
        }
        // 自动创建
        try {
            String requestJson = buildContactWayJson(dm.getManagerUserid(), "school_fallback_" + dm.getId());
            JsonNode resp = wecomApiClient.createContactWay(requestJson);
            String configId = resp.get("config_id").asText();
            String qrUrl = resp.get("qr_code").asText();

            dm.setQrConfigId(configId);
            dm.setQrUrl(qrUrl);
            districtManagerRepository.save(dm);

            log.info("Created fallback QR for district manager: {} (district={}-{})",
                    dm.getManagerName(), dm.getRegionCity(), dm.getRegionDistrict());
            return qrUrl;
        } catch (Exception e) {
            log.error("Failed to create fallback QR for district manager: {}", dm.getManagerUserid(), e);
            return null; // 创建失败返回 null，前端展示空状态
        }
    }

    private String ensureGlobalContactQrCode() {
        String existingUrl = getGlobalConfig("global_contact_qr_url");
        if (existingUrl != null && !existingUrl.isEmpty()) {
            return existingUrl;
        }
        // 全局联系人的活码手动在后台创建（无关联企微 userid）
        log.warn("Global contact QR not configured — please create manually in admin panel");
        return null;
    }

    private String buildContactWayJson(String userid, String state) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", 1);        // 单人
            body.put("scene", 2);       // 联系我
            body.put("state", state);
            body.put("user", List.of(userid));
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build contact way JSON", e);
        }
    }

    // ========================================================================
    // 全局联系人配置
    // ========================================================================

    public String getGlobalContactName() {
        return getGlobalConfig("global_contact_name");
    }

    public String getGlobalContactQrUrl() {
        return getGlobalConfig("global_contact_qr_url");
    }

    private String getGlobalConfig(String key) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .orElse("");
    }

    public void updateGlobalConfig(String key, String value) {
        SystemConfig config = systemConfigRepository.findById(key)
                .orElse(new SystemConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        systemConfigRepository.save(config);
    }
}
