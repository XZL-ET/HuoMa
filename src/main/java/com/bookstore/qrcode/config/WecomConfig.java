package com.bookstore.qrcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "wecom")
public class WecomConfig {
    private String corpId;
    private String corpSecret;
    private String callbackToken;
    private String callbackEncodingAesKey;
    /** access_token 缓存，7200 秒，预留 200 秒缓冲 */
    private String accessToken;
    private long accessTokenExpireAt;
}
