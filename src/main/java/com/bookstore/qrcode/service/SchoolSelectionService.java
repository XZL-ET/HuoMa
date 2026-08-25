package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 县区码选校服务：县区→学校列表、学段→年级枚举、学校→服务老师解析链、发起在职继承。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolSelectionService {

    private final QrCodeRepository qrCodeRepo;
    private final SchoolRepository schoolRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final QrCodeGroupRepository groupRepo;
    private final QrAgentRepository qrAgentRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 县区码 school_id 前缀，用于区分县区码与学校活码 */
    public static final String COUNTY_PREFIX = "county:";

    /** 学段 → 年级枚举（见 spec §2.4） */
    private static final Map<String, List<String>> GRADE_MAP = Map.of(
        "小学", List.of("一年级", "二年级", "三年级", "四年级", "五年级", "六年级"),
        "初中", List.of("初一", "初二", "初三"),
        "高中", List.of("高一", "高二", "高三"),
        "幼儿园", List.of("小班", "中班", "大班")
    );

    public boolean isCountyCode(QrCode qr) {
        return qr != null && qr.getSchoolId() != null
            && qr.getSchoolId().startsWith(COUNTY_PREFIX);
    }

    public List<SchoolOption> listSchools(Long qrCodeId, String categoryName) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null || qr.getRegionDistrict() == null) return List.of();
        String district = qr.getRegionDistrict();

        List<School> schools;
        if (categoryName == null || categoryName.isBlank() || "全部".equals(categoryName)) {
            schools = schoolRepo.findByRegionDistrictAndDeletedFalseOrderBySchoolName(district);
        } else if ("未分类".equals(categoryName)) {
            schools = schoolRepo
                .findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName(district);
        } else {
            Long categoryId = categoryRepo.findByName(categoryName)
                .map(SchoolCategory::getId).orElse(null);
            if (categoryId == null) {
                log.warn("学段未找到对应分类: category={}", categoryName);
                return List.of();
            }
            schools = schoolRepo
                .findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName(district, categoryId);
        }
        return schools.stream()
            .map(s -> new SchoolOption(s.getSchoolId(), s.getSchoolName()))
            .toList();
    }

    public List<String> listGrades(String categoryName) {
        if (categoryName == null) return List.of();
        return GRADE_MAP.getOrDefault(categoryName, List.of());
    }

    public record SchoolOption(String schoolId, String schoolName) {}
    public record TransferTarget(String state, String toUserid) {}
}
