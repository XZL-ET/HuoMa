package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 学校主数据实体。
 * <p>
 * 存储所有学校（无论是否已有活码），作为学校自助查询的数据源。
 * 通过 school_id 与 {@link QrCode} 进行 LEFT JOIN 判断活码状态。
 * </p>
 */
@Entity
@Table(name = "school")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, unique = true, length = 64)
    private String schoolId;

    @Column(name = "school_name", nullable = false, length = 128)
    private String schoolName;

    @Column(name = "region_city", nullable = false, length = 64)
    private String regionCity;

    @Column(name = "region_district", nullable = false, length = 64)
    private String regionDistrict;

    @Column(name = "has_qrcode", nullable = false)
    @Builder.Default
    private Boolean hasQrcode = false;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

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
}
