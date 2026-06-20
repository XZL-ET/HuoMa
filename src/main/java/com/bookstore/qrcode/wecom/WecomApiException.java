package com.bookstore.qrcode.wecom;

import lombok.Getter;

/** 企微 API 异常基类 */
@Getter
public class WecomApiException extends RuntimeException {
    private final int errcode;
    private final String errmsg;
    private final String responseBody;

    public WecomApiException(int errcode, String errmsg, String responseBody) {
        super("企微 API 错误 [" + errcode + "]: " + errmsg);
        this.errcode = errcode;
        this.errmsg = errmsg;
        this.responseBody = responseBody;
    }
}
