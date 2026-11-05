package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.TimelineEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 读取打包进 jar 的定稿记录与构建期提交列表。
 * changelog.txt 由 Maven 在 prepare-package 阶段执行 git log 生成，生产机无需安装 git。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseNotesService {

    private static final String CURATED_PATH = "classpath:release-notes.yml";
    private static final String COMMITS_PATH = "classpath:changelog.txt";

    private final ResourceLoader resourceLoader;
    private final ReleaseNotesParser parser;
    private final ReleaseTimelineBuilder builder;

    public List<TimelineEntry> timeline(boolean showAll) {
        return builder.build(
            parser.parseVersions(read(CURATED_PATH)),
            parser.parseCommits(read(COMMITS_PATH)),
            LocalDate.now(),
            showAll);
    }

    /** jar 的修改时间即部署时间（部署脚本是 cat 上传，mtime 会刷新）。 */
    public Optional<LocalDateTime> buildTime() {
        try {
            URL location = ReleaseNotesService.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = archivePath(location.toURI());
            return Optional.of(Files.getLastModifiedTime(path)
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception e) {
            log.debug("无法读取运行时包时间: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Spring Boot fat jar 里的 code source 是 jar:nested:/path/app.jar/!BOOT-INF/classes!/，
     * 直接 Paths.get 会落到包内根目录（其 mtime 是 JVM 启动时间）。这里剥出外层 jar 路径。
     */
    static Path archivePath(URI location) {
        String value = location.toString();
        if (!value.startsWith("jar:")) {
            return Paths.get(location);
        }
        value = value.substring("jar:".length());
        if (value.startsWith("nested:")) {
            value = value.substring("nested:".length());
        }
        int cut = value.indexOf("/!");
        if (cut < 0) {
            cut = value.indexOf("!/");
        }
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        if (value.startsWith("file:")) {
            return Paths.get(URI.create(value));
        }
        // Windows 上嵌套 URL 形如 /D:/dir/app.jar
        if (value.matches("^/[A-Za-z]:.*")) {
            value = value.substring(1);
        }
        return Paths.get(value);
    }

    private String read(String location) {
        Resource resource = resourceLoader.getResource(location);
        // 未经 package 的构建（IDE / spring-boot:run）本来就没有 changelog.txt，不算异常
        if (!resource.exists()) {
            log.debug("{} 不存在，跳过", location);
            return "";
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 {} 失败（页面将缺少这部分内容）: {}", location, e.getMessage());
            return "";
        }
    }
}
