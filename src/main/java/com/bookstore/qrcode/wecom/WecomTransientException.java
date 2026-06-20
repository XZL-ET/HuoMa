package com.bookstore.qrcode.wecom;

/** 瞬时故障：网络超时、系统繁忙(-1)、5xx。可重试 3 次，指数退避。 */
public class WecomTransientException extends WecomApiException {
    public WecomTransientException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
