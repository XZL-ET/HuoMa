package com.bookstore.qrcode.wecom;

/** Token 过期（42001/40014）。重试前需刷新 token，最多重试 1 次。 */
public class WecomTokenExpiredException extends WecomApiException {
    public WecomTokenExpiredException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
