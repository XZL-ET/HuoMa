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
}
