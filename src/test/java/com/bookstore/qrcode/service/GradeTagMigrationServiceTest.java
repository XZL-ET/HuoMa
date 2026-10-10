package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradeTagMigrationServiceTest {

    @Mock WecomApiClient wecomApi;
    @Mock TagRepository tagRepo;
    @Mock CustomerRepository customerRepo;
    @Mock CustomerTagRepository customerTagRepo;
    @Mock TagInsertService tagInsertService;
    @Mock CustomerTagInsertService customerTagInsertService;
    @Mock GradeTagMigrationProgressRepository progressRepo;
    @Mock ScheduledJobGuard jobGuard;

    @InjectMocks GradeTagMigrationService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 企微 get_corp_tag_list 响应：年级组含「一年级」+「七年级」；学校-{市}组含与年级同名的重复标签 */
    private static final String CORP_TAG_LIST = """
        {"errcode":0,"errmsg":"ok","tag_group":[
          {"group_name":"年级","group_id":"GRADE","tag":[
            {"id":"g1","name":"一年级"},{"id":"g7","name":"七年级"}]},
          {"group_name":"学校-天水市","group_id":"SCH-TS","tag":[
            {"id":"s1","name":"一年级"},{"id":"s7","name":"七年级"},
            {"id":"s2","name":"中班"}]},
          {"group_name":"班级","group_id":"CLS","tag":[{"id":"c1","name":"一班"}]}
        ]}""";

    private Tag row(long id, String name, String gk, String wid) {
        return Tag.builder().id(id).name(name).groupKeyword(gk).wecomTagId(wid).build();
    }

    @Test
    @DisplayName("loadWecomTagIndex 解析组名/id/标签名，并圈出年级组 id 集合")
    void loadsIndex() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));

        var idx = service.loadWecomTagIndex();

        assertEquals("年级", idx.wid2group().get("g1"));
        assertEquals("一年级", idx.wid2name().get("g1"));
        assertEquals("学校-天水市", idx.wid2group().get("s1"));
        assertEquals("GRADE", idx.groupIdByName().get("年级"));
        assertEquals(2, idx.gradeWids().size());
        assertEquals("g1", idx.gradeWidByName("一年级"));
    }

    @Test
    @DisplayName("A0 分类：wid 在年级组→R1；wid 在学校组同名→R2；wid 企微不存在/为空→R3")
    void classifiesRows() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection())).thenReturn(List.of(
            row(1184, "一年级", "学校-天水市", "g1"), // R1：本地行挂在校园组，但 wid 已漂到年级组标签
            row(2127, "一年级", "学校-天水市", "s1"), // R2：wid 是校园组同名标签
            row(125,  "七年级", "", "DEAD"),          // R3（wid 企微已不存在）
            row(126,  "八年级", "", null)             // R3（wid 为空）
        ));

        var rows = service.classifyLocalGradeRows(service.loadWecomTagIndex());

        assertEquals(4, rows.size());
        assertEquals(GradeTagMigrationService.RowClass.R1, rows.get(0).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R2, rows.get(1).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R3, rows.get(2).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R3, rows.get(3).rowClass());
    }

    @Test
    @DisplayName("范围只含 12 个中小学年级名，不含幼儿园与高中毕业")
    void gradeNamesAreExactlyTwelve() {
        assertEquals(List.of("一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
            "七年级", "八年级", "九年级", "高一", "高二", "高三"),
            GradeTagMigrationService.GRADE_NAMES);
    }

    @Test
    @DisplayName("canonical 行已存在（wid 命中年级组行）→ 直接返回该行 id")
    void resolveCanonicalReturnsExisting() {
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        assertEquals(1190L, service.resolveCanonicalTagId(
            new GradeTagMigrationService.TargetTag("g7", "七年级")));
        org.mockito.Mockito.verify(tagInsertService, org.mockito.Mockito.never())
            .insertNew(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("canonical 行不存在 → 按 target.name() 新建 (名, 年级) 并回填 wid")
    void resolveCanonicalCreatesWhenMissing() {
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g9", "年级"))
            .thenReturn(java.util.Optional.empty());
        when(tagInsertService.insertNew("八年级", Tag.TagType.manual, null, "年级"))
            .thenReturn(Tag.builder().id(999L).name("八年级").groupKeyword("年级").build());

        assertEquals(999L, service.resolveCanonicalTagId(
            new GradeTagMigrationService.TargetTag("g9", "八年级")));
        org.mockito.Mockito.verify(tagInsertService).updateWecomTagId(999L, "g9");
    }

    @Test
    @DisplayName("target 为 null / wid 空白 → 返回 null，不建行")
    void resolveCanonicalNullForBlank() {
        assertNull(service.resolveCanonicalTagId(null));
        assertNull(service.resolveCanonicalTagId(new GradeTagMigrationService.TargetTag("  ", "一年级")));
    }

    private static final String EXT_CONTACT_NO_GRADE = """
        {"errcode":0,"errmsg":"ok","external_contact":{"external_userid":"wm1"},
         "follow_user":[{"userid":"ag1","tags":[{"tag_id":"s7","type":1}]}]}""";

    private static final String EXT_CONTACT_HAS_GRADE = """
        {"errcode":0,"errmsg":"ok","external_contact":{"external_userid":"wm2"},
         "follow_user":[{"userid":"ag2","tags":[{"tag_id":"g7","type":1},{"tag_id":"s7","type":1}]}]}""";

    private com.bookstore.qrcode.entity.CustomerTag ct(long id, long customerId, long tagId) {
        return com.bookstore.qrcode.entity.CustomerTag.builder()
            .id(id).customerId(customerId).tagId(tagId).build();
    }

    private com.bookstore.qrcode.entity.Customer cust(long id, String eid, String agent) {
        return com.bookstore.qrcode.entity.Customer.builder()
            .id(id).externalUserid(eid).currentAgent(agent)
            .status(com.bookstore.qrcode.entity.Customer.CustomerStatus.active).build();
    }

    /** 一个客户、一条本地年级标签行的公共桩。 */
    private void stubSingleRow(String corpTagListJson, long tagRowId, String tagRowName,
                               String tagRowGk, String tagRowWid,
                               long customerId, String eid, String agent) throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(corpTagListJson));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(tagRowId, tagRowName, tagRowGk, tagRowWid)));
        when(customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq(tagRowId), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(ct(1L, customerId, tagRowId)))
            .thenReturn(List.of());
        when(customerRepo.findAllById(List.of(customerId)))
            .thenReturn(List.of(cust(customerId, eid, agent)));
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("tok");
    }

    @Test
    @DisplayName("extractWecomTagIds 汇总所有 follow_user 的 tag_id")
    void extractsTagIds() throws Exception {
        assertEquals(List.of("g7", "s7"),
            GradeTagMigrationService.extractWecomTagIds(MAPPER.readTree(EXT_CONTACT_HAS_GRADE)));
        assertTrue(GradeTagMigrationService.extractWecomTagIds(null).isEmpty());
    }

    @Test
    @DisplayName("R1 客户：零企微调用，只做本地重接线（canonical 已是本行时不写）")
    void r1IsLocalOnly() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 1184L, "一年级", "学校-天水市", "g1", 11L, "wmA", "agA");
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g1", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1184L).name("一年级").groupKeyword("年级").wecomTagId("g1").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.rows());
        assertEquals(1, report.localOnly());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .getExternalContact(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(jobGuard).release(
            org.mockito.ArgumentMatchers.eq(GradeTagMigrationService.JOB_LOCK_KEY),
            org.mockito.ArgumentMatchers.eq("tok"));
    }

    @Test
    @DisplayName("R2 客户已带年级组标签（异名，升学后）→ 按企微实际 id 重接线，不补打")
    void r2WithGradeTagRelinksByWecomId() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 2127L, "一年级", "学校-天水市", "s1", 12L, "wm2", "ag2");
        when(wecomApi.getExternalContact("wm2")).thenReturn(MAPPER.readTree(EXT_CONTACT_HAS_GRADE));
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomRelink());
        org.mockito.Mockito.verify(customerTagInsertService).rewire(12L, 2127L, 1190L);
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("R3 客户：按本地名补打「年级」组标签，并标记 requires_review=1")
    void r3MarksByLocalNameAndFlagsReview() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 13L, "wm3", "ag3");
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarked());
        assertEquals(1, report.requiresReview());
        org.mockito.Mockito.verify(wecomApi).markTag("wm3", "ag3", List.of("g7"));
        org.mockito.Mockito.verify(customerTagInsertService).rewire(13L, 125L, 1190L);
    }

    @Test
    @DisplayName("R2 客户企微侧完全没有年级标签 → 按本地名补打进年级组，标 review")
    void r2WithoutGradeTagMarksByName() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 2127L, "七年级", "学校-天水市", "s7", 15L, "wm1", "ag1");
        when(wecomApi.getExternalContact("wm1")).thenReturn(MAPPER.readTree(EXT_CONTACT_NO_GRADE));
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarked());
        assertEquals(1, report.requiresReview());
        org.mockito.Mockito.verify(wecomApi).markTag("wm1", "ag1", List.of("g7"));
    }

    @Test
    @DisplayName("无 current_agent 的客户：跳过并计数，不调 markTag")
    void skipsCustomerWithoutAgent() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 14L, "wm4", " ");

        var report = service.runPhaseA(false);

        assertEquals(1, report.skippedNoAgent());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("markTag 抛错（客户已删/84061）不炸整批：计入 wecomMarkFailed，不重接线（留旧绑定给重跑）")
    void markFailureIsIsolated() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 13L, "wm3", "ag3");
        org.mockito.Mockito.doThrow(new RuntimeException("84061 not external contact"))
            .when(wecomApi).markTag("wm3", "ag3", List.of("g7"));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarkFailed());
        org.mockito.Mockito.verify(customerTagInsertService, org.mockito.Mockito.never())
            .rewire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(jobGuard).release(
            org.mockito.ArgumentMatchers.eq(GradeTagMigrationService.JOB_LOCK_KEY),
            org.mockito.ArgumentMatchers.eq("tok"));
    }

    @Test
    @DisplayName("拿不到分布式锁时立即抛错，不重复跑")
    void refusesWhenLockHeld() throws Exception {
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.runPhaseA(false));
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never()).getCorpTagList();
    }

    @Test
    @DisplayName("dry-run：只分类统计，零企微写、零写库")
    void dryRunDoesNothing() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(125L, "七年级", "", "DEAD")));
        when(customerTagRepo.countByTagIdIn(List.of(125L))).thenReturn(7L);

        var report = service.runPhaseA(true);

        assertTrue(report.dryRun());
        assertEquals(1, report.rows());
        assertEquals(7, report.customers());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(customerTagInsertService, org.mockito.Mockito.never())
            .rewire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(progressRepo, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("续跑：进度表里已有该 (customer, tagRow) 时跳过，不再调企微")
    void resumesFromProgress() throws Exception {
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("tok");
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(125L, "七年级", "", "DEAD")));
        when(progressRepo.findProcessedKeys())
            .thenReturn(List.<Object[]>of(new Object[]{13L, 125L}));
        when(customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq(125L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(ct(3L, 13L, 125L)))
            .thenReturn(List.of());
        when(customerRepo.findAllById(List.of(13L))).thenReturn(List.of(cust(13L, "wm3", "ag3")));

        var report = service.runPhaseA(false);

        assertEquals(0, report.wecomMarked());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
