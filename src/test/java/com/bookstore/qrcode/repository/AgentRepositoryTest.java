package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("AgentRepository 自定义查询")
class AgentRepositoryTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private TestEntityManager em;

    private Agent receptionist, service, blocked, melted;

    @BeforeEach
    void setUp() {
        receptionist = Agent.builder()
                .userid("agent-001")
                .name("张三")
                .role(Agent.AgentRole.receptionist)
                .overallStatus(Agent.OverallStatus.normal)
                .meltedCount24h(1)
                .build();
        service = Agent.builder()
                .userid("agent-002")
                .name("李四")
                .role(Agent.AgentRole.service)
                .overallStatus(Agent.OverallStatus.normal)
                .meltedCount24h(2)
                .build();
        blocked = Agent.builder()
                .userid("agent-003")
                .name("王五")
                .role(Agent.AgentRole.receptionist)
                .overallStatus(Agent.OverallStatus.blocked)
                .meltedCount24h(3)
                .build();
        melted = Agent.builder()
                .userid("agent-004")
                .name("赵六")
                .role(Agent.AgentRole.dual)
                .overallStatus(Agent.OverallStatus.melted)
                .meltedCount24h(3)
                .build();

        em.persist(receptionist);
        em.persist(service);
        em.persist(blocked);
        em.persist(melted);
        em.flush();
    }

    @Test
    @DisplayName("findByOverallStatus — 按综合状态查询")
    void findByOverallStatus() {
        List<Agent> normals = agentRepository.findByOverallStatus(Agent.OverallStatus.normal);
        assertThat(normals).hasSize(2);

        List<Agent> blockedList = agentRepository.findByOverallStatus(Agent.OverallStatus.blocked);
        assertThat(blockedList).hasSize(1);
        assertThat(blockedList.get(0).getUserid()).isEqualTo("agent-003");
    }

    @Test
    @DisplayName("countByOverallStatus — 按综合状态统计")
    void countByOverallStatus() {
        assertThat(agentRepository.countByOverallStatus(Agent.OverallStatus.normal)).isEqualTo(2);
        assertThat(agentRepository.countByOverallStatus(Agent.OverallStatus.melted)).isEqualTo(1);
        assertThat(agentRepository.countByOverallStatus(Agent.OverallStatus.warning)).isZero();
    }

    @Test
    @DisplayName("findByRole — 按角色查询")
    void findByRole() {
        List<Agent> receptionists = agentRepository.findByRole(Agent.AgentRole.receptionist);
        assertThat(receptionists).hasSize(2);

        List<Agent> services = agentRepository.findByRole(Agent.AgentRole.service);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getUserid()).isEqualTo("agent-002");
    }

    @Test
    @DisplayName("findByIdForUpdate — 悲观写锁查询")
    void findByIdForUpdate() {
        assertThat(agentRepository.findByIdForUpdate("agent-001")).isPresent();
        assertThat(agentRepository.findByIdForUpdate("not-exist")).isEmpty();
    }

    @Test
    @DisplayName("findByNameContaining — 按姓名模糊搜索")
    void findByNameContaining() {
        List<Agent> agents = agentRepository.findByNameContaining("张");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getUserid()).isEqualTo("agent-001");
    }

    @Test
    @DisplayName("batchResetMeltedCount — 批量重置熔断计数")
    void batchResetMeltedCount() {
        int updated = agentRepository.batchResetMeltedCount();
        assertThat(updated).isEqualTo(4); // 全部4个都有 meltedCount24h > 0

        em.clear();
        List<Agent> all = agentRepository.findAll();
        assertThat(all).allMatch(a -> a.getMeltedCount24h() == 0);
    }
}
