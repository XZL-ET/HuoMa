package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 年级标签归并进度/报告行（仅观测与断点续跑，不承载正确性）。 */
@Entity
@Table(name = "grade_tag_migration_progress")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GradeTagMigrationProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "external_userid", length = 100)
    private String externalUserid;

    /** 行级分类：R1/R2/R3 */
    @Column(name = "row_class", nullable = false, length = 4)
    private String rowClass;

    @Column(name = "local_tag_id", nullable = false)
    private Long localTagId;

    @Column(name = "local_tag_name", length = 100)
    private String localTagName;

    @Column(name = "target_wecom_tag_id", length = 50)
    private String targetWecomTagId;

    @Column(name = "target_tag_id")
    private Long targetTagId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "requires_review", nullable = false)
    @Builder.Default
    private Boolean requiresReview = false;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
