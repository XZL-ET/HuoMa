package com.bookstore.qrcode.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 时间线上的一天：curated=true 时为人工定稿条目，否则为该日的原始提交（折叠展示）。
 */
public record TimelineEntry(LocalDate date, String title, boolean curated,
                            List<ReleaseItem> items, List<String> rawCommits) {
}
