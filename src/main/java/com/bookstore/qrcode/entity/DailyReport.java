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

    @Builder.Default
    private Integer totalScan = 0;
    @Builder.Default
    private Integer totalAdd = 0;

    @Column(name = "total_add_fail")
    @Builder.Default
    private Integer totalAddFail = 0;

    @Builder.Default
    private Integer totalTransfer = 0;

    @Column(name = "total_transfer_ok")
    @Builder.Default
    private Integer totalTransferOk = 0;

    @Builder.Default
    private Integer totalRotate = 0;
    @Builder.Default
    private Integer totalAlert = 0;
    @Builder.Default
    private Integer activeQr = 0;
    @Builder.Default
    private Integer fullQr = 0;
    @Builder.Default
    private Integer blockedAgent = 0;
    @Builder.Default
    private Integer meltedAgent = 0;

    @Column(name = "detail_json", columnDefinition = "JSON")
    private String detailJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }
}
