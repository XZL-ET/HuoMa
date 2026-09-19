package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 客户 external_userid ↔ 公众号 openid 映射表。
 * <p>
 * 补发表单自动打标的关键桥梁：通过企微 {@code convert_to_openid} 批量把客户
 * external_userid 转为公众号 openid 落本表；家长在公众号网页授权时拿到 openid 后
 * 反查回 external_userid → customerId，实现「谁填了表单」的静默识别。
 * external_userid 唯一（幂等）。
 * </p>
 *
 * @author Bookstore Dev
 */
@Entity
@Table(name = "customer_openid_map")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOpenidMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 企微外部联系人 ID（唯一） */
    @Column(name = "external_userid", nullable = false, unique = true, length = 100)
    private String externalUserid;

    /** 公众号 openid */
    @Column(length = 100)
    private String openid;

    /** 公众号 appid（预留，convert_to_openid 可指定） */
    @Column(length = 100)
    private String appid;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
