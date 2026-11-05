package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.TimelineEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("打包进 jar 的版本记录资源")
class ReleaseNotesServiceTest {

    private final ReleaseNotesService service = new ReleaseNotesService(
        new DefaultResourceLoader(), new ReleaseNotesParser(), new ReleaseTimelineBuilder());

    @Test
    @DisplayName("release-notes.yml 能被解析出定稿条目")
    void shippedCuratedNotesAreParseable() {
        List<TimelineEntry> timeline = service.timeline(false);

        assertThat(timeline).isNotEmpty();
        assertThat(timeline).filteredOn(TimelineEntry::curated)
            .isNotEmpty()
            .allSatisfy(entry -> {
                assertThat(entry.title()).isNotBlank();
                assertThat(entry.items()).isNotEmpty();
            });
    }

    @Test
    @DisplayName("定稿条目按日期倒序排列")
    void curatedEntriesSortedNewestFirst() {
        List<TimelineEntry> curated = service.timeline(false).stream()
            .filter(TimelineEntry::curated)
            .toList();

        assertThat(curated).isSortedAccordingTo(
            (a, b) -> b.date().compareTo(a.date()));
    }

    @Test
    @DisplayName("读取运行时包时间不抛异常")
    void buildTimeDoesNotThrow() {
        assertThat(service.buildTime()).isNotNull();
    }

    @Test
    @DisplayName("fat jar 的 nested URI 要解析出外层 app.jar，而不是包内根目录")
    void resolvesOuterArchiveFromNestedUri() throws Exception {
        URI uri = new URI("jar:nested:/opt/HuoMa/app.jar/!BOOT-INF/classes/!/");

        assertThat(ReleaseNotesService.archivePath(uri))
            .isEqualTo(Path.of("/opt/HuoMa/app.jar"));
    }

    @Test
    @DisplayName("经典 jar URI 同样解析出外层 app.jar")
    void resolvesOuterArchiveFromClassicJarUri() throws Exception {
        URI uri = new URI("jar:file:/opt/HuoMa/app.jar!/BOOT-INF/classes!/");

        assertThat(ReleaseNotesService.archivePath(uri))
            .isEqualTo(Path.of("/opt/HuoMa/app.jar"));
    }

    @Test
    @DisplayName("开发态的普通目录直接使用该路径")
    void resolvesPlainDirectoryPath() throws Exception {
        URI uri = new URI("file:/D:/ClaudeCode/HuoMa/target/classes/");

        assertThat(ReleaseNotesService.archivePath(uri)).isEqualTo(Path.of(uri));
    }
}
