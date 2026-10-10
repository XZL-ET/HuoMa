package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTag;
import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    // ---------------------------------------------------------------- A1

    public static final String JOB_LOCK_KEY = "lock:job:grade-tag-migration";
    public static final Duration JOB_LOCK_TTL = Duration.ofHours(2);

    /** 分页批大小：一次从 customer_tag 取多少条（本地读，不是 API 调用）。 */
    private static final int PAGE_SIZE = 500;
    /** 每 N 次企微 API 调用休眠一次（沿用既有 600/min 限流模式）。 */
    private static final int API_SLEEP_EVERY = 100;
    private static final long API_SLEEP_MS = 10_000;

    public record MigrationReport(long rows, long customers, long localOnly, long wecomRelink,
                                  long wecomMarked, long wecomMarkFailed, long skippedNoAgent,
                                  long requiresReview, boolean dryRun) {}

    /**
     * 逐客户对账计数器。做成<b>局部变量</b>贯穿调用，不做成字段 ——
     * 本服务是单例，字段会被第二次运行或并发触发污染。
     */
    private static final class Counters {
        long rows, customers, localOnly, wecomRelink, wecomMarked, wecomMarkFailed,
             skippedNoAgent, requiresReview;
        int apiCalls;

        MigrationReport toReport(boolean dryRun) {
            return new MigrationReport(rows, customers, localOnly, wecomRelink, wecomMarked,
                wecomMarkFailed, skippedNoAgent, requiresReview, dryRun);
        }

        @Override public String toString() {
            return String.format("rows=%d customers=%d localOnly=%d relink=%d marked=%d "
                    + "markFailed=%d skipped=%d review=%d apiCalls=%d",
                rows, customers, localOnly, wecomRelink, wecomMarked, wecomMarkFailed,
                skippedNoAgent, requiresReview, apiCalls);
        }
    }

    /** 从 {@code externalcontact/get} 响应里汇总所有 follow_user 的 tag_id。 */
    public static List<String> extractWecomTagIds(JsonNode resp) {
        List<String> out = new ArrayList<>();
        if (resp == null) return out;
        for (JsonNode fu : resp.path("follow_user")) {
            for (JsonNode tg : fu.path("tags")) {
                String id = tg.path("tag_id").asText(null);
                if (id != null) out.add(id);
            }
        }
        return out;
    }

    /**
     * Phase A：逐行分类 → 逐客户对账 → 按 wid 重接本地账目。
     *
     * <p>幂等：R2/R3 的 {@code markTag} 是 add（重复 add 已存在标签无副作用），A2 重接线
     * 若已是 canonical 行则是空操作。因此重跑 = 续跑；{@code grade_tag_migration_progress}
     * 只用来省时（跳过已处理项）与出报告，不承载正确性。</p>
     *
     * <p>{@code dryRun=true} 时：只做 A0 分类并统计，<b>零企微调用、零写库</b>。</p>
     */
    public MigrationReport runPhaseA(boolean dryRun) {
        if (dryRun) {
            WecomTagIndex index = loadWecomTagIndex();
            List<GradeTagRow> rows = classifyLocalGradeRows(index);
            long customers = rows.stream()
                .mapToLong(r -> customerTagRepo.countByTagIdIn(List.of(r.tagId())))
                .sum();
            return new MigrationReport(rows.size(), customers, 0, 0, 0, 0, 0, 0, true);
        }

        // 先拿锁再读企微：避免双节点同时各扫一遍分类
        String token = jobGuard.tryAcquire(JOB_LOCK_KEY, JOB_LOCK_TTL);
        if (token == null) {
            throw new IllegalStateException("归并 Job 已在另一节点运行，本次跳过");
        }
        try {
            WecomTagIndex index = loadWecomTagIndex();
            List<GradeTagRow> rows = classifyLocalGradeRows(index);
            Counters counters = new Counters();
            Set<String> processed = loadProcessedKeys();
            for (GradeTagRow r : rows) {
                processRow(r, index, processed, counters);
            }
            log.info("Phase A 完成: {}", counters);
            return counters.toReport(false);
        } finally {
            jobGuard.release(JOB_LOCK_KEY, token);
        }
    }

    /** 后台线程跑 Phase A（HTTP 线程不能等 25–40 分钟）。{@code @EnableAsync} 已在应用主类开启。 */
    @Async("taskExecutor")
    public void runPhaseAAsync() {
        try {
            log.info("Phase A 后台启动");
            runPhaseA(false);
        } catch (Exception e) {
            log.error("Phase A 后台执行失败: {}", e.getMessage(), e);
        }
    }

    private Set<String> loadProcessedKeys() {
        Set<String> keys = new HashSet<>();
        for (Object[] k : progressRepo.findProcessedKeys()) {
            keys.add(((Number) k[0]).longValue() + ":" + ((Number) k[1]).longValue());
        }
        log.info("续跑：已有 {} 个已处理工作项", keys.size());
        return keys;
    }

    /** 处理一条非年级组年级 tag 行下的所有客户（按 ct.id 分页，避免一次拉进内存）。 */
    private void processRow(GradeTagRow row, WecomTagIndex index, Set<String> processed,
                            Counters counters) {
        counters.rows++;
        Long afterId = -1L;
        while (true) {
            List<CustomerTag> batch = customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                row.tagId(), afterId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) break;
            afterId = batch.get(batch.size() - 1).getId();

            List<Long> customerIds = batch.stream().map(CustomerTag::getCustomerId).distinct().toList();
            Map<Long, Customer> byId = new HashMap<>();
            customerRepo.findAllById(customerIds).forEach(c -> byId.put(c.getId(), c));

            for (CustomerTag ct : batch) {
                Customer c = byId.get(ct.getCustomerId());
                if (c == null) continue;
                if (processed.contains(c.getId() + ":" + row.tagId())) continue;
                try {
                    processCustomer(row, index, c, counters);
                } catch (Exception e) {
                    // 单客户失败不炸整批（客户已删会抛 84061 等）
                    log.error("归并失败: customerId={}, tagRow={}, error={}",
                        c.getId(), row.tagId(), e.getMessage());
                    counters.wecomMarkFailed++;
                    recordProgress(c, row, null, null, "WECOM_MARK_FAILED", true, e.getMessage());
                }
            }
        }
    }

    private void processCustomer(GradeTagRow row, WecomTagIndex index, Customer c,
                                 Counters counters) {
        counters.customers++;
        if (c.getCurrentAgent() == null || c.getCurrentAgent().isBlank()) {
            counters.skippedNoAgent++;
            recordProgress(c, row, null, null, "SKIPPED_NO_AGENT", false, null);
            return;
        }

        TargetTag target;
        String action;
        boolean review = false;

        if (row.rowClass() == RowClass.R1) {
            // 客户企微侧已对，零 API 调用
            target = new TargetTag(row.wecomTagId(), index.wid2name().get(row.wecomTagId()));
            action = "LOCAL_ONLY";
        } else {
            List<String> wecomIds = List.of();
            if (row.rowClass() == RowClass.R2) {
                sleepIfNeeded(counters);
                wecomIds = extractWecomTagIds(wecomApi.getExternalContact(c.getExternalUserid()));
                counters.apiCalls++;
            }

            if (index.hasGradeTag(wecomIds)) {
                // 已带年级组标签（可能因升学而异名）→ 按企微实际 id 重接线，绝不补打
                String wid = wecomIds.stream().filter(index.gradeWids()::contains)
                    .findFirst().orElse(null);
                target = new TargetTag(wid, index.wid2name().get(wid));
                action = "WECOM_RELINK";
            } else {
                // 企微确实没有年级标签 → 只能按本地行名补打；升学可能差一级，标 review
                target = ensureGradeTagByName(row.name(), index, counters);
                sleepIfNeeded(counters);
                wecomApi.markTag(c.getExternalUserid(), c.getCurrentAgent(),
                    List.of(target.wecomTagId()));
                counters.apiCalls++;
                action = "WECOM_MARKED";
                review = true;
            }
        }

        Long canonicalId = resolveCanonicalTagId(target);
        if (canonicalId != null && !canonicalId.equals(row.tagId())) {
            customerTagInsertService.rewire(c.getId(), row.tagId(), canonicalId);
        }
        switch (action) {
            case "LOCAL_ONLY" -> counters.localOnly++;
            case "WECOM_RELINK" -> counters.wecomRelink++;
            case "WECOM_MARKED" -> counters.wecomMarked++;
            default -> { }
        }
        if (review) counters.requiresReview++;
        recordProgress(c, row, target.wecomTagId(), canonicalId, action, review, null);
    }

    /**
     * 取「年级」组里名叫 {@code name} 的标签；不存在则新建（组已存在则 add 进该组）。
     *
     * <p>这是全流程<b>唯一按名字走</b>的路径（R1/R2 已带年级组标签的都按 id）——
     * 升学改名会让本地名与企微名差一级，因此调用方一律置 {@code requires_review=1}。</p>
     */
    private TargetTag ensureGradeTagByName(String name, WecomTagIndex index, Counters counters) {
        String wid = index.gradeWidByName(name);
        if (wid != null) return new TargetTag(wid, index.wid2name().get(wid));

        sleepIfNeeded(counters);
        String groupId = index.groupIdByName().get(GRADE_GROUP);
        JsonNode created = (groupId != null && !groupId.isBlank())
            ? wecomApi.addCorpTag(name, groupId)
            : wecomApi.addCorpTagWithGroup(name, GRADE_GROUP);
        counters.apiCalls++;

        String newId = created.path("tag_group").path("tag").path(0).path("id").asText(null);
        if (newId == null || newId.isBlank()) {
            throw new IllegalStateException("add_corp_tag 未返回新标签 id: " + name);
        }
        log.warn("按本地名补建「年级」组标签: name={}, newId={}（本地名可能因升学差一级，请复核）",
            name, newId);
        return new TargetTag(newId, name);
    }

    private void sleepIfNeeded(Counters counters) {
        if (counters.apiCalls > 0 && counters.apiCalls % API_SLEEP_EVERY == 0) {
            log.info("归并限频休眠: apiCalls={}", counters.apiCalls);
            try {
                Thread.sleep(API_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("归并 Job 被中断", e);
            }
        }
    }

    private void recordProgress(Customer c, GradeTagRow row, String targetWid, Long canonicalId,
                                String action, boolean requiresReview, String note) {
        try {
            progressRepo.save(GradeTagMigrationProgress.builder()
                .customerId(c.getId())
                .externalUserid(c.getExternalUserid())
                .rowClass(row.rowClass().name())
                .localTagId(row.tagId())
                .localTagName(row.name())
                .targetWecomTagId(targetWid)
                .targetTagId(canonicalId)
                .action(action)
                .requiresReview(requiresReview)
                .note(note == null ? null : note.substring(0, Math.min(note.length(), 255)))
                .build());
        } catch (Exception e) {
            // 进度行只是观测，写失败不影响正确性（重跑会重新处理这一项）
            log.warn("进度行写入失败: customerId={}, tagRow={}, {}", c.getId(), row.tagId(), e.getMessage());
        }
    }
}
