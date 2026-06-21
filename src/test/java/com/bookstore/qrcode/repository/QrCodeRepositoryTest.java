package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("QrCodeRepository 自定义查询")
class QrCodeRepositoryTest {

    @Autowired
    private QrCodeRepository qrCodeRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private QrAgentRepository qrAgentRepository;

    @Autowired
    private TestEntityManager em;

    private QrCode qr1, qr2, qr3;

    @BeforeEach
    void setUp() {
        qr1 = QrCode.builder()
                .schoolName("北京第一中学")
                .schoolId("BJ-001")
                .regionCity("北京")
                .regionDistrict("海淀区")
                .qrConfigId("config-001")
                .status(QrCode.QrCodeStatus.active)
                .rotateMode(QrCode.RotateMode.auto)
                .createMode(QrCode.CreateMode.manual)
                .build();
        qr2 = QrCode.builder()
                .schoolName("上海第二中学")
                .schoolId("SH-002")
                .regionCity("上海")
                .regionDistrict("浦东新区")
                .qrConfigId("config-002")
                .status(QrCode.QrCodeStatus.active)
                .rotateMode(QrCode.RotateMode.auto)
                .createMode(QrCode.CreateMode.batch_import)
                .build();
        qr3 = QrCode.builder()
                .schoolName("北京第三小学")
                .schoolId("BJ-003")
                .regionCity("北京")
                .regionDistrict("朝阳区")
                .qrConfigId("config-003")
                .status(QrCode.QrCodeStatus.paused)
                .rotateMode(QrCode.RotateMode.manual)
                .createMode(QrCode.CreateMode.manual)
                .build();

        qr1 = em.persistFlushFind(qr1);
        qr2 = em.persistFlushFind(qr2);
        qr3 = em.persistFlushFind(qr3);
    }

    @Test
    @DisplayName("search — 全参数组合搜索")
    void searchWithAllParams() {
        Page<QrCode> page = qrCodeRepository.search(
                "北京", null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting("schoolName")
                .contains("北京第一中学", "北京第三小学");
    }

    @Test
    @DisplayName("search — 城市精确筛选")
    void searchByCity() {
        Page<QrCode> page = qrCodeRepository.search(
                null, "上海", null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getSchoolName()).isEqualTo("上海第二中学");
    }

    @Test
    @DisplayName("search — 状态筛选")
    void searchByStatus() {
        Page<QrCode> page = qrCodeRepository.search(
                null, null, null, QrCode.QrCodeStatus.paused, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getSchoolId()).isEqualTo("BJ-003");
    }

    @Test
    @DisplayName("search — 区县+关键词组合")
    void searchByDistrictAndKeyword() {
        Page<QrCode> page = qrCodeRepository.search(
                "小学", null, "朝阳区", null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getSchoolName()).isEqualTo("北京第三小学");
    }

    @Test
    @DisplayName("countByStatus — 统计各状态数量")
    void countByStatus() {
        assertThat(qrCodeRepository.countByStatus(QrCode.QrCodeStatus.active)).isEqualTo(2);
        assertThat(qrCodeRepository.countByStatus(QrCode.QrCodeStatus.paused)).isEqualTo(1);
        assertThat(qrCodeRepository.countByStatus(QrCode.QrCodeStatus.full)).isZero();
    }

    @Test
    @DisplayName("findBySchoolId — 按学校ID查找")
    void findBySchoolId() {
        assertThat(qrCodeRepository.findBySchoolId("BJ-001")).isPresent();
        assertThat(qrCodeRepository.findBySchoolId("NOT-EXIST")).isEmpty();
    }

    @Test
    @DisplayName("existsBySchoolId — 检查学校ID是否存在")
    void existsBySchoolId() {
        assertThat(qrCodeRepository.existsBySchoolId("BJ-001")).isTrue();
        assertThat(qrCodeRepository.existsBySchoolId("NOT-EXIST")).isFalse();
    }

    @Test
    @DisplayName("findDistinctRegionCity — 去重城市列表")
    void findDistinctRegionCity() {
        List<String> cities = qrCodeRepository.findDistinctRegionCity();
        assertThat(cities).containsExactly("上海", "北京"); // ORDER BY 字母序
    }

    @Test
    @DisplayName("findDistinctRegionDistrict — 去重区县列表")
    void findDistinctRegionDistrict() {
        List<String> districts = qrCodeRepository.findDistinctRegionDistrict();
        assertThat(districts).contains("海淀区", "浦东新区", "朝阳区");
    }

    @Test
    @DisplayName("findOrphanCandidates — 孤儿活码扫描")
    void findOrphanCandidates() {
        List<QrCode> orphans = qrCodeRepository.findOrphanCandidates();
        // qr3: paused + has configId → 孤儿候选
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).getSchoolId()).isEqualTo("BJ-003");

        // qr1/qr2: active → 不是孤儿
    }

    @Test
    @DisplayName("findFirstServiceAgentName — native query 查询服务老师姓名")
    void findFirstServiceAgentName() {
        Agent agent = Agent.builder()
                .userid("teacher-001")
                .name("张老师")
                .role(Agent.AgentRole.service)
                .build();
        em.persist(agent);

        QrAgent qrAgent = QrAgent.builder()
                .qrCodeId(qr1.getId())
                .agentUserid("teacher-001")
                .role(QrAgent.AgentRole.service)
                .status(QrAgent.AgentStatus.active)
                .build();
        em.persistAndFlush(qrAgent);

        String name = qrCodeRepository.findFirstServiceAgentName(qr1.getId());
        assertThat(name).isEqualTo("张老师");
    }

    @Test
    @DisplayName("findFirstServiceAgentName — 无服务老师时返回 null")
    void findFirstServiceAgentNameNoMatch() {
        String name = qrCodeRepository.findFirstServiceAgentName(qr1.getId());
        assertThat(name).isNull();
    }

    @Test
    @DisplayName("findByStatus — 按状态查询活码列表")
    void findByStatus() {
        List<QrCode> actives = qrCodeRepository.findByStatus(QrCode.QrCodeStatus.active);
        assertThat(actives).hasSize(2);
    }
}
