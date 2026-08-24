package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 系统配置实体（键值对存储）。
 * <p>
 * 存储全局配置项，如全局联系人信息。
 * 通过 config_key 唯一标识一个配置项。
 * </p>
 */
@Entity
@Table(name = "system_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_name", length = 100)
    private String configName;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
