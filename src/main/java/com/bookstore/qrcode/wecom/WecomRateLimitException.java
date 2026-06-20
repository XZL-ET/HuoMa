package com.bookstore.qrcode.wecom;

import lombok.Getter;

/** 频率限制（45009）。等待 Retry-After 后可重试 1 次。 */
@Getter
public class WecomRateLimitException extends WecomApiException {
    private final int retryAfterSeconds;

    public WecomRateLimitException(int errcode, String errmsg, String body, int retryAfterSeconds) {
        super(errcode, errmsg, body);
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 5);
    }
}
