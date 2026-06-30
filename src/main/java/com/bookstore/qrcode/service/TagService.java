package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *   <li>标签组缓存机制（DCL 双重检查锁定，{@link #getGroupIdByKeyword}）</li>
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
    private final FormTemplateRepository formTemplateRepo;
    private final FormSubmissionRepository formSubmissionRepo;
    private final WecomApiClient wecomApi;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 缓存的企微标签组 ID（按 group_name 索引）。
     *
     * <p>使用 {@code volatile} 确保多线程可见性，配合 {@link #getGroupIdByKeyword} 中的
     * DCL（双重检查锁定）模式，避免每次调用都查询企微 API。</p>
     */
    private volatile Map<String, String> cachedGroupIdMap = null;
    /** 缓存的企微标签名 → 标签 ID 映射（用于校验本地 wecomTagId 是否仍有效） */
    private volatile Map<String, String> cachedTagNameToId = null;
    private volatile java.time.LocalDateTime groupCacheTime = null;
    private static final long GROUP_CACHE_MINUTES = 10;

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
        log.info("自动打标开始: external={}, userid={}, state={}", externalUserId, userId, state);
        try {
            // ===== 根据学校ID反查活码 =====
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr == null) {
                log.warn("自动打标失败: 未找到学校ID={} 的活码", state);
                return;
            }
            log.info("自动打标 活码信息: school={}, city={}, district={}",
                qr.getSchoolName(), qr.getRegionCity(), qr.getRegionDistrict());

            // ===== 先查客户（快速失败，避免后面 API 调用成功但 DB 写失败）=====
            Customer customer = customerRepo.findByExternalUserid(externalUserId)
                .orElseThrow(() -> new RuntimeException("客户不存在: " + externalUserId));

            // ===== 三级地域标签：市 → 区 → 学校 =====
            Tag cityTag = getOrCreateTag(qr.getRegionCity(), Tag.TagType.system, null, "市州");
            log.info("自动打标 市标签: name={}, id={}, wecomTagId={}",
                cityTag.getName(), cityTag.getId(), cityTag.getWecomTagId());
            Tag districtTag = getOrCreateTag(qr.getRegionDistrict(), Tag.TagType.system, cityTag.getId(), "县区");
            log.info("自动打标 区标签: name={}, id={}, wecomTagId={}",
                districtTag.getName(), districtTag.getId(), districtTag.getWecomTagId());
            Tag schoolTag = getOrCreateTag(qr.getSchoolName(), Tag.TagType.system, districtTag.getId(), "学校-" + qr.getRegionCity());
            log.info("自动打标 学校标签: name={}, id={}, wecomTagId={}",
                schoolTag.getName(), schoolTag.getId(), schoolTag.getWecomTagId());

            // ===== 先写本地关联，再调企微 API（DB 先落盘，API 失败不影响本地一致性）=====
            bindCustomerTag(customer.getId(), cityTag.getId(), "system");
            bindCustomerTag(customer.getId(), districtTag.getId(), "system");
            bindCustomerTag(customer.getId(), schoolTag.getId(), "system");
            log.info("自动打标 本地关联已写入: customerId={}, tags=[{},{},{}]",
                customer.getId(), cityTag.getName(), districtTag.getName(), schoolTag.getName());

            // ===== 同步到企业微信（逐个调用，单个失败不影响其他）=====
            for (Tag t : new Tag[]{cityTag, districtTag, schoolTag}) {
                if (t.getWecomTagId() != null) {
                    try {
                        wecomApi.markTag(externalUserId, userId, List.of(t.getWecomTagId()));
                        log.info("企微打标成功: tag={}, wecomTagId={}", t.getName(), t.getWecomTagId());
                    } catch (Exception e) {
                        log.error("企微打标失败: tag={}, wecomTagId={}", t.getName(), t.getWecomTagId(), e);
                    }
                } else {
                    log.warn("企微打标跳过(无wecomTagId): tag={}", t.getName());
                }
            }

            // ===== 活码自定义标签 =====
            // 支持两种格式：
            //   "标签名"              → 默认归入"学校"标签组（向后兼容）
            //   "标签组名:标签名"      → 归入指定标签组，如 "客户等级:VIP客户"
            if (qr.getCustomTags() != null && !qr.getCustomTags().isBlank()) {
                for (String entry : qr.getCustomTags().split(",")) {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) continue;

                    final String tagName;
                    final String groupKeyword;
                    if (trimmed.contains(":")) {
                        String[] parts = trimmed.split(":", 2);
                        String gk = parts[0].trim();
                        tagName = parts[1].trim();
                        if (tagName.isEmpty()) {
                            log.warn("自动打标 自定义标签格式异常(跳过): entry={}", entry);
                            continue;
                        }
                        // 标签组为空时回退到默认行为（null → createWecomTag 兜底"学校"组）
                        groupKeyword = gk.isEmpty() ? null : gk;
                    } else {
                        groupKeyword = null;  // null → createWecomTag 兜底用"学校"组
                        tagName = trimmed;
                    }

                    Tag customTag = getOrCreateTag(tagName, Tag.TagType.system, null, groupKeyword);
                    bindCustomerTag(customer.getId(), customTag.getId(), "system");
                    if (customTag.getWecomTagId() != null) {
                        try {
                            wecomApi.markTag(externalUserId, userId, List.of(customTag.getWecomTagId()));
                            log.info("企微打标成功(自定义): tag={}, group={}, wecomTagId={}",
                                customTag.getName(), groupKeyword, customTag.getWecomTagId());
                        } catch (Exception e) {
                            log.error("企微打标失败: tag={}, group={}, wecomTagId={}",
                                customTag.getName(), groupKeyword, customTag.getWecomTagId(), e);
                        }
                    }
                }
            }

            log.info("自动打标完成: external={}, state={}, school={}",
                externalUserId, state, qr.getSchoolName());
        } catch (Exception e) {
            // 异常向外抛出让 TagWorker/MessageGuard 触发重试和死信机制
            log.error("自动打标异常: external={}, state={}", externalUserId, state, e);
            throw e;
        }
    }

    /**
     * 获取或创建标签（自动同步到企业微信）。
     *
     * <p>根据标签名称在本地数据库中查找，若存在则直接返回（如发现缺少企微ID则补同步）；
     * 若不存在则在企微创建对应标签，并持久化到本地数据库。</p>
     *
     * @param name         标签名称（如"北京市"、"海淀区"、"XX学校"）
     * @param type         标签类型，见 {@link Tag.TagType#system} 和 {@link Tag.TagType#form}
     * @param parentId     上级标签ID，用于构建标签层级（市 → 区 → 学校），可为 null
     * @param groupKeyword 企微标签组关键词，用于归入正确的分组（如 "学校"、"市"、"区"）
     * @return 已持久化的标签实体（含企微标签ID）
     */
    @Transactional
    public Tag getOrCreateTag(String name, Tag.TagType type, Long parentId, String groupKeyword) {
        // 优先查找本地数据库，避免重复创建
        Tag existing = tagRepo.findByName(name);
        if (existing != null) {
            String storedId = existing.getWecomTagId();
            // 缺少企微 ID → 补同步
            if (storedId == null || storedId.isBlank()) {
                syncExistingTagToWecom(existing, groupKeyword);
                return existing;
            }
            // 校验本地 wecomTagId 在企微当前 Corp 下是否仍有效
            // （Corp ID 切换或标签被删除后，旧 ID 会变成无效的僵尸 ID）
            String currentWecomId = getCachedTagId(name);
            if (currentWecomId == null) {
                // 当前 Corp 企微标签列表中完全找不到该名称 → 需要完整重同步
                log.warn("标签在当前 Corp 企微列表中未找到，触发重同步: name={}, oldWecomTagId={}",
                    name, storedId);
                syncExistingTagToWecom(existing, groupKeyword);
            } else if (!currentWecomId.equals(storedId)) {
                log.warn("标签 wecomTagId 已过期，更新: name={}, old={}, new={}",
                    name, storedId, currentWecomId);
                existing.setWecomTagId(currentWecomId);
                tagRepo.save(existing);
            }
            return existing;
        }

        // ===== 先保存本地记录，再创建企微标签（防止企微创建成功但 DB 保存失败导致孤儿标签）=====
        Tag tag = Tag.builder()
            .name(name)
            .type(type)
            .parentId(parentId)
            .wecomTagId(null)  // 先留空，企微创建成功后再回填
            .build();
        try {
            tag = tagRepo.save(tag);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发创建：另一个线程已抢先创建同名标签，重新查询返回
            Tag concurrent = tagRepo.findByName(name);
            if (concurrent != null) {
                log.info("标签并发创建冲突，复用已有记录: name={}, id={}", name, concurrent.getId());
                return concurrent;
            }
            throw e; // 不应该走到这里，但保持安全
        }

        // 创建企微标签并回填 wecomTagId
        try {
            String wecomTagId = createWecomTag(name, groupKeyword);
            tag.setWecomTagId(wecomTagId);
            tag = tagRepo.save(tag);
        } catch (Exception e) {
            // 企微创建失败：本地记录已存在（wecomTagId=null），后续可通过 syncExistingTagToWecom 补同步
            log.warn("企微标签创建失败，本地记录已保留: name={}, 待后续补同步", name, e);
        }
        return tag;
    }

    // ==================== 企微标签同步 ====================

    /**
     * 根据关键词查找企微标签组 ID（带缓存）。
     *
     * <p>匹配策略：先尝试精确匹配（{@code map.get(keyword)}），
     * 例如 {@code keyword="市州"} 精确匹配 "市州" 组，{@code keyword="学校-白银市"} 精确匹配对应城市的学校组；
     * 精确匹配失败时回退到模糊匹配（组名包含关键词），例如 {@code keyword="学校"} 匹配第一个含"学校"的组。</p>
     *
     * <p>缓存策略：首次调用时加载全部标签组到 {@link #cachedGroupIdMap}，
     * 缓存 10 分钟，超时后重新加载。</p>
     *
     * @param keyword 标签组名关键词（如 "市州"、"县区"、"学校-白银市"、"学校"）
     * @return 企微标签组ID，若未找到则返回 null
     */
    private String getGroupIdByKeyword(String keyword) {
        // 检查缓存是否有效
        if (cachedGroupIdMap != null && groupCacheTime != null
            && java.time.Duration.between(groupCacheTime, java.time.LocalDateTime.now())
                .toMinutes() < GROUP_CACHE_MINUTES) {
            return matchGroupByKeyword(keyword);
        }
        synchronized (this) {
            // DCL 双重检查
            if (cachedGroupIdMap != null && groupCacheTime != null
                && java.time.Duration.between(groupCacheTime, java.time.LocalDateTime.now())
                    .toMinutes() < GROUP_CACHE_MINUTES) {
                return matchGroupByKeyword(keyword);
            }
            try {
                JsonNode resp = wecomApi.getCorpTagList();
                Map<String, String> groupMap = new LinkedHashMap<>();
                Map<String, String> tagMap = new LinkedHashMap<>();
                if (resp.has("tag_group")) {
                    for (JsonNode group : resp.get("tag_group")) {
                        String gn = group.has("group_name") ? group.get("group_name").asText().trim() : "";
                        String gid = group.has("group_id") ? group.get("group_id").asText().trim() : "";
                        if (!gn.isEmpty() && !gid.isEmpty()) {
                            groupMap.put(gn, gid);
                        }
                        // 同时缓存所有标签名 → ID，用于后续校验本地 wecomTagId 是否仍有效
                        if (group.has("tag")) {
                            for (JsonNode t : group.get("tag")) {
                                String tn = t.has("name") ? t.get("name").asText().trim() : "";
                                String tid = t.has("id") ? t.get("id").asText().trim() : "";
                                if (!tn.isEmpty() && !tid.isEmpty()) {
                                    tagMap.put(tn, tid);
                                }
                            }
                        }
                    }
                }
                cachedGroupIdMap = groupMap;
                cachedTagNameToId = tagMap;
                groupCacheTime = java.time.LocalDateTime.now();
                log.info("企微标签缓存已刷新: {} 个组, {} 个标签 → 组名: {}",
                    groupMap.size(), tagMap.size(), groupMap.keySet());
                return matchGroupByKeyword(keyword);
            } catch (Exception e) {
                log.warn("查询企微标签组列表失败: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * 从缓存中匹配标签组 ID：先精确匹配，再模糊匹配兜底。
     *
     * <p>精确匹配确保 {@code "学校-白银市"} 只命中对应城市的组，
     * 不会因为 {@code contains("市")} 串到其他城市的学校组或市州组。
     * 模糊匹配作为兜底：{@code "学校"}（年级/班级标签用）匹配第一个包含"学校"的组。</p>
     */
    private String matchGroupByKeyword(String keyword) {
        if (cachedGroupIdMap == null) return null;
        // ① 精确匹配：如 "市州" → 市州组, "学校-白银市" → 学校-白银市组
        String exact = cachedGroupIdMap.get(keyword);
        if (exact != null) return exact;
        // ② 模糊匹配兜底：如 "学校" 匹配到第一个包含"学校"的组
        // 防御：keyword 为空或 null 时不进行模糊匹配（String.contains(null) 会 NPE）
        if (keyword == null || keyword.isEmpty()) return null;
        return cachedGroupIdMap.entrySet().stream()
            .filter(e -> e.getKey().contains(keyword))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }

    /**
     * 从缓存中按标签名获取企微标签 ID（不额外调用 API）。
     * 若缓存过期或不存在则触发刷新。
     *
     * @param tagName 标签名称
     * @return 企微标签 ID，未找到返回 null
     */
    private String getCachedTagId(String tagName) {
        // 缓存有效则直接查询
        if (cachedTagNameToId != null && groupCacheTime != null
            && java.time.Duration.between(groupCacheTime, java.time.LocalDateTime.now())
                .toMinutes() < GROUP_CACHE_MINUTES) {
            return cachedTagNameToId.get(tagName);
        }
        // 缓存过期：触发一次刷新（通过 getGroupIdByKeyword 间接刷新两个缓存）
        getGroupIdByKeyword("学校");
        if (cachedTagNameToId != null) {
            return cachedTagNameToId.get(tagName);
        }
        return null;
    }

    /**
     * 在企业微信创建标签，返回企微标签 ID。
     *
     * <p>根据 groupKeyword 查找企微已有的标签组（如 "学校" 匹配 "学校" 标签组），
     * 将标签创建到正确的分组下。若找不到匹配组，则以 groupKeyword 作为组名创建新组。</p>
     *
     * @param tagName      标签名称
     * @param groupKeyword 标签组关键词，用于匹配企微已有标签组（如 "学校"、"市"、"区"）。
     *                     null 时默认归入"学校"组
     * @return 企业微信返回的标签 ID
     * @throws RuntimeException 企微接口调用失败或返回数据异常时抛出
     */
    private String createWecomTag(String tagName, String groupKeyword) {
        JsonNode resp;
        try {
            // 按关键词查找企微已有标签组（如 keyword="学校" 匹配 "学校" 标签组）
            String groupId = groupKeyword != null ? getGroupIdByKeyword(groupKeyword) : null;
            if (groupId != null) {
                // 在已有标签组下创建标签
                log.info("标签 '{}' 归入企微标签组 groupId={} (keyword={})", tagName, groupId, groupKeyword);
                resp = wecomApi.addCorpTag(tagName, groupId);
            } else {
                // 未找到匹配组：以 groupKeyword 为组名创建新组 + 标签
                // （如 "学校-白银市" 首次出现时会自动创建该学校组）
                String fallbackGroup = groupKeyword != null ? groupKeyword : "学校";
                log.info("未找到关键词匹配的标签组，标签 '{}' 用 group_name='{}' 创建", tagName, fallbackGroup);
                resp = wecomApi.addCorpTagWithGroup(tagName, fallbackGroup);
            }
        } catch (WecomApiException e) {
            // 检查企微错误码 40071：标签名已存在（并发创建导致）
            if (e.getErrcode() == 40071) {
                log.info("企微标签已存在(40071)，从列表查找: name={}", tagName);
                String existingId = findWecomTagIdByName(tagName);
                if (existingId != null) {
                    return existingId;
                }
                log.warn("企微标签已存在但列表中未匹配到，暂时跳过: name={}", tagName);
                return null;
            }
            // 其他错误码
            log.warn("创建企微标签返回非零: name={}, errcode={}, errmsg={}",
                tagName, e.getErrcode(), e.getErrmsg());
            String existingId = findWecomTagIdByName(tagName);
            if (existingId != null) return existingId;
            return null;
        }

        // parseAndCheck 保证 errcode=0，从企微返回中解析标签 ID
        if (resp.has("tag_group")) {
            JsonNode group = resp.get("tag_group");
            if (group.has("tag")) {
                JsonNode tagNode = group.get("tag");
                if (tagNode.isArray() && tagNode.size() > 0) {
                    String wecomTagId = tagNode.get(0).get("id").asText();
                    log.info("企微标签已创建: name={}, wecomTagId={}, groupKeyword={}",
                        tagName, wecomTagId, groupKeyword);
                    return wecomTagId;
                }
            }
        }
        throw new RuntimeException("企微返回中未找到 tag id: " + resp);
    }

    /**
     * 从企微标签列表中按名称查找标签 ID。
     *
     * @param tagName 标签名称
     * @return 企微标签 ID，未找到返回 null
     */
    private String findWecomTagIdByName(String tagName) {
        try {
            JsonNode resp = wecomApi.getCorpTagList();
            if (resp.has("tag_group")) {
                List<String> allNames = new ArrayList<>();
                for (JsonNode group : resp.get("tag_group")) {
                    if (group.has("tag")) {
                        for (JsonNode t : group.get("tag")) {
                            String name = t.has("name") ? t.get("name").asText().trim() : "";
                            allNames.add(name);
                            if (tagName.trim().equals(name)) {
                                return t.get("id").asText();
                            }
                        }
                    }
                }
                // 未匹配到时输出所有企微标签名称，便于排查编码/命名差异
                log.warn("企微标签列表中未找到 '{}'，当前企微标签: {}", tagName, allNames);
            } else {
                log.warn("企微返回无 tag_group，查找标签失败: name={}", tagName);
            }
        } catch (Exception e) {
            log.warn("查找企微标签失败: name={}", tagName, e);
        }
        return null;
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
     * @param tag          需要补同步的标签实体（调用方保证 name 非空）
     * @param groupKeyword 标签组关键词（用于归入正确的企微标签组）
     */
    private void syncExistingTagToWecom(Tag tag, String groupKeyword) {
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
            String wecomTagId = createWecomTag(tag.getName(), groupKeyword);
            if (wecomTagId != null) {
                tag.setWecomTagId(wecomTagId);
                tagRepo.save(tag);
            }
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
        // 先检查关联是否已存在，避免 DataIntegrityViolationException
        // 毒化 Spring 事务（JPA 会将重复键异常标记为 rollback-only）
        if (customerTagRepo.existsByCustomerIdAndTagId(customerId, tagId)) {
            return;
        }
        try {
            CustomerTag ct = CustomerTag.builder()
                .customerId(customerId)
                .tagId(tagId)
                .source(CustomerTag.TagSource.valueOf(source))
                .build();
            customerTagRepo.save(ct);
        } catch (Exception ignored) {
            // 极端并发下仍可能冲突（check-then-act 竞态），忽略
        }
    }

    /**
     * 一次性从企微同步所有标签到本地 DB。
     *
     * <p>使用场景：在企微管理后台提前创建好标签（市/区/学校）后，
     * 调用此方法将所有标签导入本地数据库。之后 TagWorker 的
     * {@link #getOrCreateTag} 将直接命中本地 DB，不再调企微创建 API。</p>
     *
     * <p>幂等：已存在的标签（按名称匹配）自动跳过。</p>
     *
     * @return 同步结果，包含已存在数量和新导入数量
     */
    @Transactional
    public Map<String, Integer> syncTagsFromWecom() {
        int skipped = 0;
        int imported = 0;
        try {
            JsonNode resp = wecomApi.getCorpTagList();
            if (!resp.has("tag_group")) {
                log.warn("企微返回无 tag_group");
                return Map.of("skipped", skipped, "imported", imported);
            }
            // 按 group_name → group_id 建立索引，后续创建标签时使用
            Map<String, String> groupIdMap = new LinkedHashMap<>();
            Map<String, String> tagNameToIdMap = new LinkedHashMap<>();  // tag_name → wecomTagId，防缓存不一致
            for (JsonNode group : resp.get("tag_group")) {
                String groupName = group.has("group_name") ? group.get("group_name").asText() : "";
                String groupId = group.has("group_id") ? group.get("group_id").asText() : "";
                if (!groupName.isEmpty()) {
                    groupIdMap.put(groupName, groupId);
                }
                if (group.has("tag")) {
                    for (JsonNode t : group.get("tag")) {
                        String tagName = t.get("name").asText();
                        String wecomId = t.get("id").asText();
                        tagNameToIdMap.put(tagName, wecomId);  // 同时记录 name→id 映射
                        // 按名称查本地 DB
                        Tag existing = tagRepo.findByName(tagName);
                        if (existing != null) {
                            // 企微 API 是唯一数据源：若 wecomTagId 与 API 不一致则更新
                            if (!wecomId.equals(existing.getWecomTagId())) {
                                String oldId = existing.getWecomTagId();
                                existing.setWecomTagId(wecomId);
                                tagRepo.save(existing);
                                if (oldId != null && !oldId.isBlank()) {
                                    log.info("标签 wecomTagId 已更新: name={}, old={}, new={}",
                                        tagName, oldId, wecomId);
                                }
                            }
                            skipped++;
                        } else {
                            // 新标签写入本地 DB
                            Tag tag = Tag.builder()
                                .name(tagName)
                                .type(Tag.TagType.system)
                                .wecomTagId(wecomId)
                                .build();
                            tagRepo.save(tag);
                            imported++;
                            log.info("标签已同步: name={}, wecomTagId={}", tagName, wecomId);
                        }
                    }
                }
            }
            // 刷新标签组 ID 缓存 和 标签名→ID 缓存
            if (!groupIdMap.isEmpty()) {
                cachedGroupIdMap = groupIdMap;
                cachedTagNameToId = tagNameToIdMap;  // 保持两个缓存一致，防止 getOrCreateTag 写过期 ID
                groupCacheTime = java.time.LocalDateTime.now();
            }
            log.info("标签同步完成: 跳过{}个, 导入{}个", skipped, imported);
        } catch (Exception e) {
            log.error("同步企微标签失败", e);
            throw new RuntimeException("同步企微标签失败: " + e.getMessage(), e);
        }
        return Map.of("skipped", skipped, "imported", imported);
    }

    /**
     * 使用已获取的企微标签列表响应同步到本地 DB（避免重复调用企微 API）。
     *
     * @param resp 已获取的企微 {@code get_corp_tag_list} 响应
     * @return 同步结果，包含已存在数量和新导入数量
     */
    @Transactional
    public Map<String, Integer> syncTagsFromWecom(JsonNode resp) {
        int skipped = 0;
        int imported = 0;
        try {
            if (!resp.has("tag_group")) {
                log.warn("企微返回无 tag_group");
                return Map.of("skipped", skipped, "imported", imported);
            }
            Map<String, String> groupIdMap = new LinkedHashMap<>();
            Map<String, String> tagNameToIdMap = new LinkedHashMap<>();  // tag_name → wecomTagId，防缓存不一致
            for (JsonNode group : resp.get("tag_group")) {
                String groupName = group.has("group_name") ? group.get("group_name").asText() : "";
                String groupId = group.has("group_id") ? group.get("group_id").asText() : "";
                if (!groupName.isEmpty()) {
                    groupIdMap.put(groupName, groupId);
                }
                if (group.has("tag")) {
                    for (JsonNode t : group.get("tag")) {
                        String tagName = t.get("name").asText();
                        String wecomId = t.get("id").asText();
                        tagNameToIdMap.put(tagName, wecomId);  // 同时记录 name→id 映射
                        Tag existing = tagRepo.findByName(tagName);
                        if (existing != null) {
                            // 企微 API 是唯一数据源：若 wecomTagId 与 API 不一致则更新
                            // （企业切换 Corp ID 后，旧 ID 需替换为新 ID）
                            if (!wecomId.equals(existing.getWecomTagId())) {
                                String oldId = existing.getWecomTagId();
                                existing.setWecomTagId(wecomId);
                                tagRepo.save(existing);
                                if (oldId != null && !oldId.isBlank()) {
                                    log.info("标签 wecomTagId 已更新: name={}, old={}, new={}",
                                        tagName, oldId, wecomId);
                                }
                            }
                            skipped++;
                        } else {
                            Tag tag = Tag.builder()
                                .name(tagName)
                                .type(Tag.TagType.system)
                                .wecomTagId(wecomId)
                                .build();
                            tagRepo.save(tag);
                            imported++;
                            log.info("标签已同步: name={}, wecomTagId={}", tagName, wecomId);
                        }
                    }
                }
            }
            // 刷新标签组 ID 缓存 和 标签名→ID 缓存
            if (!groupIdMap.isEmpty()) {
                cachedGroupIdMap = groupIdMap;
                cachedTagNameToId = tagNameToIdMap;  // 保持两个缓存一致，防止 getOrCreateTag 写过期 ID
                groupCacheTime = java.time.LocalDateTime.now();
            }
            log.info("标签同步完成: 跳过{}个, 导入{}个", skipped, imported);
        } catch (Exception e) {
            log.error("同步企微标签失败", e);
            throw new RuntimeException("同步企微标签失败: " + e.getMessage(), e);
        }
        return Map.of("skipped", skipped, "imported", imported);
    }

    /**
     * 手动给客户补打标签。
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

            // 打年级标签（归入"学校"标签组）
            if (grade != null) {
                Tag gradeTag = getOrCreateTag(grade, Tag.TagType.form, null, "学校");
                bindCustomerTag(customer.getId(), gradeTag.getId(), "form");
                // 同步到企微
                if (gradeTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(gradeTag.getWecomTagId()));
                }
            }
            // 打班级标签（归入"学校"标签组）
            if (className != null) {
                Tag classTag = getOrCreateTag(className, Tag.TagType.form, null, "学校");
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

    /**
     * 表单提交后异步打标+备注（由 TagWorker 消费 form_submit 事件调用）。
     */
    @Transactional
    public void applyFormTags(String externalUserId, String userId,
                               Long formTemplateId, Long submissionId, String fieldDataJson,
                               String schoolName) {
        try {
            FormTemplate tpl = formTemplateRepo.findById(formTemplateId).orElse(null);
            if (tpl == null) { log.warn("表单模板不存在: {}", formTemplateId); return; }

            Customer customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
            if (customer == null) { log.warn("客户不存在: {}", externalUserId); return; }

            JsonNode fieldData = objectMapper.readTree(fieldDataJson);
            JsonNode tagMapping = objectMapper.readTree(tpl.getTagMapping());

            List<String> appliedTags = new ArrayList<>();
            final String[] remarkText = { null };

            // 区域联盟：客户选择的学校也打成标签
            if (schoolName != null && !schoolName.isBlank()) {
                Tag schoolTag = getOrCreateTag(schoolName, Tag.TagType.form, null, "学校");
                bindCustomerTag(customer.getId(), schoolTag.getId(), "form");
                if (schoolTag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(schoolTag.getWecomTagId()));
                    appliedTags.add(schoolTag.getName());
                }
            }

            java.util.Iterator<String> fn = fieldData.fieldNames();
            while (fn.hasNext()) {
                String fieldName = fn.next();
                String fieldValue = fieldData.get(fieldName).asText();
                if (fieldValue == null || fieldValue.isBlank()) continue;

                String action = tagMapping.has(fieldName)
                    ? tagMapping.get(fieldName).asText() : null;
                if (action == null) continue;

                // 支持 "tag" 和 "tag:标签组名" 两种格式
                //   "tag"           → 默认归入"学校"标签组（向后兼容）
                //   "tag:客户等级"   → 归入"客户等级"标签组
                if ("tag".equals(action) || action.startsWith("tag:")) {
                    final String groupKeyword;
                    if (action.startsWith("tag:") && action.length() > 4) {
                        String extracted = action.substring(4).trim();
                        groupKeyword = extracted.isEmpty() ? "学校" : extracted;
                    } else {
                        groupKeyword = "学校";
                    }
                    Tag tag = getOrCreateTag(fieldValue, Tag.TagType.form, null, groupKeyword);
                    bindCustomerTag(customer.getId(), tag.getId(), "form");
                    if (tag.getWecomTagId() != null) {
                        wecomApi.markTag(externalUserId, userId, List.of(tag.getWecomTagId()));
                        appliedTags.add(tag.getName());
                    }
                }
            }

            // 按 remark_template 拼接备注
            if (tpl.getRemarkTemplate() != null && !tpl.getRemarkTemplate().isBlank()) {
                remarkText[0] = tpl.getRemarkTemplate();
                java.util.Iterator<String> fn2 = fieldData.fieldNames();
                while (fn2.hasNext()) {
                    String key = fn2.next();
                    String val = fieldData.get(key).asText();
                    remarkText[0] = remarkText[0].replace("{{" + key + "}}", val != null ? val : "");
                }
                wecomApi.updateRemark(userId, externalUserId, remarkText[0]);
            }

            // 回填 submission 记录
            formSubmissionRepo.findById(submissionId).ifPresent(sub -> {
                sub.setTagsApplied(String.join(",", appliedTags));
                sub.setRemarkUpdated(remarkText[0]);
                formSubmissionRepo.save(sub);
            });

            log.info("表单打标完成: external={}, tags={}, remark={}", externalUserId, appliedTags, remarkText[0]);
        } catch (Exception e) {
            log.error("表单打标异常: external={}", externalUserId, e);
            throw new RuntimeException("表单打标失败", e);
        }
    }
}
