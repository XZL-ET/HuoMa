package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate date;

    private Integer totalScan = 0;
    private Integer totalAdd = 0;

    @Column(name = "total_add_fail")
    private Integer totalAddFail = 0;

    private Integer totalTransfer = 0;

    @Column(name = "total_transfer_ok")
    private Integer totalTransferOk = 0;

    private Integer totalRotate = 0;
    private Integer totalAlert = 0;
    private Integer activeQr = 0;
    private Integer fullQr = 0;
    private Integer blockedAgent = 0;
    private Integer meltedAgent = 0;

    @Column(name = "detail_json", columnDefinition = "JSON")
    private String detailJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }
}
