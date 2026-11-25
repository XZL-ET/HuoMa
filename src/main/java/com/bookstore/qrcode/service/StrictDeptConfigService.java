package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 活码严格同部门取人配置服务。
 *
 * <p>读写 {@code system_config} 中的两个配置键：</p>
 * <ul>
 *   <li>{@value #STRICT_MODE_KEY}：严格同部门取人开关，仅字符串 {@code "true"}
 *       （忽略大小写）视为开启，缺失或其它值一律关闭。</li>
 *   <li>{@value #EXEMPT_USERIDS_KEY}：豁免服务老师 userid 的 JSON 数组。</li>
 * </ul>
 *
 * <p>配置每次调用都从 DB 读取，不做实例级缓存——服务部署在 ALB 后的双节点上，
 * 配置改动必须立即在两个节点同时生效。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrictDeptConfigService {

    /** system_config 中「严格同部门取人」开关的配置键。 */
    public static final String STRICT_MODE_KEY = "strict_dept_mode";

    /** system_config 中严格同部门豁免服务老师名单的配置键。 */
    public static final String EXEMPT_USERIDS_KEY = "strict_dept_exempt_userids";

    /** 开关配置的默认中文名。 */
    private static final String STRICT_MODE_NAME = "严格同部门取人";

    /** 豁免名单配置的默认中文名。 */
    private static final String EXEMPT_USERIDS_NAME = "严格同部门豁免服务老师";

    /** 未配置或解析失败时的豁免服务老师名单。 */
    public static final Set<String> DEFAULT_EXEMPT_USERIDS = Set.of(
            "18809427636", "18709336236", "15213919369", "15209340344", "WeiSiQi");

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * 严格同部门取人是否开启。
     *
     * @return 配置值为 {@code "true"}（忽略大小写）时为 true；缺失或其它值为 false
     */
    public boolean isStrictModeEnabled() {
        return systemConfigRepository.findByConfigKey(STRICT_MODE_KEY)
                .map(SystemConfig::getConfigValue)
                .map(value -> value != null && "true".equalsIgnoreCase(value.trim()))
                .orElse(false);
    }

    /**
     * 写入严格同部门取人开关。
     *
     * @param enabled 是否开启
     */
    public void setStrictModeEnabled(boolean enabled) {
        upsert(STRICT_MODE_KEY, STRICT_MODE_NAME, String.valueOf(enabled));
    }

    /**
     * 读取豁免服务老师 userid 集合。
     *
     * @return 配置的 userid 集合；键缺失时返回 {@link #DEFAULT_EXEMPT_USERIDS} 的不可变副本，
     *         解析失败时记录 WARN 并回退同一默认名单
     */
    public Set<String> getExemptUserids() {
        return systemConfigRepository.findByConfigKey(EXEMPT_USERIDS_KEY)
                .map(this::parseExemptUserids)
                .orElseGet(() -> Set.copyOf(DEFAULT_EXEMPT_USERIDS));
    }

    /**
     * 写入豁免服务老师 userid 集合。
     *
     * @param userids userid 集合；null 视为空集合
     * @throws IllegalStateException 序列化失败（写入失败不静默）
     */
    public void setExemptUserids(Collection<String> userids) {
        Set<String> values = userids == null ? Set.of() : new LinkedHashSet<>(userids);
        String json;
        try {
            json = objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("严格同部门豁免名单序列化失败", e);
        }
        upsert(EXEMPT_USERIDS_KEY, EXEMPT_USERIDS_NAME, json);
    }

    private Set<String> parseExemptUserids(SystemConfig config) {
        String value = config.getConfigValue();
        try {
            Set<String> parsed = objectMapper.readValue(value, new TypeReference<Set<String>>() {});
            return parsed == null ? Set.copyOf(DEFAULT_EXEMPT_USERIDS) : Set.copyOf(parsed);
        } catch (Exception e) {
            log.warn("严格同部门豁免名单解析失败，回退默认名单: key={}, value={}", EXEMPT_USERIDS_KEY, value, e);
            return Set.copyOf(DEFAULT_EXEMPT_USERIDS);
        }
    }

    /**
     * 新增或更新配置项：键已存在时保留原 configName（为空则用默认名）。
     */
    private void upsert(String key, String defaultName, String value) {
        String configName = systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(defaultName);
        systemConfigRepository.save(SystemConfig.builder()
                .configKey(key)
                .configName(configName)
                .configValue(value)
                .build());
    }
}
