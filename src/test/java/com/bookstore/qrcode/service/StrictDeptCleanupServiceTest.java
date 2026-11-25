package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.StrictDeptCleanupPreview;
import com.bookstore.qrcode.dto.StrictDeptCleanupResult;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrRotateLog;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.OperationLogRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 严格同部门清退编排（Task 5）。
 *
 * <p>真实 H2 + 真实撤人服务，仅 mock 企微部门树、全局池取人与企微同步，
 * 以便同时验证「preview 只读」「apply 复用撤人服务」「跳过规则」三类语义。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("严格同部门清退编排：只读预览 + 确认应用")
class StrictDeptCleanupServiceTest extends BaseIntegrationTest {

    /** 本县根部门（含子部门 144）。 */
    private static final Long LOCAL_ROOT = 52L;
    private static final Long LOCAL_CHILD = 144L;
    /** 另一个本县根部门，用于分片用例。 */
    private static final Long SECOND_ROOT = 66L;
    /** 外县部门。 */
    private static final Long OTHER_DEPT = 888L;

    @Autowired private StrictDeptCleanupService cleanupService;
    @Autowired private StrictDeptConfigService strictDeptConfig;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private OperationLogRepository operationLogRepo;
    @Autowired private SystemConfigRepository systemConfigRepo;

    @MockBean private DepartmentService departmentService;
    @MockBean private GlobalAgentPoolService globalAgentPoolService;
    @MockBean private AgentRotationService agentRotationService;
    @MockBean private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        // R8：先清掉可能残留的 system_config，再显式写入本用例需要的值
        deleteStrictConfig();
        strictDeptConfig.setStrictModeEnabled(true);
        strictDeptConfig.setExemptUserids(List.of());

        rotateLogRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        operationLogRepo.deleteAll();
        whitelistRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();

        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            LOCAL_ROOT, new DepartmentService.DeptNode(LOCAL_ROOT, "本县", 0L),
            LOCAL_CHILD, new DepartmentService.DeptNode(LOCAL_CHILD, "本县子部门", LOCAL_ROOT),
            SECOND_ROOT, new DepartmentService.DeptNode(SECOND_ROOT, "第二县", 0L),
            97L, new DepartmentService.DeptNode(97L, "未入白名单县", 0L)));
    }

    @AfterEach
    void tearDown() {
        // 删除键而非回写：缺省豁免名单（5 人）与 "[]" 语义不同，回写会污染其它用例
        deleteStrictConfig();
    }

    private void deleteStrictConfig() {
        systemConfigRepo.findByConfigKey(StrictDeptConfigService.STRICT_MODE_KEY)
            .ifPresent(systemConfigRepo::delete);
        systemConfigRepo.findByConfigKey(StrictDeptConfigService.EXEMPT_USERIDS_KEY)
            .ifPresent(systemConfigRepo::delete);
    }

    // ==================== preview ====================

    @Test
    @DisplayName("preview 不写库：池行数与排序不变，且不调用任何 takeStandby")
    void preview不写库_池行数与排序不变() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-NOWRITE", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        seedStandby("sbLocal", "[" + LOCAL_ROOT + "]", LOCAL_ROOT, 3);
        seedStandby("sbOther", "[" + OTHER_DEPT + "]", OTHER_DEPT, 7);

        Map<String, Integer> before = poolSnapshot();
        int beforeSize = poolRepo.findAll().size();

        cleanupService.preview(null);
        cleanupService.preview(null);

        Map<String, Integer> after = poolSnapshot();
        assertThat(poolRepo.findAll()).hasSize(beforeSize);
        assertThat(after).isEqualTo(before);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "ext"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));

        verify(globalAgentPoolService, never()).takeStandby(anySet());
        verify(globalAgentPoolService, never()).takeStandby(anySet(), any());
        verify(globalAgentPoolService, never()).takeStandbyStrict(anySet(), any());
    }

    @Test
    @DisplayName("preview 跳过 departmentId 为空的码并记 warning")
    void preview跳过departmentId为空的码() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-NULL-DEPT", null, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.codeCount()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("未设置所属部门"));
        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).as("R12：跳过码仍放进 codes").isNotNull();
        assertThat(plan.skipReason()).isNotBlank();
        assertThat(plan.members()).isEmpty();
    }

    @Test
    @DisplayName("preview 根部门在白名单即处理，不按子部门集合判定跳过")
    void preview根部门在白名单即处理_不按子部门集合判定跳过() {
        // 白名单只有根 52；子部门 144 不在白名单
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-ROOT-ONLY", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("localRoot", "[" + LOCAL_ROOT + "]");
        seedEmployee("localChild", "[" + LOCAL_CHILD + "]");
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "localRoot", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "localChild", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).isNotNull();
        assertThat(plan.skipReason()).isNull();
        assertThat(plan.members()).extracting(StrictDeptCleanupPreview.Member::userid)
            .containsExactly("ext");
        assertThat(preview.codeCount()).isEqualTo(1);
        assertThat(preview.receptionistCount()).isEqualTo(1);
        assertThat(preview.warnings()).isEmpty();
    }

    @Test
    @DisplayName("preview 部门不在白名单时跳过并记 warning")
    void preview部门不在白名单时跳过并记warning() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-OUT-WHITELIST", 97L, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.codeCount()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("白名单"));
        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).isNotNull();
        assertThat(plan.skipReason()).isNotBlank();
    }

    @Test
    @DisplayName("preview 部门树不可用时整体中止")
    void preview部门树不可用时中止() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-TREE-DOWN", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("dept tree down"));

        assertThatThrownBy(() -> cleanupService.preview(null))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
    }

    @Test
    @DisplayName("preview 豁免服务老师不计入待撤")
    void preview豁免服务老师不计入待撤() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-EXEMPT", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("exemptTeacher", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "exemptTeacher", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        strictDeptConfig.setExemptUserids(List.of("exemptTeacher"));

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.strictModeEnabled()).isTrue();
        assertThat(preview.exemptUserids()).contains("exemptTeacher");
        assertThat(preview.receptionistCount()).isZero();
        assertThat(preview.codes()).isEmpty();
    }

    @Test
    @DisplayName("preview 跳过 paused 码并记 warning")
    void preview跳过paused码并记warning() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-PAUSED", LOCAL_ROOT, QrCode.QrCodeStatus.paused);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.codeCount()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("暂停"));
        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).isNotNull();
        assertThat(plan.skipReason()).contains("暂停");
    }

    @Test
    @DisplayName("preview 员工档案缺失时保留并记 warning")
    void preview员工档案缺失时保留并记warning() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-GHOST", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        // 只有 Agent，没有 Employee 档案
        agentRepo.save(Agent.builder().userid("ghost").name("ghost").build());
        bind(qr.getId(), "ghost", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.codeCount()).isZero();
        assertThat(preview.receptionistCount()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("缺少员工记录"));
    }

    @Test
    @DisplayName("preview 员工部门缺失/不可解析时保留并记 warning（不当作外县）")
    void preview员工部门不可判定时保留() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-UNKNOWN-DEPT", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        seedEmployee("blankDept", "");
        seedEmployee("nullDept", null);
        seedEmployee("badJson", "not-json");
        seedEmployee("emptyArr", "[]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "blankDept", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "nullDept", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "badJson", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "emptyArr", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).isNotNull();
        assertThat(plan.members()).extracting(StrictDeptCleanupPreview.Member::userid)
            .containsExactly("ext");
        assertThat(preview.receptionistCount()).isEqualTo(1);
        assertThat(preview.warnings())
            .filteredOn(w -> w.contains("部门缺失或无法解析，保留不撤"))
            .anyMatch(w -> w.contains("blankDept"))
            .anyMatch(w -> w.contains("nullDept"))
            .anyMatch(w -> w.contains("badJson"))
            .anyMatch(w -> w.contains("emptyArr"))
            .allMatch(w -> w.contains("活码 " + qr.getId() + " 上接待员"));
    }

    @Test
    @DisplayName("preview 根部门不在部门树时跳过并记 warning")
    void preview根部门不在部门树时跳过并记warning() {
        whitelist(999L);
        QrCode qr = newQr("PREVIEW-NO-TREE", 999L, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupPreview preview = cleanupService.preview(null);

        assertThat(preview.codeCount()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("不在部门树"));
        StrictDeptCleanupPreview.QrPlan plan = planFor(preview, qr.getId());
        assertThat(plan).isNotNull();
        assertThat(plan.skipReason()).contains("不在部门树");
    }

    @Test
    @DisplayName("preview 统计本县可补人数（只读池）")
    void preview统计本县可补人数() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("PREVIEW-STANDBY", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        seedStandby("sbLocal", "[" + LOCAL_ROOT + "]", LOCAL_ROOT, 1);
        seedStandby("sbOther", "[" + OTHER_DEPT + "]", OTHER_DEPT, 2);

        StrictDeptCleanupPreview.QrPlan plan = planFor(cleanupService.preview(null), qr.getId());

        assertThat(plan).isNotNull();
        assertThat(plan.needsBackfill()).isTrue();
        assertThat(plan.localStandbyAvailable()).isTrue();
        assertThat(poolRepo.findAll()).hasSize(2);
    }

    // ==================== apply ====================

    @Test
    @DisplayName("apply 清退外县接待员并保留本县服务老师")
    void apply清退外县接待员并保留本县服务老师() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("APPLY-BASIC", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("svcLocal", "[" + LOCAL_ROOT + "]");
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "svcLocal", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        assertThat(result.codesProcessed()).isEqualTo(1);
        assertThat(result.codesSkipped()).isZero();
        assertThat(result.activeRemoved()).isEqualTo(1);
        assertThat(result.fullRemoved()).isZero();
        assertThat(result.backfilled()).isZero();
        assertThat(result.failures()).isEmpty();

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "ext"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "svcLocal"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));

        assertThat(rotateLogRepo.findAll())
            .anySatisfy(log -> {
                assertThat(log.getQrCodeId()).isEqualTo(qr.getId());
                assertThat(log.getFromUserid()).isEqualTo("ext");
                assertThat(log.getReason()).isEqualTo("严格同部门移出");
            });
        verify(agentRotationService).syncQrCodeToWechatAsync(qr.getId());
        verify(operationLogService).log(eq("tester"), eq("strict_dept_cleanup"),
            eq("qr_code"), eq("-"), anyString());
    }

    @Test
    @DisplayName("apply 员工部门缺失/不可解析时不撤绑定、不写轮换日志")
    void apply员工部门不可判定时保留() {
        whitelist(LOCAL_ROOT);
        QrCode blankQr = newQr("APPLY-BLANK-DEPT", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        // 本县接待员保证撤人后仍 >= 1 人，避免「补位失败整体跳过」掩盖旧行为
        seedEmployee("localTea", "[" + LOCAL_ROOT + "]");
        seedEmployee("blankDept", "");
        bind(blankQr.getId(), "localTea", QrAgent.AgentRole.receptionist,
            QrAgent.AgentStatus.active);
        bind(blankQr.getId(), "blankDept", QrAgent.AgentRole.receptionist,
            QrAgent.AgentStatus.active);

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        assertThat(result.activeRemoved()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(blankQr.getId(), "blankDept"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(blankQr.getId(), "localTea"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));
        assertThat(rotateLogRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("apply 员工部门为坏 JSON 时同样保留不撤")
    void apply坏部门JSON时保留() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("APPLY-BAD-JSON", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("localTea", "[" + LOCAL_ROOT + "]");
        seedEmployee("badJson", "not-json");
        bind(qr.getId(), "localTea", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "badJson", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        assertThat(result.activeRemoved()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "badJson"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));
        assertThat(rotateLogRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("apply 对本县仅有服务老师、池中无人的码照常撤且不 skip")
    void apply对15个无备选码照常撤且不skip() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("APPLY-NO-STANDBY", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("svcLocal", "[" + LOCAL_ROOT + "]");
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        seedEmployee("sbOther", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "svcLocal", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(qr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        // 池里只有外县人，严格补位也补不到；但本县服务老师仍在，撤人无需补位
        agentRepo.save(Agent.builder().userid("sbOther").name("sbOther").build());
        poolRepo.save(GlobalAgentPool.builder().agentUserid("sbOther")
            .departmentId(OTHER_DEPT).sortOrder(1)
            .status(GlobalAgentPool.PoolStatus.standby).build());

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        assertThat(result.activeRemoved()).isGreaterThan(0);
        assertThat(result.codesSkipped()).isZero();
        assertThat(result.backfilled()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .extracting(QrAgent::getAgentUserid).containsExactly("svcLocal");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "ext"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed));
        verify(globalAgentPoolService, never()).takeStandbyStrict(anySet(), any());
        verify(globalAgentPoolService, never()).takeStandby(anySet(), any());
    }

    @Test
    @DisplayName("apply 支持按区县分片")
    void apply支持按区县分片() {
        whitelist(LOCAL_ROOT, SECOND_ROOT);
        QrCode keepRootQr = newQr("APPLY-SHARD-A", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        QrCode otherRootQr = newQr("APPLY-SHARD-B", SECOND_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("svcA", "[" + LOCAL_ROOT + "]");
        seedEmployee("extA", "[" + OTHER_DEPT + "]");
        seedEmployee("svcB", "[" + SECOND_ROOT + "]");
        seedEmployee("extB", "[" + OTHER_DEPT + "]");
        bind(keepRootQr.getId(), "svcA", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(keepRootQr.getId(), "extA", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(otherRootQr.getId(), "svcB", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(otherRootQr.getId(), "extB", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        StrictDeptCleanupResult result = cleanupService.apply(Set.of(LOCAL_ROOT), "tester");

        assertThat(result.codesProcessed()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(keepRootQr.getId(), "extA"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(otherRootQr.getId(), "extB"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("apply 审计区分跳过与真实失败：失败只计真实失败，跳过/提示单独计数")
    void apply审计区分跳过与真实失败() {
        whitelist(LOCAL_ROOT);

        // 1) 跳过码：departmentId 为空 → 计一条「跳过」
        QrCode skippedQr = newQr("AUDIT-SKIP", null, QrCode.QrCodeStatus.active);
        seedEmployee("skipExt", "[" + OTHER_DEPT + "]");
        bind(skippedQr.getId(), "skipExt", QrAgent.AgentRole.receptionist,
            QrAgent.AgentStatus.active);

        // 2) 提示：有接待员但无员工档案 → 计一条「提示」，不是失败
        QrCode ghostQr = newQr("AUDIT-GHOST", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        agentRepo.save(Agent.builder().userid("ghost").name("ghost").build());
        bind(ghostQr.getId(), "ghost", QrAgent.AgentRole.receptionist,
            QrAgent.AgentStatus.active);

        // 3) 真实失败：撤人成功但企微同步抛异常 → 计一条「失败」
        QrCode okQr = newQr("AUDIT-OK", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("svcLocal", "[" + LOCAL_ROOT + "]");
        seedEmployee("ext", "[" + OTHER_DEPT + "]");
        bind(okQr.getId(), "svcLocal", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(okQr.getId(), "ext", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        doThrow(new RuntimeException("wecom down"))
            .when(agentRotationService).syncQrCodeToWechatAsync(anyLong());

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).log(eq("tester"), eq("strict_dept_cleanup"),
            eq("qr_code"), eq("-"), description.capture());
        assertThat(description.getValue())
            .contains("处理 1 码")
            .contains("跳过 1 码")
            .contains("失败 1 条")
            .contains("提示 1 条");

        // 跳过原因与真实失败各一条，不重复计入
        assertThat(result.failures()).hasSize(2);
        assertThat(result.failures()).anyMatch(f -> f.contains("未设置所属部门"))
            .anyMatch(f -> f.contains("企微同步失败"));
        assertThat(result.failures()).noneMatch(f -> f.contains("缺少员工记录"));
    }

    @Test
    @DisplayName("apply 同时撤下 full 外县接待员")
    void apply同时撤下full外县接待员() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("APPLY-FULL", LOCAL_ROOT, QrCode.QrCodeStatus.full);
        seedEmployee("svcLocal", "[" + LOCAL_ROOT + "]");
        seedEmployee("extFull", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "svcLocal", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind(qr.getId(), "extFull", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.full);

        StrictDeptCleanupResult result = cleanupService.apply(null, "tester");

        assertThat(result.fullRemoved()).isEqualTo(1);
        assertThat(result.activeRemoved()).isZero();
        assertThat(result.codesProcessed()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "extFull"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus()).isEqualTo(QrAgent.AgentStatus.removed));
        verify(agentRotationService).syncQrCodeToWechatAsync(qr.getId());
    }

    @Test
    @DisplayName("countDriftedActiveReceptionists 统计外县非豁免接待员并排除 paused")
    void countDrifted排除paused与豁免() {
        whitelist(LOCAL_ROOT);
        QrCode activeQr = newQr("DRIFT-ACTIVE", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        QrCode pausedQr = newQr("DRIFT-PAUSED", LOCAL_ROOT, QrCode.QrCodeStatus.paused);
        seedEmployee("localTea", "[" + LOCAL_ROOT + "]");
        seedEmployee("extActive", "[" + OTHER_DEPT + "]");
        seedEmployee("extPaused", "[" + OTHER_DEPT + "]");
        seedEmployee("extExempt", "[" + OTHER_DEPT + "]");
        bind(activeQr.getId(), "localTea", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(activeQr.getId(), "extActive", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(activeQr.getId(), "extExempt", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(pausedQr.getId(), "extPaused", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        strictDeptConfig.setExemptUserids(List.of("extExempt"));

        assertThat(cleanupService.countDriftedActiveReceptionists()).isEqualTo(1);
    }

    @Test
    @DisplayName("countDrifted 部门树不可用 → 向上抛 DeptTreeUnavailableException（不误报全漂移）")
    void countDrifted抛树不可用异常() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("DRIFT-TREE-DOWN", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("extActive", "[" + OTHER_DEPT + "]");
        bind(qr.getId(), "extActive", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("dept tree down"));

        assertThatThrownBy(() -> cleanupService.countDriftedActiveReceptionists())
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
    }

    @Test
    @DisplayName("countDrifted 员工部门为空/不可解析 → 不计为漂移")
    void countDrifted跳过无部门员工() {
        whitelist(LOCAL_ROOT);
        QrCode qr = newQr("DRIFT-NO-DEPT", LOCAL_ROOT, QrCode.QrCodeStatus.active);
        seedEmployee("extActive", "[" + OTHER_DEPT + "]");
        seedEmployee("blankDept", null);
        seedEmployee("badJson", "not-a-json");
        bind(qr.getId(), "extActive", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "blankDept", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        bind(qr.getId(), "badJson", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        assertThat(cleanupService.countDriftedActiveReceptionists()).isEqualTo(1);
    }

    // ==================== helpers ====================

    private QrCode newQr(String schoolId, Long deptId, QrCode.QrCodeStatus status) {
        return qrCodeRepo.save(QrCode.builder()
            .schoolName("学校" + schoolId).schoolId(schoolId)
            .regionCity("测试市").regionDistrict("测试县")
            .departmentId(deptId).status(status).build());
    }

    private void seedEmployee(String userid, String departmentJson) {
        agentRepo.save(Agent.builder().userid(userid).name(userid).build());
        employeeRepo.save(Employee.builder()
            .userid(userid).name(userid).department(departmentJson)
            .active(true).wechatStatus(1).build());
    }

    private void seedStandby(String userid, String departmentJson, Long poolDept, int sortOrder) {
        seedEmployee(userid, departmentJson);
        poolRepo.save(GlobalAgentPool.builder()
            .agentUserid(userid).departmentId(poolDept).sortOrder(sortOrder)
            .status(GlobalAgentPool.PoolStatus.standby).build());
    }

    private void bind(Long qrId, String userid, QrAgent.AgentRole role, QrAgent.AgentStatus status) {
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qrId).agentUserid(userid).role(role).status(status)
            .dailyMax(150).sortOrder(0).build());
    }

    private void whitelist(Long... deptIds) {
        for (Long deptId : deptIds) {
            whitelistRepo.save(PoolAllowedDepartment.builder()
                .deptId(deptId).deptName("d" + deptId).build());
        }
    }

    private Map<String, Integer> poolSnapshot() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (GlobalAgentPool row : poolRepo.findAll()) {
            snapshot.put(row.getAgentUserid(), row.getSortOrder());
        }
        return snapshot;
    }

    private StrictDeptCleanupPreview.QrPlan planFor(StrictDeptCleanupPreview preview, Long qrCodeId) {
        return preview.codes().stream()
            .filter(plan -> plan.qrCodeId().equals(qrCodeId))
            .findFirst().orElse(null);
    }
}
