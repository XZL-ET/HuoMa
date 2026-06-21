package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("GlobalAgentPoolRepository 自定义查询")
class GlobalAgentPoolRepositoryTest {

    @Autowired
    private GlobalAgentPoolRepository poolRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private TestEntityManager em;

    private GlobalAgentPool p1, p2, p3;

    @BeforeEach
    void setUp() {
        // 需要先创建 Agent（外键约束）
        em.persist(Agent.builder().userid("user-001").name("员工1").build());
        em.persist(Agent.builder().userid("user-002").name("员工2").build());
        em.persist(Agent.builder().userid("user-003").name("员工3").build());
        em.flush();

        p1 = GlobalAgentPool.builder()
                .agentUserid("user-001")
                .dailyMax(100)
                .dailyCurrent(50)
                .sortOrder(10)
                .status(GlobalAgentPool.PoolStatus.standby)
                .build();
        p2 = GlobalAgentPool.builder()
                .agentUserid("user-002")
                .dailyMax(100)
                .dailyCurrent(100)
                .sortOrder(5)
                .status(GlobalAgentPool.PoolStatus.full)
                .build();
        p3 = GlobalAgentPool.builder()
                .agentUserid("user-003")
                .dailyMax(100)
                .dailyCurrent(30)
                .sortOrder(20)
                .status(GlobalAgentPool.PoolStatus.standby)
                .build();

        p1 = em.persistFlushFind(p1);
        p2 = em.persistFlushFind(p2);
        p3 = em.persistFlushFind(p3);
    }

    @Test
    @DisplayName("findByAgentUserid — 按 userid 查找")
    void findByAgentUserid() {
        assertThat(poolRepository.findByAgentUserid("user-001")).isPresent();
        assertThat(poolRepository.findByAgentUserid("not-exist")).isEmpty();
    }

    @Test
    @DisplayName("findByStatusOrderBySortOrder — 按状态查询并按优先级排序")
    void findByStatusOrderBySortOrder() {
        List<GlobalAgentPool> standbys = poolRepository
                .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
        assertThat(standbys).hasSize(2);
        // sortOrder 升序: user-002(5)..., 实际是 standby 的 p1(10), p3(20)
        assertThat(standbys).extracting("sortOrder").containsExactly(10, 20);

        List<GlobalAgentPool> fulls = poolRepository
                .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.full);
        assertThat(fulls).hasSize(1);
        assertThat(fulls.get(0).getAgentUserid()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("findStandbysForUpdate — 悲观写锁查询待命员工")
    void findStandbysForUpdate() {
        List<GlobalAgentPool> standbys = poolRepository
                .findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby);
        assertThat(standbys).hasSize(2);
        assertThat(standbys).extracting("agentUserid")
                .containsExactly("user-001", "user-003"); // sortOrder ASC
    }

    @Test
    @DisplayName("countByStatus — 统计各状态数量")
    void countByStatus() {
        assertThat(poolRepository.countByStatus(GlobalAgentPool.PoolStatus.standby)).isEqualTo(2);
        assertThat(poolRepository.countByStatus(GlobalAgentPool.PoolStatus.full)).isEqualTo(1);
        assertThat(poolRepository.countByStatus(GlobalAgentPool.PoolStatus.blocked)).isZero();
    }

    @Test
    @DisplayName("findFirstByOrderBySortOrderDesc — 取最大 sortOrder")
    void findFirstByOrderBySortOrderDesc() {
        Optional<GlobalAgentPool> max = poolRepository.findFirstByOrderBySortOrderDesc();
        assertThat(max).isPresent();
        assertThat(max.get().getSortOrder()).isEqualTo(20);
        assertThat(max.get().getAgentUserid()).isEqualTo("user-003");
    }

    @Test
    @DisplayName("findAllAgentUserids — 查询所有池中 employee userid")
    void findAllAgentUserids() {
        List<String> userids = poolRepository.findAllAgentUserids();
        assertThat(userids).containsExactlyInAnyOrder("user-001", "user-002", "user-003");
    }

    @Test
    @DisplayName("findByAgentUseridContaining — userid 模糊匹配")
    void findByAgentUseridContaining() {
        List<GlobalAgentPool> result = poolRepository.findByAgentUseridContaining("user-00");
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("分页查询 — 按 userid 模糊+状态+分页")
    void paginatedQuery() {
        Page<GlobalAgentPool> page = poolRepository
                .findByAgentUseridContainingAndStatusOrderBySortOrder(
                        "user", GlobalAgentPool.PoolStatus.standby,
                        PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting("sortOrder").containsExactly(10, 20);
    }

    @Test
    @DisplayName("batchUpdateStatus — 批量更新状态和 sortOrder")
    void batchUpdateStatus() {
        LocalDateTime now = LocalDateTime.now();
        int updated = poolRepository.batchUpdateStatus(
                GlobalAgentPool.PoolStatus.standby,
                GlobalAgentPool.PoolStatus.full,
                100,
                now);
        assertThat(updated).isEqualTo(2);

        em.clear();
        // 现在全部都是 full
        assertThat(poolRepository.countByStatus(GlobalAgentPool.PoolStatus.standby)).isZero();
        assertThat(poolRepository.countByStatus(GlobalAgentPool.PoolStatus.full)).isEqualTo(3);
    }

    @Test
    @DisplayName("batchResetDailyCurrent — 批量重置日计数")
    void batchResetDailyCurrent() {
        int updated = poolRepository.batchResetDailyCurrent(GlobalAgentPool.PoolStatus.standby);
        assertThat(updated).isEqualTo(2);

        em.clear();
        List<GlobalAgentPool> standbys = poolRepository
                .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
        assertThat(standbys).allMatch(p -> p.getDailyCurrent() == 0);
    }
}
