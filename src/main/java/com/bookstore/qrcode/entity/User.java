package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 系统用户实体。
 * <p>
 * 用于管理后台登录认证，支持 admin/operator 两种角色。
 * 密码使用 BCrypt 哈希存储，不保存明文。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.1
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用户名，唯一 */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 密码哈希 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 显示名称（用于导航栏展示） */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 角色：admin（管理员）或 operator（运营人员） */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserRole role = UserRole.operator;

    /** 是否启用（禁用后无法登录） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public enum UserRole {
        admin,
        operator
    }
}
