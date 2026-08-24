package com.bookstore.qrcode.util;

import java.net.URI;
import java.util.Set;

/** 企微图片 CDN 域名白名单，用于服务端图片代理的 SSRF 防护。 */
public final class QrUrlAllowlist {

    private static final Set<String> WECOM_QR_HOSTS =
        Set.of("wework.qpic.cn", "open.work.weixin.qq.com");

    private QrUrlAllowlist() {}

    public static boolean isAllowedQrUrl(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme())
                && host != null
                && WECOM_QR_HOSTS.stream().anyMatch(h -> h.equalsIgnoreCase(host));
        } catch (Exception e) {
            return false;
        }
    }
}
