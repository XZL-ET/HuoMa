package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码下载日志实体。
 * <p>
 * 每次员工下载活码二维码时写入一条记录。
 * 同一员工多次下载同一活码产生多条记录（非 upsert），
 * 下载次数通过 COUNT 聚合计算。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Entity
@Table(name = "qr_download_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrDownloadLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被下载的活码 ID */
    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    /** 下载员工的企微 userid */
    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    /** 下载时间 */
    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;

    /** 下载来源 IP */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @PrePersist
    void prePersist() {
        if (downloadedAt == null) {
            downloadedAt = LocalDateTime.now();
        }
    }
}
