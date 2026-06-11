package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页页面控制器。
 * <p>
 * 处理根路径请求，将用户重定向到活码管理页面 {@code /qrcodes}。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
public class HomeController {

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
}
