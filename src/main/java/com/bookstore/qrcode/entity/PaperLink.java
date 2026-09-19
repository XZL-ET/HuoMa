package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 试题内容链接（年级 × 科目）。
 * <p>
 * 「补发表单·送试题」场景：家长提交表单选好年级和科目后，按所选（年级, 科目）
 * 查本表返回对应的试题图文永久链接，前端展示「领取」入口。
 * 每个（年级, 科目）组合唯一，由后台管理增删改查。
 * </p>
 *
 * @author Bookstore Dev
 */
@Entity
@Table(name = "paper_link", uniqueConstraints = @UniqueConstraint(columnNames = {"grade", "subject"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 年级，如「高一」 */
    @Column(nullable = false, length = 50)
    private String grade;

    /** 科目，如「数学」 */
    @Column(nullable = false, length = 50)
    private String subject;

    /** 试题标题，如「高一数学期中试题」 */
    @Column(length = 200)
    private String title;

    /** 试题图文永久链接（mp.weixin.qq.com/s/xxx） */
    @Column(name = "paper_url", nullable = false, length = 500)
    private String paperUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
