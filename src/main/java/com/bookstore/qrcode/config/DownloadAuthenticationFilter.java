package com.bookstore.qrcode.config;

import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.service.WecomOAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 下载中心 Session 认证过滤器。
 * <p>
 * 检查访问 /download/** 的请求是否持有有效的企微 OAuth Session。
 * 未认证的请求重定向到 OAuth 入口，已认证的放行。
 * OAuth 入口和回调路径跳过本过滤器。
 * 每次请求时重新验证员工活跃状态，防止已离职员工通过旧 Session 继续访问。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class DownloadAuthenticationFilter implements Filter {

    private final EmployeeRepository employeeRepo;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 只处理 /download/** 路径
        if (!path.startsWith("/download/")) {
            chain.doFilter(request, response);
            return;
        }

        // OAuth 入口和回调不需要认证
        if (path.startsWith("/download/oauth/")) {
            chain.doFilter(request, response);
            return;
        }

        // 检查 Session 中是否有 employeeUserid
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID) == null) {
            // 未认证：重定向到 OAuth 入口
            String entryUrl = req.getContextPath() + "/download/oauth/entry";
            resp.sendRedirect(entryUrl);
            return;
        }

        // 每次请求重新验证员工活跃状态，防止已离职员工通过旧 Session 继续访问
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid != null) {
            var employee = employeeRepo.findByUserid(userid).orElse(null);
            if (employee == null || !employee.getActive()) {
                session.removeAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
                session.removeAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);
                resp.sendRedirect(req.getContextPath() + "/download/oauth/entry");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
