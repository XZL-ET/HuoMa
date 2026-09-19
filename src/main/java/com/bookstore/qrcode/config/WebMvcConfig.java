package com.bookstore.qrcode.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring MVC 配置：将上传目录映射为静态资源路径，使上传的卡片图片可通过 /uploads/** 访问。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${upload.card-pic-dir:./data/uploads/card-pics}")
    private String cardPicDir;

    @Value("${upload.grade-cover-dir:./data/uploads/grade-covers}")
    private String gradeCoverDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registerDir(registry, cardPicDir, "/uploads/card-pics/**");
        registerDir(registry, gradeCoverDir, "/uploads/grade-covers/**");
    }

    private void registerDir(ResourceHandlerRegistry registry, String dirValue, String urlPattern) {
        try {
            Path dir = Path.of(dirValue).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            registry.addResourceHandler(urlPattern)
                    .addResourceLocations("file:" + dir.toString().replace('\\', '/') + "/");
            log.info("Upload resource handler registered: {} → {}", urlPattern, dir);
        } catch (Exception e) {
            log.error("Failed to create upload directory: {}", dirValue, e);
        }
    }
}
