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

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path dir = Path.of(cardPicDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            registry.addResourceHandler("/uploads/card-pics/**")
                    .addResourceLocations("file:" + dir.toString().replace('\\', '/') + "/");
            log.info("Upload resource handler registered: /uploads/card-pics/** → {}", dir);
        } catch (Exception e) {
            log.error("Failed to create upload directory: {}", cardPicDir, e);
        }
    }
}
