package com.bookstore.qrcode.dto;

import java.time.LocalDate;

/** 构建期从 git log 带出来的原始提交，subject 已去掉 feat/fix 前缀。 */
public record RawCommit(LocalDate date, String subject) {
}
