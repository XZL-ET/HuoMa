package com.bookstore.qrcode.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页与全局 API 控制器。
 * <p>
 * 处理根路径重定向，以及全局在职继承自动开关等无业务前缀的 API。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final StringRedisTemplate redisTemplate;

    /**
     * GET {@code /}
     * <p>
     * 访问根路径时自动重定向到活码管理列表页，作为应用的默认入口。
     * </p>
     *
     * @return 重定向到 {@code /qrcodes}
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/qrcodes";
    }

    // ================================================================
    //  自动在职继承开关（全局）
    // ================================================================

    /**
     * 查询自动在职继承开关状态。
     * <p>GET /api/inheritance/auto/status → {"enabled": true/false}</p>
     */
    @GetMapping("/api/inheritance/auto/status")
    @ResponseBody
    public Map<String, Object> inheritanceAutoStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        String val = redisTemplate.opsForValue()
            .get(com.bookstore.qrcode.job.InheritanceJob.AUTO_ENABLED_KEY);
        result.put("enabled", val == null || !"false".equals(val));
        return result;
    }

    /**
     * 切换自动在职继承开关。
     * <p>POST /api/inheritance/auto/toggle → {"enabled": true/false}</p>
     */
    @PostMapping("/api/inheritance/auto/toggle")
    @ResponseBody
    public Map<String, Object> inheritanceAutoToggle() {
        Map<String, Object> result = new LinkedHashMap<>();
        String val = redisTemplate.opsForValue()
            .get(com.bookstore.qrcode.job.InheritanceJob.AUTO_ENABLED_KEY);
        boolean currentlyEnabled = val == null || !"false".equals(val);
        boolean newState = !currentlyEnabled;
        redisTemplate.opsForValue().set(
            com.bookstore.qrcode.job.InheritanceJob.AUTO_ENABLED_KEY,
            String.valueOf(newState));
        result.put("enabled", newState);
        return result;
    }
}
