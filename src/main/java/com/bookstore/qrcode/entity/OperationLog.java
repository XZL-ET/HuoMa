package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体。
 * <p>
 * 记录管理后台的关键操作（创建、删除、修改等），用于安全审计和问题追溯。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.1
 */
@Entity
@Table(name = "operation_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人（登录用户名） */
    @Column(length = 100)
    private String operator;

    /** 操作类型，如 create/delete/update/sync */
    @Column(nullable = false, length = 100)
    private String action;

    /** 操作对象类型，如 qrcode/customer/agent */
    @Column(name = "target_type", length = 50)
    private String targetType;

    /** 操作对象 ID */
    @Column(name = "target_id", length = 100)
    private String targetId;

    /** 操作详情（JSON 格式） */
    @Column(columnDefinition = "JSON")
    private String detail;

    /** 操作时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
