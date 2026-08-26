package com.bookstore.qrcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 企业微信（WeCom）配置属性类。
 * <p>
 * 自动绑定 {@code application.yml/properties} 中以 {@code wecom} 为前缀的配置项。
 * 包含企业微信应用的基础凭证（corpId / corpSecret）、回调 URL 验证参数
 * （callbackToken / callbackEncodingAesKey）以及
 * 运行时缓存的 access_token 及其过期时间。
 * </p>
 *
 * <p><b>配置示例 (application.yml)：</b>
 * <pre>
 * wecom:
 *   corp-id: wwxxxxxxxxxxxx
 *   corp-secret: xxxxxxxxxxxxxxxxxxxxx
 *   callback-token: myCallbackToken
 *   callback-encoding-aes-key: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 * </pre>
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wecom")
public class WecomConfig {

    /**
     * 企业微信企业 ID（CorpId）。
     * <p>
     * 每个企业微信账号拥有唯一的 CorpId，
     * 所有 API 请求均需携带此参数以标识企业身份。
     * 可在企业微信管理后台 "我的企业 > 企业信息" 中查看。
     * </p>
     */
    private String corpId;

    /**
     * 企业微信应用凭证密钥（CorpSecret）。
     * <p>
     * 用于获取 access_token，需严格保密，不可泄露。
     * 每个企业微信应用（自建/第三方）有独立的 CorpSecret。
     * 可在企业微信管理后台 "应用管理 > 应用 > 查看 Secret" 中获取。
     * </p>
     */
    private String corpSecret;

    /**
     * 回调 URL 验证 Token。
     * <p>
     * 用于验证回调 URL 的有效性，在企业微信管理后台配置回调地址时自定义设置。
     * 当企业微信向回调地址发送 GET 验证请求时，需要回填此 Token 进行签名校验。
     * </p>
     */
    private String callbackToken;

    /**
     * 回调消息加解密密钥（EncodingAESKey）。
     * <p>
     * 用于加解密回调消息体，长度固定为 43 个字符。
     * 企业微信推送的消息体使用 AES-256-CBC 加密，
     * 服务端需用此密钥解密后才能读取消息内容。
     * 响应回调时也需使用此密钥加密返回数据。
     * </p>
     */
    private String callbackEncodingAesKey;

    /**
     * 企业微信应用 AgentId。
     * <p>
     * 用于 {@code /cgi-bin/message/send} 应用消息推送（如每日转接对账推送给负责人）。
     * 可在企业微信管理后台「应用管理」中查看。未配置时应用消息推送不可用。
     * </p>
     */
    private Integer agentId;

    /**
     * 当前缓存的 access_token。
     * <p>
     * access_token 是企业微信 API 调用的全局唯一凭证，
     * 有效期默认 7200 秒（2 小时）。
     * 为避免在过期边界调用失败，实际使用时会在过期前 200 秒提前刷新。
     * 此字段运行时动态更新，不源自配置文件。
     * </p>
     */
    private String accessToken;

    /**
     * access_token 的过期时间戳（毫秒）。
     * <p>
     * 记录当前 access_token 的绝对过期时刻（System.currentTimeMillis），
     * 用于判断 token 是否已过期，配合 {@link #accessToken} 实现自动续期。
     * 此字段运行时动态更新，不源自配置文件。
     * </p>
     */
    private long accessTokenExpireAt;
}
