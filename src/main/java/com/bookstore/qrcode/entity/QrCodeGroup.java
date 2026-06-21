package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_code_group")
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class QrCodeGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "region_city", length = 50)
    private String regionCity;

    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    @Column(name = "group_type", nullable = false, length = 20)
    @Builder.Default
    private String groupType = "alliance";

    @Column(name = "default_welcome_text", length = 500)
    private String defaultWelcomeText;

    @Column(name = "default_form_template_id")
    private Long defaultFormTemplateId;

    /** 联盟关联的唯一活码 ID（一个联盟只有一个活码） */
    @Column(name = "qr_code_id")
    private Long qrCodeId;

    /** 联盟包含的学校列表，一行一个学校名称 */
    @Column(name = "school_list", columnDefinition = "TEXT")
    private String schoolList;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
