package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {

    /**
     * 根据 config_key 查找系统配置项。
     *
     * @param configKey 配置键名，不可为 null
     * @return 包含匹配配置项的 Optional；若未找到则返回 {@link Optional#empty()}
     */
    Optional<SystemConfig> findByConfigKey(String configKey);
}
