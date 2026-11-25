package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrRotateLog;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤人服务「自定义原因 + 强制本县补位」回归（Task 4）。
 *
 * <p>用 mock 仓储与 {@link GlobalAgentPoolService} 聚焦服务本身的编排语义：
 * 补位走哪个取人方法、原因如何落 {@link QrRotateLog}、
 * 以及撤人后活码是否仍保留至少 1 名 active 成员。
 * 严格取人本身的部门判定由 {@code GlobalAgentPoolServiceStrictTest} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部门准入撤人：自定义原因与强制本县补位")
class DepartmentWhitelistRemovalServiceStrictTest {

    private static final Long QR_ID = 9001L;
    private static final Long LOCAL_DEPT = 66L;
    private static final String DEFAULT_REASON = "部门准入白名单移出";

    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private QrAgentRepository qrAgentRepo;
    @Mock private QrRotateLogRepository rotateLogRepo;
    @Mock private GlobalAgentPoolService poolService;

    @InjectMocks private DepartmentWhitelistRemovalService service;

    // ── 用例 1：强制严格补位 ──

    @Test
    @DisplayName("强制严格补位：全局开关关闭也只补本县，补到本县人")
    void strictBackfillUsesStrictStandbyOnly() {
        QrAgent doomed = receptionist("receptionistA", 1);
        givenQr(List.of(doomed));
        when(poolService.takeStandbyStrict(anySet(), eq(LOCAL_DEPT)))
            .thenReturn(standby("localStandby", 120));

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "严格同部门移出", true);

        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isEqualTo(1);
        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.skipReason()).isNull();

        // 必须走严格取人，绝不能降级到非严格 takeStandby
        verify(poolService).takeStandbyStrict(anySet(), eq(LOCAL_DEPT));
        verify(poolService, never()).takeStandby(anySet());
        verify(poolService, never()).takeStandby(anySet(), any());

        ArgumentCaptor<QrAgent> saved = ArgumentCaptor.forClass(QrAgent.class);
        verify(qrAgentRepo).save(saved.capture());
        assertThat(saved.getValue().getAgentUserid()).isEqualTo("localStandby");
        assertThat(saved.getValue().getRole()).isEqualTo(QrAgent.AgentRole.receptionist);
        assertThat(saved.getValue().getStatus()).isEqualTo(QrAgent.AgentStatus.active);
        assertThat(saved.getValue().getDailyMax()).isEqualTo(120);

        assertThat(removedAgents()).extracting(QrAgent::getAgentUserid)
            .containsExactly("receptionistA");
    }

    @Test
    @DisplayName("强制严格补位：本县无人则整体跳过，原活码不变")
    void strictBackfillSkipsWhenNoLocalStandby() {
        QrAgent doomed = receptionist("receptionistA", 1);
        givenQr(List.of(doomed));
        when(poolService.takeStandbyStrict(anySet(), eq(LOCAL_DEPT))).thenReturn(null);

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "严格同部门移出", true);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.removed()).isZero();
        assertThat(outcome.backfilled()).isZero();
        assertThat(outcome.skipReason()).isEqualTo("本县无备选，无法补位");

        verify(poolService).takeStandbyStrict(anySet(), eq(LOCAL_DEPT));
        verify(poolService, never()).takeStandby(anySet());
        verify(poolService, never()).takeStandby(anySet(), any());
        // 跳过时不得落任何写库动作，原成员状态保持 active
        verify(qrAgentRepo, never()).saveAll(any());
        verify(qrAgentRepo, never()).save(any());
        verify(rotateLogRepo, never()).saveAll(any());
        assertThat(doomed.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
    }

    @Test
    @DisplayName("非严格补位：池枯竭仍用原跳过文案")
    void nonStrictBackfillKeepsOriginalSkipReason() {
        QrAgent doomed = receptionist("receptionistA", 1);
        givenQr(List.of(doomed));
        when(poolService.takeStandby(anySet(), eq(LOCAL_DEPT))).thenReturn(null);

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "部门准入白名单移出", false);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("全局池枯竭，无法补位");
        verify(poolService, never()).takeStandbyStrict(anySet(), any());
    }

    // ── 用例 2：原因写入 QrRotateLog ──

    @Test
    @DisplayName("撤人原因写入 QrRotateLog")
    void removalReasonIsPersistedToRotateLog() {
        QrAgent doomed = receptionist("receptionistA", 1);
        QrAgent kept = receptionist("receptionistB", 2);
        givenQr(List.of(doomed, kept));

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "严格同部门移出", true);

        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isZero();
        assertThat(outcome.skipped()).isFalse();
        // 剩余 1 名 active，无需补位
        verify(poolService, never()).takeStandby(anySet(), any());
        verify(poolService, never()).takeStandbyStrict(anySet(), any());

        List<QrRotateLog> logs = rotateLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getQrCodeId()).isEqualTo(QR_ID);
        assertThat(logs.get(0).getFromUserid()).isEqualTo("receptionistA");
        assertThat(logs.get(0).getReason()).isEqualTo("严格同部门移出");
    }

    @Test
    @DisplayName("原因为空白时回退默认文案")
    void blankReasonFallsBackToDefault() {
        QrAgent doomed = receptionist("receptionistA", 1);
        QrAgent kept = receptionist("receptionistB", 2);
        givenQr(List.of(doomed, kept));

        service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "   ", true);

        assertThat(rotateLogs()).singleElement()
            .extracting(QrRotateLog::getReason).isEqualTo(DEFAULT_REASON);
    }

    @Test
    @DisplayName("原因为 null 时回退默认文案")
    void nullReasonFallsBackToDefault() {
        QrAgent doomed = receptionist("receptionistA", 1);
        QrAgent kept = receptionist("receptionistB", 2);
        givenQr(List.of(doomed, kept));

        service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), null, true);

        assertThat(rotateLogs()).singleElement()
            .extracting(QrRotateLog::getReason).isEqualTo(DEFAULT_REASON);
    }

    // ── 用例 3：唯一外县服务老师时撤接待员不清空活码 ──

    @Test
    @DisplayName("唯一外县服务老师时撤接待员不清空活码")
    void removingReceptionistKeepsQrFromBeingEmptiedWhenServiceTeacherRemains() {
        QrAgent serviceTeacher = serviceTeacher("serviceA", 1);
        QrAgent doomed = receptionist("receptionistA", 2);
        givenQr(List.of(serviceTeacher, doomed));

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"), "严格同部门移出", true);

        assertThat(outcome.skipped()).as("服务老师仍在 active，不应跳过").isFalse();
        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isZero();
        // 服务老师不是接待员，即使本县无人也不该触发补位
        verify(poolService, never()).takeStandbyStrict(anySet(), any());
        verify(poolService, never()).takeStandby(anySet(), any());

        assertThat(serviceTeacher.getStatus()).isEqualTo(QrAgent.AgentStatus.active);
        assertThat(removedAgents()).extracting(QrAgent::getAgentUserid)
            .containsExactly("receptionistA");
    }

    // ── 旧签名回归 ──

    @Test
    @DisplayName("旧 2 参签名委托非严格全局补位并使用默认原因")
    void legacyTwoArgSignatureDelegatesToNonStrictBackfill() {
        QrAgent doomed = receptionist("receptionistA", 1);
        givenQr(List.of(doomed));
        when(poolService.takeStandby(anySet(), eq(LOCAL_DEPT)))
            .thenReturn(standby("globalStandby", 150));

        DepartmentWhitelistRemovalService.QrRemovalOutcome outcome =
            service.removeReceptionistsFromQr(QR_ID, Set.of("receptionistA"));

        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isEqualTo(1);
        assertThat(outcome.skipped()).isFalse();

        verify(poolService).takeStandby(anySet(), eq(LOCAL_DEPT));
        verify(poolService, never()).takeStandbyStrict(anySet(), any());
        assertThat(rotateLogs()).singleElement()
            .extracting(QrRotateLog::getReason).isEqualTo(DEFAULT_REASON);
    }

    // ── helpers ──

    private void givenQr(List<QrAgent> agents) {
        QrCode qr = QrCode.builder()
            .id(QR_ID).schoolName("测试学校").schoolId("test-school")
            .regionCity("测试市").regionDistrict("测试县")
            .departmentId(LOCAL_DEPT)
            .build();
        when(qrCodeRepo.findById(QR_ID)).thenReturn(Optional.of(qr));
        when(qrAgentRepo.findByQrCodeId(QR_ID)).thenReturn(agents);
    }

    private static QrAgent receptionist(String userid, int sortOrder) {
        return QrAgent.builder()
            .qrCodeId(QR_ID).agentUserid(userid)
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.active)
            .sortOrder(sortOrder)
            .build();
    }

    private static QrAgent serviceTeacher(String userid, int sortOrder) {
        return QrAgent.builder()
            .qrCodeId(QR_ID).agentUserid(userid)
            .role(QrAgent.AgentRole.service)
            .status(QrAgent.AgentStatus.active)
            .sortOrder(sortOrder)
            .build();
    }

    private static GlobalAgentPool standby(String userid, int dailyMax) {
        return GlobalAgentPool.builder()
            .agentUserid(userid).dailyMax(dailyMax)
            .status(GlobalAgentPool.PoolStatus.standby)
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<QrAgent> removedAgents() {
        ArgumentCaptor<List<QrAgent>> captor = ArgumentCaptor.forClass(List.class);
        verify(qrAgentRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<QrRotateLog> rotateLogs() {
        ArgumentCaptor<List<QrRotateLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(rotateLogRepo).saveAll(captor.capture());
        return captor.getValue();
    }
}
