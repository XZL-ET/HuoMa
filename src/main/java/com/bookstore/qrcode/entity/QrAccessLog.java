package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码访问日志实体。
 * <p>
 * 统一记录员工下载和学校自助查询两类渠道的查看/下载行为。
 * channel='employee' 对应下载中心员工操作；
 * channel='school' 对应学校自助查询页面操作。
 * </p>
 */
@Entity
@Table(name = "qr_access_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrAccessLog {

    public enum Action { view, download }
    public enum Channel { employee, school }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    @Builder.Default
    private Action action = Action.view;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    @Builder.Default
    private Channel channel = Channel.school;

    @Column(name = "user_identity", length = 128)
    private String userIdentity;

    @Column(name = "accessed_at", updatable = false)
    private LocalDateTime accessedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @PrePersist
    void prePersist() {
        accessedAt = LocalDateTime.now();
    }
}
