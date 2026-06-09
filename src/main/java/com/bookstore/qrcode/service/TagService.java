package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
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

    /** 默认企微标签组名 */
    private static final String DEFAULT_TAG_GROUP_NAME = "家校服务";
    /** 缓存的标签组 ID，避免每次查企微 API */
    private volatile String cachedGroupId = null;

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

            // 调企微 API 打标签（使用 WeCom 标签 ID）
            List<String> wecomTagIds = new ArrayList<>();
            if (cityTag.getWecomTagId() != null) wecomTagIds.add(cityTag.getWecomTagId());
            if (districtTag.getWecomTagId() != null) wecomTagIds.add(districtTag.getWecomTagId());
            if (schoolTag.getWecomTagId() != null) wecomTagIds.add(schoolTag.getWecomTagId());
            if (!wecomTagIds.isEmpty()) {
                wecomApi.markTag(externalUserId, userId, wecomTagIds);
            }

            // 写入客户-标签关联
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElseThrow(() -> new RuntimeException("客户不存在: " + externalUserId));
            bindCustomerTag(customer.getId(), cityTag.getId(), "system");
            bindCustomerTag(customer.getId(), districtTag.getId(), "system");
            bindCustomerTag(customer.getId(), schoolTag.getId(), "system");

            // 活码自定义标签：客户扫码后自动打上
            if (qr.getCustomTags() != null && !qr.getCustomTags().isBlank()) {
                List<String> customWecomTagIds = new ArrayList<>();
                for (String tagName : qr.getCustomTags().split(",")) {
                    String trimmed = tagName.trim();
                    if (trimmed.isEmpty()) continue;
                    Tag customTag = getOrCreateTag(trimmed, Tag.TagType.system, null);
                    bindCustomerTag(customer.getId(), customTag.getId(), "system");
                    if (customTag.getWecomTagId() != null) {
                        customWecomTagIds.add(customTag.getWecomTagId());
                    }
                }
                if (!customWecomTagIds.isEmpty()) {
                    wecomApi.markTag(externalUserId, userId, customWecomTagIds);
                }
            }

        } catch (Exception e) {
            log.error("自动打标异常: external={}, state={}", externalUserId, state, e);
        }
    }

    /**
     * 获取或创建标签（自动同步到企微）。
     */
    @Transactional
    public Tag getOrCreateTag(String name, Tag.TagType type, Long parentId) {
        Tag existing = tagRepo.findByName(name);
        if (existing != null) {
            // 已有标签但缺少企微 ID → 补同步
            if (existing.getWecomTagId() == null || existing.getWecomTagId().isBlank()) {
                syncExistingTagToWecom(existing);
            }
            return existing;
        }

        // 创建企微标签 → 拿到 WeCom ID
        String wecomTagId = createWecomTag(name);
        Tag tag = Tag.builder()
            .name(name)
            .type(type)
            .parentId(parentId)
            .wecomTagId(wecomTagId)
            .build();
        return tagRepo.save(tag);
    }

    // ==================== 企微标签同步 ====================

    /** 获取或创建默认标签组 ID（缓存） */
    private String getOrCreateTagGroupId() {
        if (cachedGroupId != null) return cachedGroupId;
        synchronized (this) {
            if (cachedGroupId != null) return cachedGroupId;
            try {
                JsonNode resp = wecomApi.getCorpTagList();
                if (resp.has("tag_group")) {
                    for (JsonNode group : resp.get("tag_group")) {
                        if (DEFAULT_TAG_GROUP_NAME.equals(group.get("group_name").asText())) {
                            cachedGroupId = group.get("group_id").asText();
                            log.info("找到已有标签组: group_id={}", cachedGroupId);
                            return cachedGroupId;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("查询企微标签列表失败，将尝试创建新组: {}", e.getMessage());
            }
            // 不存在则通过创建第一个标签来隐式创建组
            return null; // 第一次用 group_name 创建
        }
    }

    /** 在企微创建标签，返回 WeCom tag ID */
    private String createWecomTag(String tagName) {
        try {
            String groupId = getOrCreateTagGroupId();
            JsonNode resp;
            if (groupId != null) {
                resp = wecomApi.addCorpTag(tagName, groupId);
            } else {
                // 首次：带 group_name 创建，同时创建组和标签
                resp = wecomApi.addCorpTagWithGroup(tagName, DEFAULT_TAG_GROUP_NAME);
            }

            if (resp.has("tag_group")) {
                JsonNode group = resp.get("tag_group");
                // 缓存 group_id
                if (groupId == null && group.has("group_id")) {
                    cachedGroupId = group.get("group_id").asText();
                    log.info("标签组已创建: group_id={}", cachedGroupId);
                }
                // 提取 tag id
                if (group.has("tag")) {
                    JsonNode tagNode = group.get("tag");
                    if (tagNode.isArray() && tagNode.size() > 0) {
                        String wecomTagId = tagNode.get(0).get("id").asText();
                        log.info("企微标签已创建: name={}, wecomTagId={}", tagName, wecomTagId);
                        return wecomTagId;
                    }
                }
            }
            throw new RuntimeException("企微返回中未找到 tag id: " + resp);
        } catch (Exception e) {
            log.error("创建企微标签失败: name={}", tagName, e);
            throw new RuntimeException("创建企微标签失败: " + tagName, e);
        }
    }

    /** 补充已有标签的 WeCom ID */
    private void syncExistingTagToWecom(Tag tag) {
        try {
            // 先尝试从企微标签列表中按名称匹配
            JsonNode resp = wecomApi.getCorpTagList();
            if (resp.has("tag_group")) {
                for (JsonNode group : resp.get("tag_group")) {
                    if (group.has("tag")) {
                        for (JsonNode t : group.get("tag")) {
                            if (tag.getName().equals(t.get("name").asText())) {
                                String wecomId = t.get("id").asText();
                                tag.setWecomTagId(wecomId);
                                tagRepo.save(tag);
                                log.info("标签已补同步: name={}, wecomTagId={}", tag.getName(), wecomId);
                                return;
                            }
                        }
                    }
                }
            }
            // 企微上不存在 → 创建
            String wecomTagId = createWecomTag(tag.getName());
            tag.setWecomTagId(wecomTagId);
            tagRepo.save(tag);
        } catch (Exception e) {
            log.error("补同步标签失败: name={}", tag.getName(), e);
        }
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
        Tag tag = tagRepo.findById(tagId)
            .orElseThrow(() -> new RuntimeException("标签不存在: " + tagId));
        bindCustomerTag(customerId, tagId, "manual");
        Customer customer = customerRepo.findById(customerId)
            .orElseThrow(() -> new RuntimeException("客户不存在: " + customerId));
        String wecomTagId = tag.getWecomTagId();
        if (wecomTagId != null && !wecomTagId.isBlank()) {
            wecomApi.markTag(customer.getExternalUserid(), userId, List.of(wecomTagId));
        } else {
            log.warn("标签 {} 未同步到企微，跳过企微打标", tag.getName());
        }
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
                if (gradeTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(gradeTag.getWecomTagId()));
                }
            }
            if (className != null) {
                Tag classTag = getOrCreateTag(className, Tag.TagType.form, null);
                bindCustomerTag(customer.getId(), classTag.getId(), "form");
                if (classTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(classTag.getWecomTagId()));
                }
            }
        } catch (Exception e) {
            log.error("表单打标异常: external={}", externalUserId, e);
        }
    }
}
