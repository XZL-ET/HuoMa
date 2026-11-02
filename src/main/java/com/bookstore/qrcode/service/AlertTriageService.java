package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.AgentAlert;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 待处理告警聚合。
 *
 * <p>把已加载的 open 告警行按 {@code alertType} 聚合成卡片，按 {@link AlertTypeMeta}
 * 的 informational 标记分成「需要处理」与「已自动恢复/可归档」两组。本服务不访问数据库，
 * 计数与最近时间均来自传入的行，便于单测与页面渲染复用。</p>
 */
@Service
public class AlertTriageService {

    /** 排序：级别高 → 条数多 → 最近；时间空值排最后。 */
    private static final Comparator<TriageCard> CARD_ORDER =
        Comparator.comparingInt((TriageCard c) -> c.severity().ordinal()).reversed()
            .thenComparing(Comparator.comparingLong(TriageCard::count).reversed())
            .thenComparing(TriageCard::lastSeenAt,
                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()));

    /**
     * 聚合 open 告警为卡片分组。
     *
     * <p>{@code actionableCount} / {@code informationalCount} 为告警行数之和，不是卡片数。</p>
     */
    public TriageSummary buildSummary(List<AgentAlert> openAlerts) {
        Map<String, List<AgentAlert>> byType = new LinkedHashMap<>();
        if (openAlerts != null) {
            for (AgentAlert alert : openAlerts) {
                byType.computeIfAbsent(alert.getAlertType(), key -> new ArrayList<>()).add(alert);
            }
        }

        List<TriageCard> actionable = new ArrayList<>();
        List<TriageCard> informational = new ArrayList<>();
        long actionableCount = 0;
        long informationalCount = 0;
        for (Map.Entry<String, List<AgentAlert>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<AgentAlert> alerts = entry.getValue();
            AlertTypeMeta meta = AlertTypeMeta.of(type);
            TriageCard card = toCard(type, meta, alerts);
            if (meta.informational()) {
                informational.add(card);
                informationalCount += alerts.size();
            } else {
                actionable.add(card);
                actionableCount += alerts.size();
            }
        }

        actionable.sort(CARD_ORDER);
        informational.sort(CARD_ORDER);
        return new TriageSummary(List.copyOf(actionable), List.copyOf(informational),
            actionableCount, informationalCount);
    }

    private TriageCard toCard(String type, AlertTypeMeta meta, List<AgentAlert> alerts) {
        Set<Long> qrIds = new HashSet<>();
        Set<String> agents = new HashSet<>();
        Long linkQrCodeId = null;
        AgentAlert.AlertSeverity severity = null;
        LocalDateTime lastSeenAt = null;

        for (AgentAlert alert : alerts) {
            if (alert.getQrCodeId() != null) {
                qrIds.add(alert.getQrCodeId());
                if (linkQrCodeId == null) {
                    linkQrCodeId = alert.getQrCodeId();
                }
            }
            if (alert.getAgentUserid() != null) {
                agents.add(alert.getAgentUserid());
            }
            AgentAlert.AlertSeverity rowSeverity = alert.getSeverity();
            if (rowSeverity != null
                && (severity == null || rowSeverity.ordinal() > severity.ordinal())) {
                severity = rowSeverity;
            }
            LocalDateTime seen = alert.getLastSeenAt() != null
                ? alert.getLastSeenAt() : alert.getCreatedAt();
            if (seen != null && (lastSeenAt == null || seen.isAfter(lastSeenAt))) {
                lastSeenAt = seen;
            }
        }

        return new TriageCard(type, meta.label(), meta.suggestion(),
            severity != null ? severity : meta.severity(), alerts.size(),
            qrIds.size(), agents.size(), lastSeenAt,
            AlertTypeMeta.linkFor(type, linkQrCodeId), AlertTypeMeta.linkLabelFor(type));
    }

    /**
     * 单类告警的聚合卡片。
     *
     * @param alertType  告警类型标识
     * @param label      中文名
     * @param suggestion 建议动作
     * @param severity   组内最高严重级别
     * @param count      告警行数
     * @param qrCount    涉及的活码数（去重非空）
     * @param agentCount 涉及的员工数（去重非空）
     * @param lastSeenAt 组内最近一次发生时间（COALESCE(lastSeenAt, createdAt) 最大值，可能为空）
     * @param linkUrl    深链地址，空串表示无按钮
     * @param linkLabel  深链按钮文案，空串表示无按钮
     */
    public record TriageCard(String alertType, String label, String suggestion,
                             AgentAlert.AlertSeverity severity, long count, long qrCount,
                             long agentCount, LocalDateTime lastSeenAt,
                             String linkUrl, String linkLabel) {
    }

    /**
     * 告警聚合结果。
     *
     * @param actionable         需要处理的卡片
     * @param informational      已自动恢复/可归档的卡片
     * @param actionableCount    actionable 告警行数之和
     * @param informationalCount informational 告警行数之和
     */
    public record TriageSummary(List<TriageCard> actionable, List<TriageCard> informational,
                                long actionableCount, long informationalCount) {
    }
}
