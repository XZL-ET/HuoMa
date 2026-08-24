package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 学校分类实体。
 * <p>
 * 介于 QrCodeGroup 和 SystemConfig 之间的继承层，
 * 允许按学段/类型统一配置默认欢迎语和表单模板。
 * 一个学校只属于一个分类（通过 {@code School.categoryId} 关联）。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2026-06-26
 */
@Entity
@Table(name = "school_category")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchoolCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "default_welcome_text", length = 500)
    private String defaultWelcomeText;

    @Column(name = "default_form_template_id")
    private Long defaultFormTemplateId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
