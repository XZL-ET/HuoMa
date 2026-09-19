package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.wecom.MpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 公众号网页授权（snsapi_base）拿 openid 的最小验证页（spike 临时物，验证后删除）。
 * <p>
 * 用途：验证 openid 反查链路 A 部分——家长在微信里点链接能否静默拿到 openid。
 * 用于和 {@code convert_to_openid} 返回的 openid 比对（B 部分）。
 * </p>
 *
 * @author Bookstore Dev
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MpOauthTestController {

    private final MpApiClient mpApi;

    @Value("${app.base-url:https://huoma.gsxhsd.com}")
    private String baseUrl;

    /** 入口：跳转公众号 snsapi_base 授权 */
    @GetMapping("/mp/test/oauth")
    public String start() {
        return "redirect:" + mpApi.buildOAuthUrl(baseUrl + "/mp/test/callback", "mp-test");
    }

    /** 回调：用 code 换 openid 并回显 */
    @GetMapping("/mp/test/callback")
    public String callback(@RequestParam(value = "code", required = false) String code,
                           @RequestParam(value = "state", required = false) String state,
                           Model model) {
        if (code == null || code.isBlank()) {
            model.addAttribute("error", "未获取到 code，请从微信内重新打开测试链接");
            return "form/mp-openid-test";
        }
        try {
            model.addAttribute("openid", mpApi.getOpenidByCode(code));
        } catch (Exception e) {
            log.error("公众号换 openid 失败", e);
            model.addAttribute("error", e.getMessage());
        }
        return "form/mp-openid-test";
    }
}
