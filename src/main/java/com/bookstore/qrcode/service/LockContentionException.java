package com.bookstore.qrcode.service;

/**
 * 业务锁竞争异常 — 表示操作因 Redis 分布式锁被占用而暂时无法执行。
 * <p>
 * 与 {@link com.bookstore.qrcode.wecom.WecomApiException} 不同，
 * 此异常表示系统内部并发控制（而非外部 API 错误），调用方应
 * 延迟后重试，<b>不计入</b>正常重试配额。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
public class LockContentionException extends RuntimeException {

    public LockContentionException(String message) {
        super(message);
    }
}
