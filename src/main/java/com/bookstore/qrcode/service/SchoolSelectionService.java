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

    /**
     * 学校 → 服务老师解析链（见 spec §3.2）：
     * ① 学校有独立活码 → 取该活码的 service 老师；② 否则包在学区码 → 取学区码活码的 service 老师。
     *
     * @return state=目标活码 schoolId（传给 TransferService.initiate 反查活码），toUserid=目标服务老师
     */
    public Optional<TransferTarget> resolveTransferTarget(String schoolId, String schoolName) {
        // ① 一校一码：学校自己有活码
        QrCode schoolQr = qrCodeRepo.findBySchoolId(schoolId).orElse(null);
        if (schoolQr != null) {
            String toUserid = findServiceAgent(schoolQr);
            if (toUserid != null) return Optional.of(new TransferTarget(schoolId, toUserid));
            log.warn("学校 {} 有活码但未配置服务老师，无法转接", schoolName);
            return Optional.empty();
        }
        // ② 学区码兜底：学校名在联盟 schoolList 里
        if (schoolName == null) return Optional.empty();
        for (QrCodeGroup g : groupRepo.findByGroupType("alliance")) {
            if (!containsSchool(g, schoolName)) continue;
            if (g.getQrCodeId() == null) continue;
            QrCode allianceQr = qrCodeRepo.findById(g.getQrCodeId()).orElse(null);
            if (allianceQr == null) continue;
            String toUserid = findServiceAgent(allianceQr);
            if (toUserid != null) return Optional.of(new TransferTarget(allianceQr.getSchoolId(), toUserid));
        }
        log.warn("学校 {} 既无独立活码也不在学区码，留在县区接待员", schoolName);
        return Optional.empty();
    }

    /**
     * 表单提交后发起县区码在职继承：解析目标老师并写入 TRANSFER_STREAM_KEY 异步转接。
     *
     * @return true=已发布转接事件，false=未解析到目标（留在县区接待员）
     */
    public boolean initiateCountyTransfer(Long customerId, String fromUserid,
                                           String externalUserid, String schoolId, String schoolName) {
        Optional<TransferTarget> target = resolveTransferTarget(schoolId, schoolName);
        if (target.isEmpty()) return false;
        TransferTarget t = target.get();
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("customer_id", String.valueOf(customerId));
            event.put("from_userid", fromUserid);
            event.put("to_userid", t.toUserid());
            event.put("external_userid", externalUserid);
            event.put("state", t.state());
            redisTemplate.opsForStream().add(
                RedisConfig.TRANSFER_STREAM_KEY,
                Map.of("event", objectMapper.writeValueAsString(event)));
            log.info("县区码转接事件已发布: customer={}, to={}, state={}",
                customerId, t.toUserid(), t.state());
            return true;
        } catch (Exception e) {
            log.error("发布县区码转接事件失败: customer={}", customerId, e);
            return false;
        }
    }

    /** 取活码第一个未移除的 service/dual 老师 */
    private String findServiceAgent(QrCode qr) {
        return qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .filter(a -> a.getRole() == QrAgent.AgentRole.service
                      || a.getRole() == QrAgent.AgentRole.dual)
            .map(QrAgent::getAgentUserid)
            .findFirst().orElse(null);
    }

    /** schoolList 一行一个学校名，精确匹配整行 */
    private boolean containsSchool(QrCodeGroup g, String schoolName) {
        if (g.getSchoolList() == null) return false;
        for (String line : g.getSchoolList().split("\\n")) {
            if (line.strip().equals(schoolName)) return true;
        }
        return false;
    }
}
