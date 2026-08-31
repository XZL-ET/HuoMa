package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码轮换日志 — 记录每次轮换/扩容事件。
 * <p>
 * 轮换触发原因包括：服务老师满员自动轮换、后备接待员承接客户、管理员手动指定轮换等。
 * 当 {@code fromUserid} 为空时，表示该记录为<b>新增</b>场景（客户首次分配，非轮换产生），
 * 此时 {@code toUserid} 为被分配的服务老师。
 * </p>
 * <p>
 * 每条日志关联一个 {@link com.bookstore.qrcode.entity.QrCode 活码}（通过 {@code qrCodeId}），
 * 表示该活码下的一次分配变更。
 * </p>
 */
@Entity
@Table(name = "qr_rotate_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrRotateLog {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联活码 ID，对应 {@link com.bookstore.qrcode.entity.QrCode#id}，不可空 */
    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    /** 轮换前负责的老师 userid；为空表示<b>新增</b>场景（非轮换产生），此时客户首次分配 */
    @Column(name = "from_userid", length = 100)
    private String fromUserid;

    /** 轮换后/新分配的老师 userid；纯下码（无接替者）场景为空 */
    @Column(name = "to_userid", length = 100)
    private String toUserid;

    /** 轮换原因说明，如 "服务老师满员自动轮换"、"后备接待员承接客户"、"管理员手动轮换" 等 */
    @Column(length = 500)
    private String reason;

    /** 记录创建时间，持久化后不可更新 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 持久化前自动写入当前时间作为 {@link #createdAt} */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
