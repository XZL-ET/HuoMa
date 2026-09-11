package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("QrAgentRepository.applyDailyMaxToServiceTeachers")
class QrAgentRepositoryApplyTest {

    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private TestEntityManager em;

    @Test
    @DisplayName("只更新 service/dual 且未移除的老师，dailyMax 与 serviceDailyMax 都设为目标值")
    void updatesOnlyNonRemovedServiceTeachers() {
        QrCode qr = qrCodeRepo.save(QrCode.builder()
                .schoolName("测试学校").schoolId("T-001")
                .regionCity("兰州").regionDistrict("城关区").build());

        agentRepo.save(agent("svcActive"));
        agentRepo.save(agent("svcRemoved"));
        agentRepo.save(agent("svcFull"));
        agentRepo.save(agent("receptionist"));

        QrAgent a1 = qrAgentRepo.save(qa(qr.getId(), "svcActive", QrAgent.AgentRole.service, QrAgent.AgentStatus.active));
        QrAgent a2 = qrAgentRepo.save(qa(qr.getId(), "svcRemoved", QrAgent.AgentRole.service, QrAgent.AgentStatus.removed));
        QrAgent a3 = qrAgentRepo.save(qa(qr.getId(), "svcFull", QrAgent.AgentRole.service, QrAgent.AgentStatus.full));
        QrAgent a4 = qrAgentRepo.save(qa(qr.getId(), "receptionist", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active));

        int updated = qrAgentRepo.applyDailyMaxToServiceTeachers(300);

        assertThat(updated).isEqualTo(2);

        em.clear();

        assertThat(qrAgentRepo.findById(a1.getId()).orElseThrow().getDailyMax()).isEqualTo(300);
        assertThat(qrAgentRepo.findById(a1.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(300);

        assertThat(qrAgentRepo.findById(a2.getId()).orElseThrow().getDailyMax()).isEqualTo(150);
        assertThat(qrAgentRepo.findById(a2.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(150);

        assertThat(qrAgentRepo.findById(a3.getId()).orElseThrow().getDailyMax()).isEqualTo(300);
        assertThat(qrAgentRepo.findById(a3.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(300);

        assertThat(qrAgentRepo.findById(a4.getId()).orElseThrow().getDailyMax()).isEqualTo(150);
        assertThat(qrAgentRepo.findById(a4.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(150);
    }

    @Test
    @DisplayName("dual 角色也纳入统一日限管理，dailyMax 与 serviceDailyMax 都设为目标值")
    void updatesDualRoleTeachers() {
        QrCode qr = qrCodeRepo.save(QrCode.builder()
                .schoolName("测试学校").schoolId("T-DUAL")
                .regionCity("兰州").regionDistrict("城关区").build());

        agentRepo.save(agent("dualActive"));
        agentRepo.save(agent("dualRemoved"));

        QrAgent dualActive = qrAgentRepo.save(
                qa(qr.getId(), "dualActive", QrAgent.AgentRole.dual, QrAgent.AgentStatus.active));
        QrAgent dualRemoved = qrAgentRepo.save(
                qa(qr.getId(), "dualRemoved", QrAgent.AgentRole.dual, QrAgent.AgentStatus.removed));

        int updated = qrAgentRepo.applyDailyMaxToServiceTeachers(300);

        assertThat(updated).isEqualTo(1);

        em.clear();

        assertThat(qrAgentRepo.findById(dualActive.getId()).orElseThrow().getDailyMax()).isEqualTo(300);
        assertThat(qrAgentRepo.findById(dualActive.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(300);

        assertThat(qrAgentRepo.findById(dualRemoved.getId()).orElseThrow().getDailyMax()).isEqualTo(150);
        assertThat(qrAgentRepo.findById(dualRemoved.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(150);
    }

    private Agent agent(String userid) {
        return Agent.builder().userid(userid).name("老师" + userid).build();
    }

    private QrAgent qa(Long qrId, String userid, QrAgent.AgentRole role, QrAgent.AgentStatus status) {
        return QrAgent.builder()
                .qrCodeId(qrId).agentUserid(userid)
                .role(role).status(status)
                .dailyMax(150).serviceDailyMax(150)
                .build();
    }
}
