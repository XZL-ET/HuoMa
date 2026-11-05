package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.RawCommit;
import com.bookstore.qrcode.dto.ReleaseVersion;
import com.bookstore.qrcode.dto.TimelineEntry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把定稿条目和原始提交合成一条时间线：
 * 定稿覆盖的日期不再出现原始提交；定稿条目不受时间窗口限制，原始提交默认只看最近三个月。
 */
@Component
public class ReleaseTimelineBuilder {

    public List<TimelineEntry> build(List<ReleaseVersion> versions, List<RawCommit> commits,
                                     LocalDate referenceDate, boolean showAll) {
        LocalDate cutoff = referenceDate.minusMonths(3);
        Set<LocalDate> covered = new HashSet<>();
        List<TimelineEntry> entries = new ArrayList<>();

        for (ReleaseVersion version : versions) {
            LocalDate from = version.coversFrom() != null ? version.coversFrom() : version.date();
            LocalDate to = version.coversTo() != null ? version.coversTo() : version.date();
            if (from.isAfter(to)) {
                LocalDate swap = from;
                from = to;
                to = swap;
            }
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                covered.add(day);
            }
            entries.add(new TimelineEntry(version.date(), version.title(), true, version.items(), List.of()));
        }

        Map<LocalDate, List<String>> byDate = new LinkedHashMap<>();
        for (RawCommit commit : commits) {
            if (covered.contains(commit.date()) || (!showAll && commit.date().isBefore(cutoff))) {
                continue;
            }
            byDate.computeIfAbsent(commit.date(), key -> new ArrayList<>()).add(commit.subject());
        }
        byDate.forEach((date, subjects) ->
            entries.add(new TimelineEntry(date, null, false, List.of(), subjects)));

        entries.sort(Comparator.comparing(TimelineEntry::date).reversed());
        return entries;
    }
}
