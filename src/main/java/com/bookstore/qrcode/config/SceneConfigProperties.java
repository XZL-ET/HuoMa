package com.bookstore.qrcode.config;

import com.bookstore.qrcode.entity.Scene;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 场景预设配置 — 绑定 application.yml 中 app.scene.* 配置。
 *
 * <p>每个场景包含预期扫码率（scanRatio）和预激活阈值（urgentRatio），
 * 用于活码创建时自动计算初始接待员数及扩容触发点。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.scene")
public class SceneConfigProperties {

    /** 日常推送场景预设：扫码率 20%，预激活阈值 95% */
    private ScenePreset dailyPush = new ScenePreset(0.20, 95);

    /** 家长会场景预设：扫码率 75%，预激活阈值 70% */
    private ScenePreset parentMeeting = new ScenePreset(0.75, 70);

    /**
     * 根据场景枚举获取对应预设配置。
     *
     * @param scene 场景枚举，null 时返回 dailyPush 默认值
     * @return 匹配的场景预设
     */
    public ScenePreset getPreset(Scene scene) {
        if (scene == null) return dailyPush;
        return switch (scene) {
            case daily_push -> dailyPush;
            case parent_meeting -> parentMeeting;
        };
    }

    /**
     * 场景预设值对象。
     *
     * <p>包含预期扫码率和预激活阈值百分比两个参数，
     * 用于自动计算活码初始接待员数量。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenePreset {
        /** 预期扫码率，如 0.20 表示预计 20% 的学生会扫码 */
        private double scanRatio;

        /** 预激活阈值百分比，如 70 表示达到日限 70% 时预加载后备 */
        private int urgentRatio;
    }
}
