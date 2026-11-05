package com.bookstore.qrcode.dto;

/** 一条更新内容；kind 可选（新增/修复/调整），为 null 时页面不显示标签。 */
public record ReleaseItem(String kind, String text) {
}
