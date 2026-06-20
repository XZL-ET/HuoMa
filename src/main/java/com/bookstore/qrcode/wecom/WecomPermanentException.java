package com.bookstore.qrcode.wecom;

/** 永久故障（40003/60011 等）。不可重试，直接进 DLQ。 */
public class WecomPermanentException extends WecomApiException {
    public WecomPermanentException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
