package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
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
@Import(ServiceTeacherDailyMaxService.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ServiceTeacherDailyMaxService 无外层事务")
class ServiceTeacherDailyMaxServiceTxTest {

    @Autowired private ServiceTeacherDailyMaxService service;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private QrCodeRepository qrCodeRepo;

    @Test
    @DisplayName("applyToAll 无外层事务时也能建备份表并更新")
    void applyToAllWorksWithoutOuterTransaction() {
        QrCode qr = qrCodeRepo.save(QrCode.builder()
                .schoolName("测试学校").schoolId("T-TX")
                .regionCity("兰州").regionDistrict("城关区").build());
        agentRepo.save(Agent.builder().userid("svcTx").name("老师svcTx").build());
        QrAgent qa = qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qr.getId()).agentUserid("svcTx")
                .role(QrAgent.AgentRole.service).status(QrAgent.AgentStatus.active)
                .dailyMax(150).serviceDailyMax(150)
                .build());

        int updated = service.applyToAll(300);

        assertThat(updated).isEqualTo(1);
        assertThat(qrAgentRepo.findById(qa.getId()).orElseThrow().getDailyMax()).isEqualTo(300);
        assertThat(qrAgentRepo.findById(qa.getId()).orElseThrow().getServiceDailyMax()).isEqualTo(300);
    }
}
