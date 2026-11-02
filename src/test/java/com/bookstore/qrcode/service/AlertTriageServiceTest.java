package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.AgentAlert.AlertSeverity;
import com.bookstore.qrcode.service.AlertTriageService.TriageCard;
import com.bookstore.qrcode.service.AlertTriageService.TriageSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.bookstore.qrcode.entity.AgentAlert.AlertSeverity.high;
import static com.bookstore.qrcode.entity.AgentAlert.AlertSeverity.medium;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlertTriageService 聚合卡片")
class AlertTriageServiceTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 2, 12, 0);
    private final AlertTriageService service = new AlertTriageService();

    private AgentAlert alert(String type, String agent, Long qr, AlertSeverity severity,
                             LocalDateTime lastSeenAt) {
        return alert(type, agent, qr, severity, lastSeenAt, null);
    }

    private AgentAlert alert(String type, String agent, Long qr, AlertSeverity severity,
                             LocalDateTime lastSeenAt, LocalDateTime createdAt) {
        return AgentAlert.builder()
            .alertType(type).agentUserid(agent).qrCodeId(qr)
            .severity(severity).lastSeenAt(lastSeenAt).createdAt(createdAt)
            .status(AgentAlert.AlertStatus.open)
            .build();
    }

    @Test
    @DisplayName("按类型聚合，informational 与 actionable 分开")
    void shouldSplitByInformational() {
        List<AgentAlert> open = List.of(
            alert("qr_agent_anomaly", null, null, high, now.minusHours(1)),
            alert("traffic_spike", null, 1L, medium, now.minusHours(2)),
            alert("traffic_spike", null, 2L, medium, now.minusHours(3)));

        TriageSummary s = service.buildSummary(open);

        assertThat(s.actionable()).extracting(TriageCard::alertType).containsExactly("qr_agent_anomaly");
        assertThat(s.informational()).extracting(TriageCard::alertType).containsExactly("traffic_spike");
        assertThat(s.actionableCount()).isEqualTo(1);
        assertThat(s.informationalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("同类卡片：条数、涉及活码数、最近时间、深链正确")
    void shouldAggregateCounts() {
        TriageCard card = service.buildSummary(List.of(
            alert("traffic_spike", null, 1L, medium, now.minusHours(5)),
            alert("traffic_spike", null, 1L, medium, now.minusHours(1)),
            alert("traffic_spike", null, 2L, high, now.minusHours(2))))
            .informational().get(0);

        assertThat(card.count()).isEqualTo(3);
        assertThat(card.qrCount()).isEqualTo(2);
        assertThat(card.agentCount()).isZero();
        assertThat(card.severity()).isEqualTo(high);
        assertThat(card.lastSeenAt()).isEqualTo(now.minusHours(1));
        // 设计 4.6：traffic_spike 深链到具体活码（取组内第一条非空 qrCodeId）
        assertThat(card.linkUrl()).isEqualTo("/qrcodes/1");
        assertThat(card.linkLabel()).isEqualTo("去活码");
    }

    @Test
    @DisplayName("涉及员工数按去重非空值统计")
    void shouldCountDistinctAgents() {
        TriageCard card = service.buildSummary(List.of(
            alert("qr_agent_anomaly", "u1", null, high, now),
            alert("qr_agent_anomaly", "u1", null, high, now),
            alert("qr_agent_anomaly", "u2", null, high, now),
            alert("qr_agent_anomaly", null, null, high, now)))
            .actionable().get(0);

        assertThat(card.count()).isEqualTo(4);
        assertThat(card.agentCount()).isEqualTo(2);
        assertThat(card.qrCount()).isZero();
    }

    @Test
    @DisplayName("排序：级别高 → 条数多 → 最近")
    void shouldSortCards() {
        TriageSummary s = service.buildSummary(List.of(
            alert("transfer_fail", "u5", null, medium, now.minusMinutes(30)),
            alert("qr_agent_anomaly", "u2", 1L, medium, now.minusHours(4)),
            alert("melt", "u1", null, high, now.minusHours(5)),
            alert("qr_agent_anomaly", "u3", 1L, medium, now.minusHours(3)),
            alert("qr_agent_anomaly", "u4", 1L, medium, now.minusHours(2))));

        assertThat(s.actionable()).extracting(TriageCard::alertType)
            .containsExactly("melt", "qr_agent_anomaly", "transfer_fail");
    }

    @Test
    @DisplayName("同级别同条数时按最近时间降序，空时间排最后")
    void shouldSortByLastSeenWithNullsLast() {
        TriageSummary s = service.buildSummary(List.of(
            alert("add_fail", "u1", null, medium, null),
            alert("melt", "u2", null, medium, now.minusHours(1)),
            alert("transfer_fail", "u3", null, medium, now.minusHours(2))));

        assertThat(s.actionable()).extracting(TriageCard::alertType)
            .containsExactly("melt", "transfer_fail", "add_fail");
        assertThat(s.actionable().get(2).lastSeenAt()).isNull();
    }

    @Test
    @DisplayName("lastSeenAt 为空时回退 createdAt；两者皆空不抛异常")
    void shouldFallbackToCreatedAtAndTolerateBothNull() {
        TriageSummary s = service.buildSummary(List.of(
            alert("future_type", null, null, medium, null, now.minusHours(3)),
            alert("future_type2", null, null, medium, null, null)));

        assertThat(s.actionable()).extracting(TriageCard::alertType)
            .containsExactly("future_type", "future_type2");
        assertThat(s.actionable().get(0).lastSeenAt()).isEqualTo(now.minusHours(3));
        assertThat(s.actionable().get(1).lastSeenAt()).isNull();
    }

    @Test
    @DisplayName("未知类型仍出现在卡片里，使用原始类型名，无深链")
    void shouldIncludeUnknownTypes() {
        TriageCard card = service.buildSummary(List.of(alert("future_type", null, null, medium, now)))
            .actionable().get(0);

        assertThat(card.label()).isEqualTo("future_type");
        assertThat(card.suggestion()).isEmpty();
        assertThat(card.linkUrl()).isEmpty();
        assertThat(card.linkLabel()).isEmpty();
    }

    @Test
    @DisplayName("深链取组内第一条非空 qrCodeId；全空时回退类型级链接")
    void shouldUseFirstNonNullQrForLink() {
        TriageCard withQr = service.buildSummary(List.of(
            alert("empty_backup", null, null, high, now),
            alert("empty_backup", null, 7L, high, now),
            alert("empty_backup", null, 9L, high, now)))
            .actionable().get(0);
        assertThat(withQr.qrCount()).isEqualTo(2);
        assertThat(withQr.linkUrl()).isEqualTo("/qrcodes/7");

        TriageCard withoutQr = service.buildSummary(List.of(
            alert("empty_backup", null, null, high, now)))
            .actionable().get(0);
        assertThat(withoutQr.linkUrl()).isEqualTo("/qrcodes");
    }

    @Test
    @DisplayName("空列表返回空卡片与零计数")
    void shouldHandleEmptyInput() {
        TriageSummary s = service.buildSummary(List.of());

        assertThat(s.actionable()).isEmpty();
        assertThat(s.informational()).isEmpty();
        assertThat(s.actionableCount()).isZero();
        assertThat(s.informationalCount()).isZero();
    }
}
