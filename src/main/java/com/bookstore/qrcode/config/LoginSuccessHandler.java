package com.bookstore.qrcode.config;

import com.bookstore.qrcode.service.OperationLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 登录成功处理器。
 * <p>
 * 在用户通过表单登录成功后，将「用户名 + 来源 IP + 时间」写入操作审计日志
 * （{@code operation_log}），用于后续追溯某次后台操作由哪个账号、从哪台机器发起。
 * 时间由 {@code OperationLog.createdAt} 自动填充。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OperationLogService operationLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String username = authentication.getName();
        operationLogService.log(username, "login", "user", username,
                "登录成功，来源 IP=" + resolveClientIp(request));
        response.sendRedirect(request.getContextPath() + "/");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
