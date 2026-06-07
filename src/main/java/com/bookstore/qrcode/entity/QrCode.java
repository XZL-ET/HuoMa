package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_code")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_name", nullable = false, length = 100)
    private String schoolName;

    @Column(name = "school_id", nullable = false, unique = true, length = 50)
    private String schoolId;

    @Column(name = "region_city", nullable = false, length = 50)
    private String regionCity;

    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    @Column(name = "qr_config_id", length = 100)
    private String qrConfigId;

    @Column(name = "qr_url", length = 500)
    private String qrUrl;

    @Column(name = "qr_image_path", length = 500)
    private String qrImagePath;

    @Column(name = "style_config", columnDefinition = "JSON")
    private String styleConfig;

    @Column(name = "welcome_config", columnDefinition = "JSON")
    private String welcomeConfig;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private QrCodeStatus status = QrCodeStatus.active;

    @Column(name = "rotate_mode", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private RotateMode rotateMode = RotateMode.auto;

    @Column(name = "warn_ratio")
    private Integer warnRatio = 80;

    @Column(name = "urgent_ratio")
    private Integer urgentRatio = 95;

    @Column(name = "create_mode", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CreateMode createMode;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum QrCodeStatus { active, paused, full, no_agent }
    public enum RotateMode { auto, manual }
    public enum CreateMode { manual, batch_import }
}
