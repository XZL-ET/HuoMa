package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_userid", nullable = false, unique = true, length = 100)
    private String externalUserid;

    @Column(length = 200)
    private String name;

    @Column(length = 500)
    private String avatar;

    @Column(nullable = false)
    @Builder.Default
    private Integer type = 1;

    @Column(length = 100)
    private String unionid;

    @Column(name = "added_agent", length = 100)
    private String addedAgent;

    @Column(name = "current_agent", length = 100)
    private String currentAgent;

    @Column(name = "source_qr_id")
    private Long sourceQrId;

    @Column(name = "school_id", length = 50)
    private String schoolId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CustomerStatus status = CustomerStatus.active;

    @Column(name = "add_time")
    private LocalDateTime addTime;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }

    public enum CustomerStatus { active, deleted }
}
