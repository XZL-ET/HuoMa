package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.service.DepartmentWhitelistRemovalService;
import com.bookstore.qrcode.service.GlobalAgentPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部门准入：活码撤人核心。
 *
 * <p>验证白名单撤人时先补位后撤 active 接待员、池枯竭时跳过、
 * 有服务老师兜底时无需补位、full 接待员直接撤，
 * 以及服务老师角色不受影响。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：活码撤接待员")
class DepartmentWhitelistRemovalTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistRemovalService removalService;
    @Autowired private GlobalAgentPoolService poolService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        rotateLogRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        employeeRepo.save(Employee.builder().userid("bad").name("bad")
            .department("[200]").active(true).wechatStatus(1).build());
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("撤人测试").schoolId("SCH-RM-1")
            .regionCity("深圳").regionDistrict("南山区").build());
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
    }

    private QrAgent bind(String uid, QrAgent.AgentRole role, QrAgent.AgentStatus status) {
        return qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid(uid).role(role)
            .dailyMax(150).sortOrder(0).status(status).build());
    }

    @Test
    @DisplayName("最后一名无关接待员：先补位再撤，活码不为空")
    void backfillsThenRemoves() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        // 先建允许部门的员工记录，再入池（白名单启用态下无 Employee 记录会被拦截）
        employeeRepo.save(Employee.builder().userid("good").name("good")
            .department("[100]").active(true).wechatStatus(1).build());
        poolService.ensureInPool("good", 150);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        long active = qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active).size();
        assertThat(active).isEqualTo(1);
        var logs = rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
            qr.getId(), Pageable.unpaged());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFromUserid()).isEqualTo("bad");
        assertThat(logs.get(0).getReason()).isEqualTo("部门准入白名单移出");
    }

    @Test
    @DisplayName("补位排除本码所有绑定（含 removed），唯一候选被排除后跳过")
    void excludesUseridAlreadyBoundAsRemovedOnThisQr() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        // 先 ensureInPool 建出 Agent 记录，再插入 removed 绑定（qr_agent 有 agent 外键）
        employeeRepo.save(Employee.builder().userid("good").name("good")
            .department("[100]").active(true).wechatStatus(1).build());
        poolService.ensureInPool("good", 150);
        bind("good", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.removed);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).contains("池");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "good"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        long goodRows = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            .filter(qa -> "good".equals(qa.getAgentUserid()))
            .count();
        assertThat(goodRows).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("重复执行幂等：第二次无撤人、无补位、不跳过")
    void isIdempotentOnSecondRun() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        employeeRepo.save(Employee.builder().userid("good").name("good")
            .department("[100]").active(true).wechatStatus(1).build());
        poolService.ensureInPool("good", 150);

        var first = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));
        assertThat(first.skipped()).isFalse();
        assertThat(first.removed()).isEqualTo(1);
        assertThat(first.backfilled()).isEqualTo(1);

        var second = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(second.removed()).isZero();
        assertThat(second.backfilled()).isZero();
        assertThat(second.skipped()).isFalse();
        assertThat(second.skipReason()).isNull();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .hasSize(1);
    }

    @Test
    @DisplayName("池枯竭：保留原人并标记 skipped")
    void keepsOriginalWhenPoolExhausted() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).contains("池");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("有服务老师兜底时无需补位，只撤接待员")
    void removesWithoutBackfillWhenServicePresent() {
        agentRepo.save(Agent.builder().userid("svc").name("svc")
            .role(Agent.AgentRole.service).build());
        bind("svc", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.backfilled()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
    }

    @Test
    @DisplayName("full 接待员直接标 removed，不需要补位")
    void removesFullReceptionists() {
        QrAgent full = bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.full);

        int removed = removalService.removeFullReceptionists(List.of(full.getId()));

        assertThat(removed).isEqualTo(1);
        assertThat(qrAgentRepo.findById(full.getId()).orElseThrow().getStatus())
            .isEqualTo(QrAgent.AgentStatus.removed);
    }

    @Test
    @DisplayName("服务老师即使传入 disallowedUserids 也不动")
    void neverRemovesServiceRole() {
        bind("bad", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.removed()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }
}
