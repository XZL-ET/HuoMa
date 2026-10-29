package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.AgentAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentAlertRepository} 告警合并相关查询的测试。
 *
 * <p>覆盖按合并键（类型 + 员工 + 活码，NULL 视为相同）查找未处理告警，
 * 以及按 ID 批量关闭告警两个行为。</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("AgentAlertRepository 告警合并查询")
class AgentAlertRepositoryTest {

    @Autowired
    AgentAlertRepository repo;

    @Autowired
    TestEntityManager em;

    private AgentAlert alert(String type, String agent, Long qr) {
        return em.persistFlushFind(AgentAlert.builder()
            .alertType(type).agentUserid(agent).qrCodeId(qr)
            .severity(AgentAlert.AlertSeverity.medium)
            .detail("\"x\"").status(AgentAlert.AlertStatus.open).build());
    }

    @Test
    @DisplayName("findOpenByKey — null 与 null 匹配，不匹配非 null")
    void shouldMatchNullAsNull() {
        AgentAlert global = alert("inheritance_no_service", null, null);
        alert("inheritance_no_service", "u1", null);

        var found = repo.findOpenByKey("inheritance_no_service", null, null, AgentAlert.AlertStatus.open);

        assertThat(found).extracting(AgentAlert::getId).containsExactly(global.getId());
    }

    @Test
    @DisplayName("closeByIds — 只关闭给定 id，并写 resolvedBy/At")
    void shouldCloseOnlyGivenIds() {
        AgentAlert a = alert("traffic_spike", null, 7L);
        AgentAlert b = alert("traffic_spike", null, 8L);
        var now = LocalDateTime.now();

        int closed = repo.closeByIds(List.of(a.getId()), AgentAlert.AlertStatus.auto_resolved, now, "auto:test");
        em.flush(); em.clear();

        assertThat(closed).isEqualTo(1);
        assertThat(repo.findById(a.getId()).orElseThrow().getStatus())
            .isEqualTo(AgentAlert.AlertStatus.auto_resolved);
        assertThat(repo.findById(b.getId()).orElseThrow().getStatus())
            .isEqualTo(AgentAlert.AlertStatus.open);
    }
}
