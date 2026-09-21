package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_relation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "employee_userid", nullable = false, length = 100)
    private String employeeUserid;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "school_id", length = 50)
    private String schoolId;

    @Column(name = "add_time")
    private LocalDateTime addTime;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RelationStatus status = RelationStatus.active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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

    public enum RelationStatus {
        active, removed
    }
}
