package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 服务老师日限统一管理服务。
 * <p>
 * 提供「服务老师每日可加人数」的默认值解析与存量批量应用能力，
 * 供系统配置页（/admin/system-config）一键调整所有服务老师的日限。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ServiceTeacherDailyMaxService {

    /** system_config 中服务老师日限默认值的配置键 */
    public static final String CONFIG_KEY = "service_daily_max_default";

    /** 服务老师日限允许的最大值 */
    public static final int MAX_DAILY_MAX = 800;

    private final SystemConfigRepository systemConfigRepo;
    private final QrAgentRepository qrAgentRepo;
    private final EntityManager entityManager;

    /** 未配置时的兜底默认值，沿用批量导入默认日限 */
    @Value("${app.agent.batch-import-daily-max:300}")
    private int fallbackDefault;

    /**
     * 解析服务老师日限默认值：优先取 system_config，缺失或非法则兜底。
     *
     * @return 日限默认值（恒为正整数）
     */
    public int resolveDefault() {
        return systemConfigRepo.findByConfigKey(CONFIG_KEY)
                .map(SystemConfig::getConfigValue)
                .map(this::parseOrDefault)
                .orElse(fallbackDefault);
    }

    /**
     * 将指定日限应用到全部服务老师（role=service 或 dual 且未移除）。
     * <p>执行前先建带时间戳的备份表，便于回滚。</p>
     *
     * @param value 目标日限值
     * @return 实际更新的服务老师行数
     */
    public int applyToAll(int value) {
        validateDailyMax(value);
        backupServiceTeachers();
        return qrAgentRepo.applyDailyMaxToServiceTeachers(value);
    }

    /**
     * 保存服务老师日限默认值到 system_config（新增或更新）。
     *
     * @param value 日限默认值
     */
    public void saveDefault(int value) {
        validateDailyMax(value);
        SystemConfig config = systemConfigRepo.findByConfigKey(CONFIG_KEY)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(CONFIG_KEY);
                    return c;
                });
        config.setConfigValue(String.valueOf(value));
        systemConfigRepo.save(config);
    }

    private void validateDailyMax(int value) {
        if (value <= 0 || value > MAX_DAILY_MAX) {
            throw new IllegalArgumentException("日限必须在 1~" + MAX_DAILY_MAX + " 之间");
        }
    }

    private int parseOrDefault(String value) {
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 ? v : fallbackDefault;
        } catch (NumberFormatException e) {
            return fallbackDefault;
        }
    }

    private void backupServiceTeachers() {
        String tableName = "qr_agent_bak_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        entityManager.createNativeQuery(
                "CREATE TABLE " + tableName
                        + " AS SELECT * FROM qr_agent WHERE role IN ('service','dual') AND status<>'removed'")
                .executeUpdate();
    }
}
