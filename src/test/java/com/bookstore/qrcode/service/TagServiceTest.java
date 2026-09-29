package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomPermanentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TagService 标签创建")
class TagServiceTest {

    private static final String CORP_TAG_LIST = """
        {"tag_group":[
          {"group_id":"g_city","group_name":"市州","tag":[{"id":"t_city","name":"北京市"}]},
          {"group_id":"g_dist","group_name":"县区","tag":[{"id":"t_dist","name":"海淀区"}]},
          {"group_id":"g_school","group_name":"学校-北京市","tag":[{"id":"t_school","name":"北京一中"}]},
          {"group_id":"g_other","group_name":"学校-兰州市","tag":[{"id":"t_other","name":"北京一中"}]}
        ]}
        """;

    /** 生产事故现场：区名与市名同名（张掖市/张掖市），且"县区|张掖市"的本地 ID 已过期。 */
    private static final String CORP_TAG_LIST_ZHANGYE = """
        {"tag_group":[
          {"group_id":"g_city","group_name":"市州","tag":[{"id":"t_city","name":"张掖市"}]},
          {"group_id":"g_dist","group_name":"县区","tag":[{"id":"t_dist_new","name":"张掖市"}]},
          {"group_id":"g_school","group_name":"学校-张掖市","tag":[{"id":"t_school","name":"金安苑学校"}]}
        ]}
        """;

    @Mock private TagRepository tagRepo;
    @Mock private CustomerTagRepository customerTagRepo;
    @Mock private TagInsertService tagInsertService;
    @Mock private CustomerTagInsertService customerTagInsertService;
    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private FormTemplateRepository formTemplateRepo;
    @Mock private FormSubmissionRepository formSubmissionRepo;
    @Mock private WecomApiClient wecomApi;
    @Mock private ObjectMapper objectMapper;
    @Mock private AlertService alertService;
    @Mock private LeakMetrics leakMetrics;

    @InjectMocks private TagService tagService;

