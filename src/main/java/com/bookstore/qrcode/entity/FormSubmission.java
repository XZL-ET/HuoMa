package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_submission")
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class FormSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_template_id", nullable = false)
    private Long formTemplateId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "field_data", columnDefinition = "JSON", nullable = false)
    private String fieldData;

    /** 客户填写的学校名称（区域联盟场景下从 schoolList 下拉选择，独立学校自动取自活码） */
    @Column(name = "school_name", length = 100)
    private String schoolName;

    @Column(name = "tags_applied", length = 500)
    private String tagsApplied;

    @Column(name = "remark_updated", length = 500)
    private String remarkUpdated;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @PrePersist
    void prePersist() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
    }
}
