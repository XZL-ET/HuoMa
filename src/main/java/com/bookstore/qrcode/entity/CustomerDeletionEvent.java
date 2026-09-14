package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 客户删除关系事件实体。
 * <p>
 * 记录企微回调中「客户删除员工」({@code del_follow_user}) 与「员工删除客户」
 * ({@code del_external_contact}) 两类删除事件，供每日日报汇总推送。
 * 每条记录保留被删的客户 external_userid、被删的员工 userid、删除方向与发生时间。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Entity
@Table(name = "customer_deletion_event",
    indexes = @Index(name = "idx_deletion_direction_time", columnList = "direction, deleted_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDeletionEvent {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被删关系的客户 external_userid */
    @Column(name = "external_userid", nullable = false, length = 100)
    private String externalUserid;

    /** 被删关系的员工 userid */
    @Column(nullable = false, length = 100)
    private String userid;

    /** 删除方向 */
    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Direction direction;

    /** 删除来源（仅员工删客户事件携带，如 DELETE_BY_TRANSFER），可为 null */
    @Column(length = 50)
    private String source;

    /** 删除发生时间 */
    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    /** 记录创建时间，由 {@code prePersist} 自动填充 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    /** 删除方向枚举。 */
    public enum Direction {
        /** 客户删除员工（企微 del_follow_user 事件） */
        CUSTOMER_DELETED_AGENT,
        /** 员工删除客户（企微 del_external_contact 事件） */
        AGENT_DELETED_CUSTOMER
    }
}
