package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.RawCommit;
import com.bookstore.qrcode.dto.ReleaseItem;
import com.bookstore.qrcode.dto.ReleaseVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析人工定稿的 release-notes.yml 与构建期生成的 changelog.txt。 */
@Slf4j
@Component
public class ReleaseNotesParser {

    private static final Pattern COMMIT_SUBJECT =
        Pattern.compile("^(?:feat|fix)(?:\\([^)]*\\))?[:：]\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    public List<ReleaseVersion> parseVersions(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        Object root;
        try {
            root = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            // 手写 YAML 写坏不能让整页 500：退回只显示原始提交
            log.warn("release-notes.yml 解析失败，本次只显示原始提交: {}", e.getMessage());
            return List.of();
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("versions") instanceof List<?> list)) {
            return List.of();
        }
        List<ReleaseVersion> versions = new ArrayList<>();
        for (Object node : list) {
            if (node instanceof Map<?, ?> item) {
                ReleaseVersion version = toVersion(item);
                if (version != null) {
                    versions.add(version);
                }
            }
        }
        return versions;
    }

    private ReleaseVersion toVersion(Map<?, ?> node) {
        LocalDate date = parseDate(text(node.get("date")));
        if (date == null) {
            return null;
        }
        LocalDate coversFrom = date;
        LocalDate coversTo = date;
        String covers = text(node.get("covers"));
        int tilde = covers == null ? -1 : covers.indexOf('~');
        if (tilde >= 0) {
            LocalDate from = parseDate(covers.substring(0, tilde).trim());
            LocalDate to = parseDate(covers.substring(tilde + 1).trim());
            coversFrom = from != null ? from : date;
            coversTo = to != null ? to : date;
        }
        List<ReleaseItem> items = new ArrayList<>();
        if (node.get("items") instanceof List<?> rawItems) {
            for (Object raw : rawItems) {
                ReleaseItem item = toItem(raw);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return new ReleaseVersion(date, text(node.get("title")), coversFrom, coversTo, items);
    }

    private ReleaseItem toItem(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            String content = text(map.get("text"));
            return content == null ? null : new ReleaseItem(text(map.get("kind")), content);
        }
        if (raw == null) {
            return null;
        }
        String content = raw.toString().trim();
        return content.isEmpty() ? null : new ReleaseItem(null, content);
    }

    public List<RawCommit> parseCommits(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<RawCommit> commits = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            LocalDate date = parseDate(line.substring(0, tab).trim());
            if (date == null) {
                continue;
            }
            Matcher matcher = COMMIT_SUBJECT.matcher(line.substring(tab + 1).trim());
            if (matcher.matches()) {
                commits.add(new RawCommit(date, matcher.group(1).trim()));
            }
        }
        return commits;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        // YAML 会把裸写的 2026-11-05 解析成 UTC 的 Date，统一转回 ISO 日期串
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
