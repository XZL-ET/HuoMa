package com.bookstore.qrcode.wecom;

import java.util.Map;
import java.util.Set;

/**
 * 企微 API 常见错误码，用于异常监控分类。
 */
public class WecomErrorCodes {

    /** 对方已添加 — 不算异常 */
    public static final int ALREADY_ADDED = 20302;

    /** 对方拒绝添加 */
    public static final int REJECTED = 25002;

    /** 操作频率过高 → 触发熔断 */
    public static final int RATE_LIMITED = 84061;

    /** 已被对方删除 */
    public static final int DELETED_BY_USER = 84073;

    /** 触发熔断的错误码 */
    public static final Set<Integer> MELT_CODES = Set.of(RATE_LIMITED);

    /** 需要累计统计的异常码 */
    public static final Map<Integer, Integer> ACCUMULATE_THRESHOLD = Map.of(
        REJECTED, 10,       // 累计 10 次 → 标记
        DELETED_BY_USER, 5  // 累计 5 次 → 标记
    );

    private WecomErrorCodes() {}
}
