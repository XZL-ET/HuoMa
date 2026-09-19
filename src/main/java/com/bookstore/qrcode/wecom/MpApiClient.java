package com.bookstore.qrcode.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 微信公众号（服务号）网页授权客户端。
 * <p>
 * 与 {@link WecomApiClient}（企微 {@code qyapi.weixin.qq.com}）不同，本类走微信公众平台
 * （{@code open.weixin.qq.com} / {@code api.weixin.qq.com}），用于 snsapi_base 静默授权拿 openid，
 * 支撑「群发链接 → 网页授权 → openid 反查 external_userid」链路的验证。
 * </p>
 * <p>
 * <b>注意：</b>网页授权是「认证服务号」专属能力；appid/secret 为公众号（服务号）凭证，
 * 与企业微信 corpId/corpSecret 无关。
 * </p>
 *
 * @author Bookstore Dev
 */
@Slf4j
@Component
public class MpApiClient {

    /** 公众号网页授权入口 */
    private static final String AUTHORIZE_URL =
        "https://open.weixin.qq.com/connect/oauth2/authorize";
    /** 用 code 换取 openid */
    private static final String ACCESS_TOKEN_URL =
        "https://api.weixin.qq.com/sns/oauth2/access_token";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** 公众号（服务号）AppID */
    @Value("${wecom.mp-appid:}")
    private String mpAppid;

    /** 公众号（服务号）AppSecret */
    @Value("${wecom.mp-secret:}")
    private String mpSecret;

    public MpApiClient(@Value("${app.wecom.connect-timeout:3}") int connectTimeoutSec,
                       @Value("${app.wecom.read-timeout:10}") int readTimeoutSec,
                       RestTemplateBuilder builder,
                       ObjectMapper objectMapper) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(connectTimeoutSec))
            .setReadTimeout(Duration.ofSeconds(readTimeoutSec))
            .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 构造公众号 snsapi_base 静默授权 URL（不弹窗，任意微信用户点链接即可静默拿 openid）。
     *
     * @param redirectUri 回调地址（需配置在服务号「网页授权域名」下）
     * @param state       自定义参数，回调时原样返回
     * @return 完整授权 URL
     */
    public String buildOAuthUrl(String redirectUri, String state) {
        return AUTHORIZE_URL
            + "?appid=" + mpAppid
            + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&scope=snsapi_base"
            + "&state=" + state
            + "#wechat_redirect";
    }

    /**
     * 用授权回调的 code 换 openid。
     * <p>
     * 成功响应无 {@code errcode} 字段（含 openid）；失败响应含 {@code errcode}/{@code errmsg}。
     * </p>
     *
     * @param code 网页授权回调携带的临时 code
     * @return 公众号 openid
     * @throws WecomApiException 换取失败时抛出
     */
    public String getOpenidByCode(String code) {
        String url = ACCESS_TOKEN_URL
            + "?appid=" + mpAppid
            + "&secret=" + mpSecret
            + "&code=" + code
            + "&grant_type=authorization_code";
        try {
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(resp);
            if (node.has("errcode") && node.get("errcode").asInt() != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "未知错误";
                throw new WecomPermanentException(node.get("errcode").asInt(), errmsg, resp);
            }
            return node.has("openid") ? node.get("openid").asText() : null;
        } catch (WecomApiException e) {
            throw e;
        } catch (Exception e) {
            throw new WecomTransientException(-1,
                "公众号换 openid 失败: " + e.getMessage(), null);
        }
    }
}
