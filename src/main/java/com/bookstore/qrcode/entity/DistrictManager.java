package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 区县负责人配置实体。
 * <p>
 * 城市 + 区县 唯一确定一位负责人。
 * 活码通过 regionCity + regionDistrict 自动关联展示其负责人。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Entity
@Table(name = "district_manager")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistrictManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 城市 */
    @Column(name = "region_city", nullable = false, length = 50)
    private String regionCity;

    /** 区/县 */
    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    /** 负责人企微 userid */
    @Column(name = "manager_userid", nullable = false, length = 100)
    private String managerUserid;

    /** 负责人姓名（冗余展示，从 Employee/Agent 表同步） */
    @Column(name = "manager_name", nullable = false, length = 100)
    private String managerName;

    /** 负责人企微联系我 config_id（自动创建，用于学校端兜底展示） */
    @Column(name = "qr_config_id", length = 64)
    private String qrConfigId;

    /** 负责人活码图片 URL */
    @Column(name = "qr_url", length = 512)
    private String qrUrl;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
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
}
