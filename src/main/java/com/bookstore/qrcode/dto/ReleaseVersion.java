package com.bookstore.qrcode.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 一个人工定稿的版本记录。
 * coversFrom/coversTo 标记这条记录覆盖的提交区间，区间内的原始提交不再重复展示。
 */
public record ReleaseVersion(LocalDate date, String title,
                             LocalDate coversFrom, LocalDate coversTo,
                             List<ReleaseItem> items) {
}
