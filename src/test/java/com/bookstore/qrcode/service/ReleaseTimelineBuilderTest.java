package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.RawCommit;
import com.bookstore.qrcode.dto.ReleaseItem;
import com.bookstore.qrcode.dto.ReleaseVersion;
import com.bookstore.qrcode.dto.TimelineEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("版本时间线合并")
class ReleaseTimelineBuilderTest {

    private final ReleaseTimelineBuilder builder = new ReleaseTimelineBuilder();
    private final LocalDate today = LocalDate.of(2026, 11, 5);

    private static ReleaseVersion version(LocalDate date, LocalDate from, LocalDate to, String title) {
        return new ReleaseVersion(date, title, from, to, List.of(new ReleaseItem(null, title)));
    }

    @Test
    @DisplayName("covers 范围内的原始提交不再单列")
    void suppressedRawCommitsHiddenByCovers() {
        ReleaseVersion v = version(LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), "部门准入白名单");
        List<RawCommit> raw = List.of(
            new RawCommit(LocalDate.of(2026, 11, 2), "add eligibility service"),
            new RawCommit(LocalDate.of(2026, 11, 3), "enforce whitelist"),
            new RawCommit(LocalDate.of(2026, 11, 5), "系统配置页新增接待员日限组件"));

        List<TimelineEntry> timeline = builder.build(List.of(v), raw, today, false);

        assertThat(timeline).extracting(TimelineEntry::date)
            .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 4));
        assertThat(timeline.get(0).curated()).isFalse();
        assertThat(timeline.get(0).rawCommits()).containsExactly("系统配置页新增接待员日限组件");
        assertThat(timeline.get(1).curated()).isTrue();
        assertThat(timeline.get(1).title()).isEqualTo("部门准入白名单");
    }

    @Test
    @DisplayName("同一天的原始提交归为一组，按文件顺序保留")
    void rawCommitsGroupedByDate() {
        List<RawCommit> raw = List.of(
            new RawCommit(LocalDate.of(2026, 11, 5), "第一条"),
            new RawCommit(LocalDate.of(2026, 11, 5), "第二条"),
            new RawCommit(LocalDate.of(2026, 11, 4), "第三条"));

        List<TimelineEntry> timeline = builder.build(List.of(), raw, today, false);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).rawCommits()).containsExactly("第一条", "第二条");
        assertThat(timeline.get(1).rawCommits()).containsExactly("第三条");
    }

    @Test
    @DisplayName("默认只显示最近三个月，showAll 时给全量")
    void filtersToLastThreeMonthsUnlessShowAll() {
        List<RawCommit> raw = List.of(
            new RawCommit(LocalDate.of(2026, 11, 5), "新的"),
            new RawCommit(LocalDate.of(2026, 6, 1), "旧的"));

        assertThat(builder.build(List.of(), raw, today, false))
            .extracting(TimelineEntry::rawCommits)
            .containsExactly(List.of("新的"));

        assertThat(builder.build(List.of(), raw, today, true)).hasSize(2);
    }

    @Test
    @DisplayName("定稿条目即使超过三个月也始终显示")
    void curatedEntriesNeverFilteredOut() {
        ReleaseVersion old = version(LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 10), "很老的版本");

        List<TimelineEntry> timeline = builder.build(List.of(old), List.of(), today, false);

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).title()).isEqualTo("很老的版本");
    }
}
