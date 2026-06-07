package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {
    @Id
    @Column(length = 100)
    private String userid;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String mobile;

    @Column(length = 200)
    private String department;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentRole role = AgentRole.receptionist;

    @Column(name = "daily_total_cap", nullable = false)
    @Builder.Default
    private Integer dailyTotalCap = 500;

    @Column(name = "daily_total_used")
    @Builder.Default
    private Integer dailyTotalUsed = 0;

    @Column(name = "overall_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OverallStatus overallStatus = OverallStatus.normal;

    @Column(name = "status_reason", columnDefinition = "JSON")
    private String statusReason;

    @Column(name = "total_added")
    @Builder.Default
    private Integer totalAdded = 0;

    @Column(name = "total_deleted")
    @Builder.Default
    private Integer totalDeleted = 0;

    @Column(name = "melted_count_24h")
    @Builder.Default
    private Integer meltedCount24h = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum AgentRole { receptionist, service, dual }
    public enum OverallStatus { normal, warning, blocked, melted }
}
