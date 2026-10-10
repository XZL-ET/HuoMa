package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 年级标签归并 —— 把散落在「学校-{市}」组的年级标签收敛到「年级」组。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-10-grade-tag-consolidation-design.md}。
 * 核心规则：<b>按 {@code wecom_tag_id} 接线，绝不按名字</b>（每学期升学改名会让本地名差一级）。</p>
 *
 * <p>四段：A0 行级分类（R1/R2/R3）→ A1 逐客户对账 → A2 按 wid 重接本地账目 →
 * B 删企微重复标签 → C 清本地孤儿行 → D 校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeTagMigrationService {

    /** 本次归并范围内的小学/初中/高中年级名（幼儿园与「高中毕业」不在内）。 */
    public static final List<String> GRADE_NAMES = List.of(
        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
        "七年级", "八年级", "九年级", "高一", "高二", "高三");

    public static final String GRADE_GROUP = "年级";

    /** 判断一个组是不是「非年级的重复组」时排除的核心组名。 */
    public static final Set<String> CORE_GROUPS =
        Set.of("年级", "班级", "市州", "县区", "渠道来源");

    private final WecomApiClient wecomApi;
    private final TagRepository tagRepo;
    private final CustomerRepository customerRepo;
    private final CustomerTagRepository customerTagRepo;
    private final TagInsertService tagInsertService;
    private final CustomerTagInsertService customerTagInsertService;
    private final GradeTagMigrationProgressRepository progressRepo;
    private final ScheduledJobGuard jobGuard;

    // ---------------------------------------------------------------- A0

    /** 企微标签全局索引：wid→名、wid→组名、组名→组 id、年级组 wid 集合。 */
    public record WecomTagIndex(Map<String, String> wid2name,
                                Map<String, String> wid2group,
                                Map<String, String> groupIdByName,
                                Set<String> gradeWids) {

        /** 这组标签 id 里是否已经带了「年级」组的年级标签。 */
        public boolean hasGradeTag(Collection<String> wids) {
            if (wids == null) return false;
            for (String w : wids) if (gradeWids.contains(w)) return true;
            return false;
        }

        /** 「年级」组里名叫 name 的标签 id；没有则返回 null（可能被升学改名改掉了）。 */
        public String gradeWidByName(String name) {
            for (String wid : gradeWids) {
                if (name.equals(wid2name.get(wid))) return wid;
            }
            return null;
        }
    }

    public WecomTagIndex loadWecomTagIndex() {
        JsonNode resp = wecomApi.getCorpTagList();
        // LinkedHashMap：保持企微返回顺序，使 Phase B 的「先删 1 个」探针每次挑同一条
        Map<String, String> wid2name = new LinkedHashMap<>();
        Map<String, String> wid2group = new LinkedHashMap<>();
        Map<String, String> groupIdByName = new LinkedHashMap<>();
        Set<String> gradeWids = new LinkedHashSet<>();
        for (JsonNode g : resp.path("tag_group")) {
            String groupName = g.path("group_name").asText(null);
            if (groupName != null) groupIdByName.put(groupName, g.path("group_id").asText(null));
            for (JsonNode t : g.path("tag")) {
                String id = t.path("id").asText(null);
                if (id == null) continue;
                wid2name.put(id, t.path("name").asText(null));
                wid2group.put(id, groupName);
                if (GRADE_GROUP.equals(groupName)) gradeWids.add(id);
            }
        }
        return new WecomTagIndex(wid2name, wid2group, groupIdByName, gradeWids);
    }

    public enum RowClass { R1, R2, R3 }

    /** 本地一条「名为年级名、却不在年级组」的 tag 行的分类结果。 */
    public record GradeTagRow(long tagId, String name, String groupKeyword,
                              String wecomTagId, RowClass rowClass) {}

    /**
     * A0：把本地所有非年级组的年级名 tag 行分成三类。
     *
     * <ul>
     *   <li><b>R1</b>：wid 落在企微「年级」组 → 客户企微侧已对，只需本地重接线。</li>
     *   <li><b>R2</b>：wid 是企微某非年级组（「学校-{市}」）的同名标签 → 需逐客户判定。</li>
     *   <li><b>R3</b>：wid 为空或企微已不存在（legacy 空组行）→ 客户企微侧没有年级标签，需按本地名补打。</li>
     * </ul>
     */
    public List<GradeTagRow> classifyLocalGradeRows(WecomTagIndex index) {
        List<GradeTagRow> out = new ArrayList<>();
        for (var t : tagRepo.findGradeNamedRowsOutsideGradeGroup(GRADE_NAMES)) {
            String wid = t.getWecomTagId();
            RowClass cls;
            if (wid == null || wid.isBlank()) {
                cls = RowClass.R3;
            } else if (index.gradeWids().contains(wid)) {
                cls = RowClass.R1;
            } else if (index.wid2group().containsKey(wid)) {
                cls = RowClass.R2;
            } else {
                cls = RowClass.R3;
            }
            out.add(new GradeTagRow(t.getId(), t.getName(), t.getGroupKeyword(), wid, cls));
        }
        log.info("A0 行级分类完成: 共 {} 行, R1={}, R2={}, R3={}",
            out.size(),
            out.stream().filter(r -> r.rowClass() == RowClass.R1).count(),
            out.stream().filter(r -> r.rowClass() == RowClass.R2).count(),
            out.stream().filter(r -> r.rowClass() == RowClass.R3).count());
        return out;
    }

    // ---------------------------------------------------------------- A2

    /**
     * 归并目标：要指向的企微「年级」组标签 id，加上该标签当前的企微名。
     *
     * <p>带 name 是为了在 canonical 本地行缺失时能按<b>企微当前名</b>建行 —— 本地旧行名
     * 可能因升学改名差一级，落库会重新引入名/id 不一致。</p>
     */
    public record TargetTag(String wecomTagId, String name) {}

    /**
     * 求目标对应的本地「年级」组 canonical tag 行 id；不存在则按 {@code target.name()} 新建。
     *
     * <p>定位按 {@code wecom_tag_id}（不按名）：升学改名会让本地名差一级。</p>
     */
    public Long resolveCanonicalTagId(TargetTag target) {
        if (target == null || target.wecomTagId() == null || target.wecomTagId().isBlank()) return null;
        var existing = tagRepo.findFirstByWecomTagIdAndGroupKeyword(target.wecomTagId(), GRADE_GROUP);
        if (existing.isPresent()) return existing.get().getId();

        String name = target.name() != null ? target.name() : "";
        Tag created = tagInsertService.insertNew(name, Tag.TagType.manual, null, GRADE_GROUP);
        tagInsertService.updateWecomTagId(created.getId(), target.wecomTagId());
        log.info("新建 canonical 年级 tag 行: id={}, name={}, wid={}",
            created.getId(), name, target.wecomTagId());
        return created.getId();
    }
}
