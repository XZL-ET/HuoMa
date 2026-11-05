package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务边界回归：生产 HTTP 请求不会在测试事务中运行，applyToAll 必须自行开启事务，
 * 否则原生 CREATE TABLE 备份会抛 TransactionRequiredException（2026-11-05 线上 500）。
 */
@DataJpaTest
@Import(ReceptionistDailyMaxService.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ReceptionistDailyMaxService 无外层事务")
class ReceptionistDailyMaxServiceTxTest {

    @Autowired private ReceptionistDailyMaxService service;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;

    @Test
    @DisplayName("applyToAll 无外层事务时也能建备份表并同时更新两处日限")
    void applyToAllWorksWithoutOuterTransaction() {
        QrCode qr = qrCodeRepo.save(QrCode.builder()
                .schoolName("测试学校").schoolId("T-TX-REC")
                .regionCity("兰州").regionDistrict("城关区").build());
        agentRepo.save(Agent.builder().userid("recTx").name("接待员recTx")
                .role(Agent.AgentRole.receptionist).build());
        agentRepo.save(Agent.builder().userid("svcTx2").name("老师svcTx2")
                .role(Agent.AgentRole.service).build());
        QrAgent receptionist = qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("recTx")
                .role(QrAgent.AgentRole.receptionist).status(QrAgent.AgentStatus.active)
                .dailyMax(100).build());
        QrAgent serviceTeacher = qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("svcTx2")
                .role(QrAgent.AgentRole.service).status(QrAgent.AgentStatus.active)
                .dailyMax(60).build());
        GlobalAgentPool receptionistPool = poolRepo.save(GlobalAgentPool.builder()
                .agentUserid("recTx").dailyMax(100).sortOrder(1)
                .status(GlobalAgentPool.PoolStatus.standby).build());
        GlobalAgentPool servicePool = poolRepo.save(GlobalAgentPool.builder()
                .agentUserid("svcTx2").dailyMax(60).sortOrder(2)
                .status(GlobalAgentPool.PoolStatus.standby).build());

        int updated = service.applyToAll(200);

        assertThat(updated).isEqualTo(1);
        assertThat(qrAgentRepo.findById(receptionist.getId()).orElseThrow().getDailyMax()).isEqualTo(200);
        assertThat(poolRepo.findById(receptionistPool.getId()).orElseThrow().getDailyMax()).isEqualTo(200);
        // 服务老师不受接待员日限影响
        assertThat(qrAgentRepo.findById(serviceTeacher.getId()).orElseThrow().getDailyMax()).isEqualTo(60);
        assertThat(poolRepo.findById(servicePool.getId()).orElseThrow().getDailyMax()).isEqualTo(60);
    }
}
