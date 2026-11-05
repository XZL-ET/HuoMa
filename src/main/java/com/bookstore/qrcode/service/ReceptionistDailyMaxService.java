package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 接待员日限统一管理服务。
 * <p>
 * 提供「接待员每日可加人数」的默认值解析与存量批量应用能力，
 * 供系统配置页（/admin/system-config）一键调整所有接待员日限。
 * </p>
 * <p>与应用服务老师日限不同，接待员的实际日限判定读
 * {@code GlobalAgentPool.dailyMax}，活码关联上的 {@code qr_agent.dailyMax}
 * 仅供展示，因此批量应用必须同时更新两处。</p>
 */
@Service
@RequiredArgsConstructor
public class ReceptionistDailyMaxService {

    /** system_config 中接待员日限默认值的配置键 */
    public static final String CONFIG_KEY = "receptionist_daily_max_default";

    /** 接待员日限允许的最大值 */
    public static final int MAX_DAILY_MAX = 800;

    private final SystemConfigRepository systemConfigRepo;
    private final QrAgentRepository qrAgentRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final EntityManager entityManager;

    /** 未配置时的兜底默认值，沿用 app.agent.daily-max-default */
    @Value("${app.agent.daily-max-default:150}")
    private int fallbackDefault;

    /**
     * 解析接待员日限默认值：优先取 system_config，缺失或非法则兜底。
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
     * 将指定日限应用到全部接待员（role=receptionist 且未移除）。
     * <p>执行前先为两处数据建带时间戳的备份表，便于回滚。</p>
     *
     * @param value 目标日限值
     * @return 参与更新的活码关联行数（全局池同步同批执行）
     */
    @Transactional
    public int applyToAll(int value) {
        validateDailyMax(value);
        backupReceptionists();
        poolRepo.applyDailyMaxToReceptionists(value);
        return qrAgentRepo.applyDailyMaxToReceptionists(value);
    }

    /**
     * 保存接待员日限默认值到 system_config（新增或更新）。
     *
     * @param value 日限默认值
     */
    public void saveDefault(int value) {
        validateDailyMax(value);
        SystemConfig config = systemConfigRepo.findByConfigKey(CONFIG_KEY)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(CONFIG_KEY);
                    c.setConfigName("接待员日限默认值");
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

    private void backupReceptionists() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        entityManager.createNativeQuery(
                "CREATE TABLE global_agent_pool_bak_" + ts
                        + " AS SELECT * FROM global_agent_pool "
                        + "WHERE agent_userid IN (SELECT userid FROM agent WHERE role = 'receptionist')")
                .executeUpdate();
        entityManager.createNativeQuery(
                "CREATE TABLE qr_agent_bak_recept_" + ts
                        + " AS SELECT * FROM qr_agent WHERE role = 'receptionist' AND status<>'removed'")
                .executeUpdate();
    }
}
