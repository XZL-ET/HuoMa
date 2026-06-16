package com.bookstore.qrcode.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局控制器增强。
 * <p>
 * 向所有 Thymeleaf 视图暴露当前登录用户名（${currentUser}），
 * 供导航栏显示用户信息和退出按钮。
 * </p>
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    /**
     * 向所有视图暴露当前登录用户名。
     *
     * @return 当前用户名，未登录时返回 null
     */
    @ModelAttribute("currentUser")
    public String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return null;
    }
}
