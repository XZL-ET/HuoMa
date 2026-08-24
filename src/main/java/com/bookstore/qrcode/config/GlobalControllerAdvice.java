package com.bookstore.qrcode.config;

import com.bookstore.qrcode.service.WecomOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.NoSuchElementException;

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
     * <p>优先使用 Spring Security 认证（管理后台），
     * 若无认证则回退读取企微 OAuth Session（下载中心）。</p>
     *
     * @param session HTTP Session，用于读取下载中心 OAuth 用户名
     * @return 当前用户名，未登录时返回 null
     */
    @ModelAttribute("currentUser")
    public String currentUser(HttpSession session) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        // 下载中心 OAuth Session 回退
        if (session != null) {
            Object name = session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);
            if (name != null) return name.toString();
        }
        return null;
    }

    /**
     * 处理学校自助查询中不存在的学校 ID 请求。
     * <p>返回友好的 404 页面而非 500 错误。</p>
     */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView handleNotFound(NoSuchElementException ex) {
        ModelAndView mav = new ModelAndView("school/not-found");
        mav.addObject("message", ex.getMessage());
        return mav;
    }

    /**
     * 处理表单绑定 / 参数校验失败（MethodArgumentNotValidException 与 BindException）。
     * <p>返回友好错误而非 500 裸奔：AJAX 请求返回 JSON，普通表单请求重定向回列表页
     * 并携带 flash 错误信息。</p>
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Object handleValidation(BindException ex, HttpServletRequest request, RedirectAttributes ra) {
        String msg = ex.getBindingResult().getAllErrors().stream()
            .findFirst()
            .map(e -> e.getDefaultMessage())
            .orElse("请求参数不合法");
        boolean isAjax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        if (isAjax) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
        }
        ra.addFlashAttribute("error", msg);
        return "redirect:/qrcodes";
    }
}
