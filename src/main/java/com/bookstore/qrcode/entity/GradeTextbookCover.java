package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 各年级语文课本封面图（图片版收集表单联动展示）。
 * <p>以年级名作为主键（自然键），每个年级一张封面图。</p>
 */
@Entity
@Table(name = "grade_textbook_cover")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GradeTextbookCover {

    @Id
    @Column(name = "grade_name", length = 50)
    private String gradeName;

    @Column(name = "image_url", length = 500, nullable = false)
    private String imageUrl;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
