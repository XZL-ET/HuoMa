package com.bookstore.qrcode.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.awt.*;
import java.io.InputStream;

/**
 * CJK 字体配置。
 * <p>
 * 优先从 classpath 加载捆绑的中文字体（{@code fonts/NotoSansSC-Regular.ttf}），
 * 确保在无桌面环境的 Linux 服务器上也能正确渲染中文——避免学校名等中文文本变成方块（tofu）。
 * 若 classpath 字体不可用，则降级为 {@code app.qr-image.font-name} 指定的系统字体。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.3.0
 */
@Slf4j
@Configuration
public class FontConfig {

    /** classpath 中捆绑的中文字体路径 */
    @Value("${app.font.resource-path:fonts/NotoSansSC-Regular.ttf}")
    private String fontResourcePath;

    /** 降级系统字体名，与 QrImageService 的历史配置保持一致 */
    @Value("${app.qr-image.font-name:SansSerif}")
    private String fallbackFontName;

    /**
     * 提供应用全局使用的中文兼容 {@link Font} bean。
     * <p>
     * 返回的字体为 12pt 基准字号，调用方通过 {@link Font#deriveFont(float)} 派生所需字号。
     * 字体对象不可变且线程安全，以单例持有。
     * </p>
     *
     * @return 加载成功的中文字体，或降级的系统字体（不返回 {@code null}）
     */
    @Bean
    public Font chineseFont() {
        // 1. 尝试从 classpath 加载捆绑的中文字体
        if (fontResourcePath != null && !fontResourcePath.isBlank()) {
            try {
                ClassPathResource resource = new ClassPathResource(fontResourcePath);
                if (resource.exists()) {
                    try (InputStream is = resource.getInputStream()) {
                        Font loaded = Font.createFont(Font.TRUETYPE_FONT, is);
                        Font derived = loaded.deriveFont(Font.PLAIN, 12f);
                        log.info("已从 classpath 加载中文字体: {} → family={}",
                                fontResourcePath, derived.getFamily());
                        return derived;
                    }
                } else {
                    log.warn("classpath 字体文件不存在: {}", fontResourcePath);
                }
            } catch (Exception e) {
                log.warn("从 classpath 加载字体失败: {} — {}", fontResourcePath, e.getMessage());
            }
        }

        // 2. 降级：使用系统字体（Linux 上需安装中文字体并设置 QR_FONT_NAME 环境变量）
        Font fallback = new Font(fallbackFontName, Font.PLAIN, 12);
        log.warn("降级为系统字体: name={}, family={}。"
                + "若中文显示为方块，请在服务器上安装中文字体（如 yum install wqy-microhei-fonts）"
                + "并设置 QR_FONT_NAME 环境变量。",
                fallbackFontName, fallback.getFamily());
        return fallback;
    }
}