    private static final ObjectMapper REAL_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("getOrCreateTag — 并发插入冲突时重查复用已有记录")
    void reusesExistingOnConcurrentInsertConflict() {
        when(tagRepo.findFirstByNameAndGroupKeyword("北京", "市州"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(Tag.builder().id(9L).name("北京")
                .groupKeyword("市州").type(Tag.TagType.system).wecomTagId("t9").build()));
        doThrow(new DataIntegrityViolationException("dup uk_tag_name_group"))
            .when(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));

        Tag result = tagService.getOrCreateTag("北京", Tag.TagType.system, null, "市州");

        assertThat(result.getId()).isEqualTo(9L);
        verify(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));
    }

    @Test
    @DisplayName("getOrCreateTag — 冲突后重查仍查不到时原样抛出")
    void rethrowsWhenWinnerMissing() {
        when(tagRepo.findFirstByNameAndGroupKeyword("北京", "市州"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup uk_tag_name_group"))
            .when(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));

        assertThatThrownBy(() -> tagService.getOrCreateTag("北京", Tag.TagType.system, null, "市州"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("syncExistingTagToWecom — 同名标签分布在多个组时补同步到关键词指定的组")
    void syncsToGroupMatchedByKeyword() throws Exception {
        // 本地标签缺 wecomTagId，企微侧 "北京一中" 同时存在于 学校-北京市 和 学校-兰州市 两个组
        Tag local = Tag.builder().id(7L).name("北京一中")
            .groupKeyword("学校-北京市").type(Tag.TagType.system).build();
        when(tagRepo.findFirstByNameAndGroupKeyword("北京一中", "学校-北京市"))
            .thenReturn(Optional.of(local));
        when(wecomApi.getCorpTagList()).thenReturn(REAL_MAPPER.readTree(CORP_TAG_LIST));
        when(tagInsertService.updateWecomTagId(7L, "t_school")).thenReturn(wecomBoundTag(
            7L, "北京一中", "学校-北京市", "t_school"));

        Tag result = tagService.getOrCreateTag("北京一中", Tag.TagType.system, null, "学校-北京市");

        assertThat(result.getWecomTagId())
            .as("必须取 学校-北京市 组内的 ID，而不是企微返回顺序里的第二个同名标签")
            .isEqualTo("t_school");
        verify(tagInsertService).updateWecomTagId(7L, "t_school");
        verify(tagRepo, never()).save(any());
    }

    @Test
    @DisplayName("syncExistingTagToWecom — 组关键词匹配不到任何组时回退全局按名查找")
    void fallsBackToGlobalNameLookupWhenGroupUnresolved() throws Exception {
        Tag local = Tag.builder().id(8L).name("北京一中")
            .groupKeyword("不存在的组").type(Tag.TagType.system).build();
        when(tagRepo.findFirstByNameAndGroupKeyword("北京一中", "不存在的组"))
            .thenReturn(Optional.of(local));
        when(wecomApi.getCorpTagList()).thenReturn(REAL_MAPPER.readTree(CORP_TAG_LIST));
        when(tagInsertService.updateWecomTagId(8L, "t_school")).thenReturn(wecomBoundTag(
            8L, "北京一中", "不存在的组", "t_school"));

        Tag result = tagService.getOrCreateTag("北京一中", Tag.TagType.system, null, "不存在的组");

        assertThat(result.getWecomTagId())
            .as("回退到原有全局按名查找，行为与改动前一致")
            .isEqualTo("t_school");
    }

    @Test
    @DisplayName("getOrCreateTag — wecomTagId 过期时经独立事务写入器更新，不在外层事务写 tag 行")
    void repairsStaleWecomTagIdOutsideCallerTransaction() throws Exception {
        // 生产事故（张掖市/甘州区）：本地 "县区|张掖市" 存的是旧 ID，企微侧已是新 ID
        Tag stale = Tag.builder().id(2894L).name("张掖市").groupKeyword("县区")
            .type(Tag.TagType.system).wecomTagId("old_id").build();
        when(tagRepo.findFirstByNameAndGroupKeyword("张掖市", "县区")).thenReturn(Optional.of(stale));
        when(wecomApi.getCorpTagList()).thenReturn(REAL_MAPPER.readTree(CORP_TAG_LIST_ZHANGYE));
        when(tagInsertService.updateWecomTagId(2894L, "t_dist_new"))
            .thenReturn(wecomBoundTag(2894L, "张掖市", "县区", "t_dist_new"));

        Tag result = tagService.getOrCreateTag("张掖市", Tag.TagType.system, null, "县区");

        assertThat(result.getWecomTagId()).isEqualTo("t_dist_new");
        verify(tagInsertService).updateWecomTagId(2894L, "t_dist_new");
        verify(tagRepo, never()).save(any());
    }

    @Test
    @DisplayName("autoTag — tag 行修复不落在外层事务，customer_tag 外键插入不再被 X 锁挡住")
    void autoTag_repairsTagOutsideOuterTransaction() throws Exception {
        // 复刻生产现场：活码 张掖市/张掖市（区名=市名），区标签本地 ID 已过期
        when(qrCodeRepo.findBySchoolId("SCH-ZY")).thenReturn(Optional.of(QrCode.builder()
            .schoolId("SCH-ZY").schoolName("金安苑学校")
            .regionCity("张掖市").regionDistrict("张掖市")
            .build()));
        when(customerRepo.findByExternalUserid("wmZY")).thenReturn(Optional.of(Customer.builder()
            .id(38234L).externalUserid("wmZY").status(Customer.CustomerStatus.active).build()));
        when(tagRepo.findFirstByNameAndGroupKeyword("张掖市", "市州"))
            .thenReturn(Optional.of(wecomBoundTag(598L, "张掖市", "市州", "t_city")));
        when(tagRepo.findFirstByNameAndGroupKeyword("张掖市", "县区"))
            .thenReturn(Optional.of(Tag.builder().id(2894L).name("张掖市").groupKeyword("县区")
                .type(Tag.TagType.system).wecomTagId("old_id").build()));
        when(tagRepo.findFirstByNameAndGroupKeyword("金安苑学校", "学校-张掖市"))
            .thenReturn(Optional.of(wecomBoundTag(1142L, "金安苑学校", "学校-张掖市", "t_school")));
        when(wecomApi.getCorpTagList()).thenReturn(REAL_MAPPER.readTree(CORP_TAG_LIST_ZHANGYE));
        when(tagInsertService.updateWecomTagId(2894L, "t_dist_new"))
            .thenReturn(wecomBoundTag(2894L, "张掖市", "县区", "t_dist_new"));

        tagService.autoTag("wmZY", "user1", "SCH-ZY");

        // 核心不变式：外层事务里没有任何 tag 行的 save（否则客户标签插入的外键检查会等锁到 30s 超时）
        verify(tagRepo, never()).save(any());
        verify(customerTagInsertService).insert(38234L, 2894L, "system");
        verify(wecomApi).markTag(eq("wmZY"), eq("user1"),
            argThat(ids -> ids.containsAll(java.util.List.of("t_city", "t_dist_new", "t_school"))));
    }

    @Test
    @DisplayName("autoTag — 客户拒收(25002)只告警不抛出，保持本地关联")
    void autoTag_swallowsCustomerRelationGone() {
        stubAutoTagHappyPath();
        doThrow(new WecomPermanentException(25002, "客户拒收", null))
            .when(wecomApi).markTag(eq("wmXXX"), eq("user1"), anyList());

        tagService.autoTag("wmXXX", "user1", "SCH-1");

        verify(alertService).handleCustomerApiError(eq("user1"), eq("wmXXX"), eq(25002), any(), eq("SCH-1"));
        verify(leakMetrics, never()).tagPermanentFailure(anyInt());
    }

    @Test
    @DisplayName("autoTag — 未识别的永久错误码抛出，交由 TagWorker 送死信队列")
    void autoTag_throwsUnknownPermanentFailure() {
        stubAutoTagHappyPath();
        doThrow(new WecomPermanentException(99999, "未知错误", null))
            .when(wecomApi).markTag(eq("wmXXX"), eq("user1"), anyList());

        assertThatThrownBy(() -> tagService.autoTag("wmXXX", "user1", "SCH-1"))
            .isInstanceOf(WecomPermanentException.class)
            .hasFieldOrPropertyWithValue("errcode", 99999);

        verify(leakMetrics).tagPermanentFailure(99999);
    }

    @Test
    @DisplayName("autoTag — 活码查不到时计数并记录 ERROR")
    void autoTag_countsMissingQrCode() {
        when(qrCodeRepo.findBySchoolId("SCH-MISSING")).thenReturn(Optional.empty());

        tagService.autoTag("wmXXX", "user1", "SCH-MISSING");

        verify(leakMetrics).tagSkipMissingQrCode();
        verify(wecomApi, never()).markTag(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("applyFormTags — 客户已删除时跳过打标")
    void applyFormTags_skipsDeletedCustomer() {
        when(formTemplateRepo.findById(1L))
            .thenReturn(Optional.of(FormTemplate.builder().id(1L).fields("[]").tagMapping("{}").build()));
        when(customerRepo.findByExternalUserid("wmXXX"))
            .thenReturn(Optional.of(Customer.builder()
                .externalUserid("wmXXX").status(Customer.CustomerStatus.deleted).build()));

        tagService.applyFormTags("wmXXX", "user1", 1L, 10L, "{}", "测试学校");

        verify(tagRepo, never()).findFirstByNameAndGroupKeyword(anyString(), anyString());
    }

    /**
     * 让 autoTag 走到 markTag 调用点：三级地域标签本地已存在且 wecomTagId 有效，
     * 使 getOrCreateTag 的 Phase 2 不触发补同步（补同步路径另有用例覆盖）。
     */
    private void stubAutoTagHappyPath() {
        when(qrCodeRepo.findBySchoolId("SCH-1")).thenReturn(Optional.of(QrCode.builder()
            .schoolId("SCH-1").schoolName("北京一中")
            .regionCity("北京市").regionDistrict("海淀区")
            .build()));
        when(customerRepo.findByExternalUserid("wmXXX"))
            .thenReturn(Optional.of(Customer.builder()
                .id(1L).externalUserid("wmXXX").status(Customer.CustomerStatus.active).build()));
        when(tagRepo.findFirstByNameAndGroupKeyword("北京市", "市州"))
            .thenReturn(Optional.of(wecomBoundTag(1L, "北京市", "市州", "t_city")));
        when(tagRepo.findFirstByNameAndGroupKeyword("海淀区", "县区"))
            .thenReturn(Optional.of(wecomBoundTag(2L, "海淀区", "县区", "t_dist")));
        when(tagRepo.findFirstByNameAndGroupKeyword("北京一中", "学校-北京市"))
            .thenReturn(Optional.of(wecomBoundTag(3L, "北京一中", "学校-北京市", "t_school")));
        when(wecomApi.getCorpTagList()).thenAnswer(inv -> {
            try {
                return REAL_MAPPER.readTree(CORP_TAG_LIST);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Tag wecomBoundTag(Long id, String name, String groupKeyword, String wecomTagId) {
        return Tag.builder().id(id).name(name).groupKeyword(groupKeyword)
            .type(Tag.TagType.system).wecomTagId(wecomTagId).build();
    }
}
