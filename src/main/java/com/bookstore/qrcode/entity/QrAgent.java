package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_agent")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrAgent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentRole role = AgentRole.receptionist;

    @Column(name = "daily_max", nullable = false)
    @Builder.Default
    private Integer dailyMax = 200;

    @Column(name = "daily_current")
    @Builder.Default
    private Integer dailyCurrent = 0;

    @Column(name = "service_daily_max")
    private Integer serviceDailyMax;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentStatus status = AgentStatus.active;

    @Column(name = "replaced_by", length = 100)
    private String replacedBy;

    @Column(name = "last_reset_at")
    private LocalDateTime lastResetAt;

    @Column(name = "bind_target", columnDefinition = "JSON")
    private String bindTarget;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }

    public enum AgentRole { receptionist, service, dual }
    public enum AgentStatus { active, full, removed, blocked }
}
