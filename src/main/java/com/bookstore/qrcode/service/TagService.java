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
 * 标签管理 + 自动打标服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>客户扫码后根据活码所属学校，自动打上市 / 区 / 学校 三级地域标签（{@link #autoTag}）</li>
 *   <li>支持活码配置的自定义标签，客户扫码后自动打标（{@link #autoTag}）</li>
 *   <li>标签的获取与创建，自动同步到企业微信（{@link #getOrCreateTag}）</li>
 *   <li>标签组缓存机制（DCL 双重检查锁定，{@link #getOrCreateTagGroupId}）</li>
 *   <li>收集表单提交后打年级 / 班级标签（{@link #tagFromForm}）</li>
 *   <li>手动为指定客户补打标签（{@link #manualTag}）</li>
 * </ul>
 *
 * <p>涉及的实体：{@link Tag}、{@link CustomerTag}、{@link QrCode}、{@link Customer}
 *
 * @author Bookstore Dev
 * @since 1.0.0
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

    /** 默认企业微信标签组名称，所有自动创建的标签均归属于该组 */
    private static final String DEFAULT_TAG_GROUP_NAME = "家校服务";
    /**
     * 缓存的企微标签组 ID。
     *
     * <p>使用 {@code volatile} 确保多线程可见性，配合 {@link #getOrCreateTagGroupId} 中的
     * DCL（双重检查锁定）模式，避免每次调用都查询企微 API。</p>
     */
    private volatile String cachedGroupId = null;

    /**
     * 自动打标：客户扫码后根据活码配置，自动打地域标签和自定义标签。
     *
     * <p>打标流程：
     * <ol>
     *   <li>从 state（学校ID）反查活码记录</li>
     *   <li>逐级获取 / 创建标签：市标签 → 区标签 → 学校标签（三级层级结构）</li>
     *   <li>同步调用企微 API 在企微侧为客户打上对应标签</li>
     *   <li>在本地数据库写入客户-标签关联记录（{@link CustomerTag}）</li>
     *   <li>若活码配置了自定义标签，同样逐个创建并打上</li>
     * </ol>
     *
     * @param externalUserId 企微客户外部用户ID
     * @param userId         当前接待员工的企微用户ID
     * @param state          自定义参数，即学校ID，用于反查活码和地域标签
     */
    @Transactional
    public void autoTag(String externalUserId, String userId, String state) {
        try {
            // ===== 根据学校ID反查活码 =====
            // state 参数是在创建活码时设定的学校ID，用于定位活码和地域信息
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr == null) {
                // 找不到活码可能是 state 异常或活码已被删除，跳过打标
                log.warn("自动打标失败: 未找到学校ID={} 的活码", state);
                return;
            }

            // ===== 三级地域标签：市 → 区 → 学校 =====
            // 按层级依次获取 / 创建标签，每个下级标签将上级标签ID设为 parentId
            Tag cityTag = getOrCreateTag(qr.getRegionCity(), Tag.TagType.system, null);
            Tag districtTag = getOrCreateTag(qr.getRegionDistrict(), Tag.TagType.system, cityTag.getId());
            Tag schoolTag = getOrCreateTag(qr.getSchoolName(), Tag.TagType.system, districtTag.getId());

            // ===== 同步到企业微信 =====
            // 收集三级标签在企微侧的标签ID，批量调用企微 API 打标
            List<String> wecomTagIds = new ArrayList<>();
            if (cityTag.getWecomTagId() != null) wecomTagIds.add(cityTag.getWecomTagId());
            if (districtTag.getWecomTagId() != null) wecomTagIds.add(districtTag.getWecomTagId());
            if (schoolTag.getWecomTagId() != null) wecomTagIds.add(schoolTag.getWecomTagId());
            if (!wecomTagIds.isEmpty()) {
                wecomApi.markTag(externalUserId, userId, wecomTagIds);
            }

            // ===== 写入本地客户-标签关联 =====
            // 在数据库记录客户与标签的关联关系，来源标记为 "system"（系统自动打标）
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElseThrow(() -> new RuntimeException("客户不存在: " + externalUserId));
            bindCustomerTag(customer.getId(), cityTag.getId(), "system");
            bindCustomerTag(customer.getId(), districtTag.getId(), "system");
            bindCustomerTag(customer.getId(), schoolTag.getId(), "system");

            // ===== 活码自定义标签 =====
            // 活码可以额外配置自定义标签（以逗号分隔的标签名称列表），
            // 客户扫码后自动打上这些标签
            if (qr.getCustomTags() != null && !qr.getCustomTags().isBlank()) {
                List<String> customWecomTagIds = new ArrayList<>();
                // 按逗号分割标签名，逐个创建并关联
                for (String tagName : qr.getCustomTags().split(",")) {
                    String trimmed = tagName.trim();
                    if (trimmed.isEmpty()) continue;
                    Tag customTag = getOrCreateTag(trimmed, Tag.TagType.system, null);
                    bindCustomerTag(customer.getId(), customTag.getId(), "system");
                    if (customTag.getWecomTagId() != null) {
                        customWecomTagIds.add(customTag.getWecomTagId());
                    }
                }
                // 批量同步到企微
                if (!customWecomTagIds.isEmpty()) {
                    wecomApi.markTag(externalUserId, userId, customWecomTagIds);
                }
            }

        } catch (Exception e) {
            // 自动打标是整个回调链路中的附加操作，异常不应影响主流程
            log.error("自动打标异常: external={}, state={}", externalUserId, state, e);
        }
    }

    /**
     * 获取或创建标签（自动同步到企业微信）。
     *
     * <p>根据标签名称在本地数据库中查找，若存在则直接返回（如发现缺少企微ID则补同步）；
     * 若不存在则在企微创建对应标签，并持久化到本地数据库。</p>
     *
     * @param name     标签名称（如"北京市"、"海淀区"、"XX学校"）
     * @param type     标签类型，见 {@link Tag.TagType#system} 和 {@link Tag.TagType#form}
     * @param parentId 上级标签ID，用于构建标签层级（市 → 区 → 学校），可为 null
     * @return 已持久化的标签实体（含企微标签ID）
     */
    @Transactional
    public Tag getOrCreateTag(String name, Tag.TagType type, Long parentId) {
        // 优先查找本地数据库，避免重复创建
        Tag existing = tagRepo.findByName(name);
        if (existing != null) {
            // 已有标签但缺少企微ID（可能是早期创建或同步异常导致）
            // 需要补充同步到企微，确保标签在企微侧也存在
            if (existing.getWecomTagId() == null || existing.getWecomTagId().isBlank()) {
                syncExistingTagToWecom(existing);
            }
            return existing;
        }

        // ===== 在企微创建新标签 =====
        // 先创建企微标签拿到 WeCom ID，再保存到本地数据库
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

    /**
     * 获取或创建默认标签组 ID（带双重检查锁定的缓存机制）。
     *
     * <p>企业微信的标签需要归属于某个标签组，本系统统一使用 {@link #DEFAULT_TAG_GROUP_NAME}
     * （"家校服务"）作为默认标签组。</p>
     *
     * <p>缓存策略：
     * <ol>
     *   <li>先检查 {@link #cachedGroupId} 是否已缓存（无锁快速路径）</li>
     *   <li>若未缓存，进入 {@code synchronized} 块（避免重复查询企微API）</li>
     *   <li>在锁内再次检查缓存（DCL 双重检查锁定），防止并发时的重复查询</li>
     *   <li>查询企微标签组列表，按名称匹配已有组</li>
     *   <li>若未找到匹配的组，返回 null，由调用方在首次创建标签时同时创建组</li>
     * </ol>
     *
     * @return 企微标签组ID，若未找到则返回 null（将由 {@link #createWecomTag} 隐式创建）
     */
    private String getOrCreateTagGroupId() {
        // 第一次检查：无锁快速路径，避免不必要的同步开销
        if (cachedGroupId != null) return cachedGroupId;
        synchronized (this) {
            // 第二次检查（DCL）：防止竞争条件下重复查询企微API
            if (cachedGroupId != null) return cachedGroupId;
            try {
                // 查询企微侧所有标签组，按名称匹配默认组
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
                // 查询失败时返回 null，由后续创建流程尝试重新查询或创建
                log.warn("查询企微标签列表失败，将尝试创建新组: {}", e.getMessage());
            }
            // 企微上不存在该标签组，返回 null 让调用方首次创建时隐式创建组
            return null;
        }
    }

    /**
     * 在企业微信创建标签，返回企微标签 ID。
     *
     * <p>逻辑说明：
     * <ul>
     *   <li>优先通过标签组ID在已有组下创建标签</li>
     *   <li>若标签组ID为空（首次），则调用带 group_name 的接口同时创建标签组和标签</li>
     *   <li>创建成功后缓存标签组ID供后续使用</li>
     * </ul>
     *
     * @param tagName 标签名称
     * @return 企业微信返回的标签 ID
     * @throws RuntimeException 企微接口调用失败或返回数据异常时抛出
     */
    private String createWecomTag(String tagName) {
        try {
            // 获取或创建默认标签组 ID
            String groupId = getOrCreateTagGroupId();
            JsonNode resp;
            if (groupId != null) {
                // 已有标签组，在该组下创建标签
                resp = wecomApi.addCorpTag(tagName, groupId);
            } else {
                // 首次创建：同时创建标签组和标签（带 group_name 参数）
                resp = wecomApi.addCorpTagWithGroup(tagName, DEFAULT_TAG_GROUP_NAME);
            }

            // 从企微返回中解析标签组和标签信息
            if (resp.has("tag_group")) {
                JsonNode group = resp.get("tag_group");
                // 若此前未缓存 group_id，现在从返回中提取并缓存
                if (groupId == null && group.has("group_id")) {
                    cachedGroupId = group.get("group_id").asText();
                    log.info("标签组已创建: group_id={}", cachedGroupId);
                }
                // 提取新建标签的 ID
                if (group.has("tag")) {
                    JsonNode tagNode = group.get("tag");
                    // 企微返回的 tag 是数组，取第一个元素的 id
                    if (tagNode.isArray() && tagNode.size() > 0) {
                        String wecomTagId = tagNode.get(0).get("id").asText();
                        log.info("企微标签已创建: name={}, wecomTagId={}", tagName, wecomTagId);
                        return wecomTagId;
                    }
                }
            }
            // 返回数据不符合预期，抛异常让上层处理
            throw new RuntimeException("企微返回中未找到 tag id: " + resp);
        } catch (Exception e) {
            log.error("创建企微标签失败: name={}", tagName, e);
            throw new RuntimeException("创建企微标签失败: " + tagName, e);
        }
    }

    /**
     * 为已有本地标签补充企业微信 ID。
     *
     * <p>适用场景：标签已在本地数据库存在但 wecomTagId 为空（可能是早期创建或同步遗漏）。
     * 处理策略：
     * <ol>
     *   <li>先从企微标签列表中按名称查找匹配的标签</li>
     *   <li>若找到则直接补填 wecomTagId</li>
     *   <li>若未找到，则在企微创建该标签并获取 wecomTagId</li>
     * </ol>
     *
     * @param tag 需要补同步的标签实体（调用方保证 name 非空）
     */
    private void syncExistingTagToWecom(Tag tag) {
        try {
            // 策略一：从企微标签列表中按名称匹配，避免重复创建
            JsonNode resp = wecomApi.getCorpTagList();
            if (resp.has("tag_group")) {
                for (JsonNode group : resp.get("tag_group")) {
                    if (group.has("tag")) {
                        for (JsonNode t : group.get("tag")) {
                            if (tag.getName().equals(t.get("name").asText())) {
                                // 名称匹配成功，补填企微 ID
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
            // 策略二：企微上不存在该名称的标签，执行创建
            String wecomTagId = createWecomTag(tag.getName());
            tag.setWecomTagId(wecomTagId);
            tagRepo.save(tag);
        } catch (Exception e) {
            log.error("补同步标签失败: name={}", tag.getName(), e);
        }
    }

    /**
     * 绑定客户与标签的关联关系（去重）。
     *
     * <p>在 {@link CustomerTag} 表中建立客户与标签的关联。
     * 依赖数据库 UNIQUE 约束防止重复关联，代码层面用 try-catch 忽略重复异常，
     * 避免额外的查询开销。</p>
     *
     * @param customerId 客户主键 ID
     * @param tagId      标签主键 ID
     * @param source     标签来源标记，对应 {@link CustomerTag.TagSource} 枚举
     *                   取值：system（自动打标）、manual（手动打标）、form（表单打标）
     */
    private void bindCustomerTag(Long customerId, Long tagId, String source) {
        // 使用 try-catch 简单处理重复关联：数据库 UNIQUE 约束会阻止重复插入，
        // 捕获异常后直接忽略，避免额外的查询开销
        try {
            CustomerTag ct = CustomerTag.builder()
                .customerId(customerId)
                .tagId(tagId)
                .source(CustomerTag.TagSource.valueOf(source))
                .build();
            customerTagRepo.save(ct);
        } catch (Exception ignored) {
            // 重复关联（已存在相同的 customerId + tagId 组合），直接忽略
        }
    }

    /**
     * 手动给客户补打标签。
     *
     * <p>管理员在管理后台为指定客户手动添加标签时调用。
     * 同步在本地数据库和企业微信侧完成打标。</p>
     *
     * @param customerId 客户主键 ID
     * @param tagId      标签主键 ID
     * @param userId     操作员工的企微用户ID（用于企微 API 鉴权）
     * @throws RuntimeException 当标签或客户不存在时抛出
     */
    @Transactional
    public void manualTag(Long customerId, Long tagId, String userId) {
        // 校验标签是否存在
        Tag tag = tagRepo.findById(tagId)
            .orElseThrow(() -> new RuntimeException("标签不存在: " + tagId));
        // 写入本地关联记录，来源标记为 "manual"
        bindCustomerTag(customerId, tagId, "manual");
        // 获取客户信息（用于获取 externalUserId）
        Customer customer = customerRepo.findById(customerId)
            .orElseThrow(() -> new RuntimeException("客户不存在: " + customerId));
        // 同步到企微：使用企微标签 ID 为客户打标
        String wecomTagId = tag.getWecomTagId();
        if (wecomTagId != null && !wecomTagId.isBlank()) {
            wecomApi.markTag(customer.getExternalUserid(), userId, List.of(wecomTagId));
        } else {
            // 标签尚未同步到企微，仅记录警告，不影响本地关联
            log.warn("标签 {} 未同步到企微，跳过企微打标", tag.getName());
        }
    }

    /**
     * 根据收集表单回调打年级 / 班级标签。
     *
     * <p>当家长提交收集表单（填写年级、班级、孩子姓名等信息）时，根据表单内容为客户打上
     * 年级标签和班级标签。标签类型标记为 {@link Tag.TagType#form}，来源标记为 "form"。</p>
     *
     * <p>典型场景：开学季家长提交信息收集表，系统自动为客户打上"一年级"、"1班"等标签。</p>
     *
     * @param externalUserId 企微客户外部用户ID
     * @param userId         当前接待员工的企微用户ID
     * @param grade          年级信息（如"一年级"、"二年级"），可为 null
     * @param className      班级信息（如"1班"、"2班"），可为 null
     * @param childName      孩子姓名（暂未使用，预留字段）
     */
    @Transactional
    public void tagFromForm(String externalUserId, String userId,
                             String grade, String className, String childName) {
        try {
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElse(null);
            // 客户不存在时跳过（可能是回调异常或数据尚未同步）
            if (customer == null) return;

            // 打年级标签
            if (grade != null) {
                Tag gradeTag = getOrCreateTag(grade, Tag.TagType.form, null);
                bindCustomerTag(customer.getId(), gradeTag.getId(), "form");
                // 同步到企微
                if (gradeTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(gradeTag.getWecomTagId()));
                }
            }
            // 打班级标签
            if (className != null) {
                Tag classTag = getOrCreateTag(className, Tag.TagType.form, null);
                bindCustomerTag(customer.getId(), classTag.getId(), "form");
                // 同步到企微
                if (classTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(classTag.getWecomTagId()));
                }
            }
        } catch (Exception e) {
            // 表单打标是附加操作，异常不应影响主流程
            log.error("表单打标异常: external={}", externalUserId, e);
        }
    }
}
