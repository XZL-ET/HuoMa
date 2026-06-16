package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 登录控制器。
 * <p>
 * 仅负责渲染登录页面；登录表单的 POST 处理由 Spring Security 内置的
 * {@link org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter} 完成。
 * </p>
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        if (error != null) {
            model.addAttribute("loginError", "用户名或密码错误");
        }
        if (logout != null) {
            model.addAttribute("message", "已安全退出");
        }
        return "auth/login";
    }
}
