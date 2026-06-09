package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码轮换日志 — 记录每次轮换/扩容事件。
 */
@Entity
@Table(name = "qr_rotate_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrRotateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    @Column(name = "from_userid", length = 100)
    private String fromUserid;

    @Column(name = "to_userid", nullable = false, length = 100)
    private String toUserid;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
