package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_alert")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AlertSeverity severity = AlertSeverity.medium;

    @Column(columnDefinition = "JSON")
    private String detail;

    @Column(name = "auto_action", length = 20)
    @Enumerated(EnumType.STRING)
    private AutoAction autoAction = AutoAction.none;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AlertStatus status = AlertStatus.open;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }

    public enum AlertSeverity { low, medium, high }
    public enum AutoAction { none, paused, removed, melted }
    public enum AlertStatus { open, resolved, auto_resolved }
}
