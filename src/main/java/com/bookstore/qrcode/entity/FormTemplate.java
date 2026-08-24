package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_template")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 表单页副标题（提示语），为空时使用系统默认 */
    @Column(length = 200)
    private String subtitle;

    /** 发送卡片标题，为空时使用默认 "📋 请填写孩子信息" */
    @Column(name = "card_title", length = 100)
    private String cardTitle;

    /** 发送卡片描述，为空时使用默认 */
    @Column(name = "card_desc", length = 500)
    private String cardDesc;

    /** 发送卡片图片链接，为空时不传 picurl */
    @Column(name = "card_pic_url", length = 500)
    private String cardPicUrl;

    @Column(name = "fields", columnDefinition = "JSON", nullable = false)
    private String fields;

    @Column(name = "tag_mapping", columnDefinition = "JSON", nullable = false)
    private String tagMapping;

    @Column(name = "remark_template", length = 500)
    private String remarkTemplate;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
