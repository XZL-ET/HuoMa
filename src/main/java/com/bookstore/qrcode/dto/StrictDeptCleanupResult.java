package com.bookstore.qrcode.dto;

import java.util.List;

/**
 * 严格同部门清退执行结果。
 *
 * @param codesProcessed 成功处理的活码数（active 撤人或 full 撤人至少有一项执行）
 * @param codesSkipped   被跳过的活码数（轮换锁占用、本县无备选、跳过规则等）
 * @param activeRemoved  实际撤下的 active 接待员数
 * @param fullRemoved    实际撤下的 full 接待员数
 * @param backfilled     严格补位新增的接待员数
 * @param failures       逐条失败/跳过说明
 * @author Bookstore Dev
 * @since 2026-11-25
 */
public record StrictDeptCleanupResult(
    int codesProcessed,
    int codesSkipped,
    int activeRemoved,
    int fullRemoved,
    int backfilled,
    List<String> failures) {}
