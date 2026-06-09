package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 标签管理 + 自动打标。
 * state → 查学校 → 市/区/学校 → 调企微 API 打标签。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepo;
    private final CustomerTagRepository customerTagRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final WecomApiClient wecomApi;

    /**
     * 自动打标：从 state（学校ID）反查市/区/学校，调企微 API 打标签。
     */
    @Transactional
    public void autoTag(String externalUserId, String userId, String state) {
        try {
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr == null) {
                log.warn("自动打标失败: 未找到学校ID={} 的活码", state);
                return;
            }

            // 确保标签存在：市、区、学校
            Tag cityTag = getOrCreateTag(qr.getRegionCity(), Tag.TagType.system, null);
            Tag districtTag = getOrCreateTag(qr.getRegionDistrict(), Tag.TagType.system, cityTag.getId());
            Tag schoolTag = getOrCreateTag(qr.getSchoolName(), Tag.TagType.system, districtTag.getId());

            // 调企微 API 打标签
            List<String> wecomTagIds = new ArrayList<>();
            wecomTagIds.add(cityTag.getId().toString());
            wecomTagIds.add(districtTag.getId().toString());
            wecomTagIds.add(schoolTag.getId().toString());
            wecomApi.markTag(externalUserId, userId, wecomTagIds);

            // 写入客户-标签关联
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElseThrow(() -> new RuntimeException("客户不存在: " + externalUserId));
            bindCustomerTag(customer.getId(), cityTag.getId(), "system");
            bindCustomerTag(customer.getId(), districtTag.getId(), "system");
            bindCustomerTag(customer.getId(), schoolTag.getId(), "system");

            // 活码自定义标签：客户扫码后自动打上
            if (qr.getCustomTags() != null && !qr.getCustomTags().isBlank()) {
                List<String> customTagIds = new ArrayList<>();
                for (String tagName : qr.getCustomTags().split(",")) {
                    String trimmed = tagName.trim();
                    if (trimmed.isEmpty()) continue;
                    Tag customTag = getOrCreateTag(trimmed, Tag.TagType.system, null);
                    bindCustomerTag(customer.getId(), customTag.getId(), "system");
                    customTagIds.add(customTag.getId().toString());
                }
                if (!customTagIds.isEmpty()) {
                    wecomApi.markTag(externalUserId, userId, customTagIds);
                }
            }

        } catch (Exception e) {
            log.error("自动打标异常: external={}, state={}", externalUserId, state, e);
        }
    }

    /**
     * 获取或创建标签。
     */
    @Transactional
    public Tag getOrCreateTag(String name, Tag.TagType type, Long parentId) {
        Tag existing = tagRepo.findByName(name);
        if (existing != null) return existing;

        Tag tag = Tag.builder()
            .name(name)
            .type(type)
            .parentId(parentId)
            .build();
        return tagRepo.save(tag);
    }

    /**
     * 给客户打标签（去重）。
     */
    private void bindCustomerTag(Long customerId, Long tagId, String source) {
        // 简单去重：忽略异常（UNIQUE 约束自动处理）
        try {
            CustomerTag ct = CustomerTag.builder()
                .customerId(customerId)
                .tagId(tagId)
                .source(CustomerTag.TagSource.valueOf(source))
                .build();
            customerTagRepo.save(ct);
        } catch (Exception ignored) {
            // 重复关联，忽略
        }
    }

    /**
     * 手动给客户补打标签。
     */
    @Transactional
    public void manualTag(Long customerId, Long tagId, String userId) {
        bindCustomerTag(customerId, tagId, "manual");
        Customer customer = customerRepo.findById(customerId)
            .orElseThrow(() -> new RuntimeException("客户不存在: " + customerId));
        wecomApi.markTag(customer.getExternalUserid(), userId, List.of(tagId.toString()));
    }

    /**
     * 根据收集表单回调打年级/班级标签。
     */
    @Transactional
    public void tagFromForm(String externalUserId, String userId,
                             String grade, String className, String childName) {
        try {
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElse(null);
            if (customer == null) return;

            if (grade != null) {
                Tag gradeTag = getOrCreateTag(grade, Tag.TagType.form, null);
                bindCustomerTag(customer.getId(), gradeTag.getId(), "form");
                wecomApi.markTag(externalUserId, userId, List.of(gradeTag.getId().toString()));
            }
            if (className != null) {
                Tag classTag = getOrCreateTag(className, Tag.TagType.form, null);
                bindCustomerTag(customer.getId(), classTag.getId(), "form");
                wecomApi.markTag(externalUserId, userId, List.of(classTag.getId().toString()));
            }
        } catch (Exception e) {
            log.error("表单打标异常: external={}", externalUserId, e);
        }
    }
}
