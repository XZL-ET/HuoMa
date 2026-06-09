package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_backup_pool")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrBackupPool {
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
    private PoolRole role = PoolRole.receptionist;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "daily_max")
    @Builder.Default
    private Integer dailyMax = 200;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PoolStatus status = PoolStatus.standby;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }

    public enum PoolRole { receptionist, service }
    public enum PoolStatus { standby, activated, removed }
}
