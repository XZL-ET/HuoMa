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
