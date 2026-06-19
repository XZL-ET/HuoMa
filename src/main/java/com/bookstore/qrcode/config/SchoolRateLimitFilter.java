package com.bookstore.qrcode.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学校自助查询 IP 频控过滤器。
 * <p>
 * 仅拦截 /s/** 路径，对同一 IP 做滑动窗口限流。
 * 后续可升级为 Redis 滑动窗口 + 图形验证码。
 * </p>
 */
@Slf4j
public class SchoolRateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000;
    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        String path = request.getRequestURI();
        if (!path.startsWith("/s")) {
            chain.doFilter(req, resp);
            return;
        }

        String ip = request.getRemoteAddr();
        SlidingWindow window = windows.computeIfAbsent(ip, k -> new SlidingWindow());

        synchronized (window) {
            long now = System.currentTimeMillis();
            window.prune(now);
            if (window.count >= MAX_REQUESTS_PER_MINUTE) {
                response.setStatus(429);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("请求过于频繁，请稍后再试");
                log.warn("Rate limit exceeded for IP: {}", ip);
                return;
            }
            window.hits[window.head] = now;
            window.head = (window.head + 1) % window.hits.length;
            window.count++;
        }

        chain.doFilter(req, resp);
    }

    private static class SlidingWindow {
        long[] hits = new long[MAX_REQUESTS_PER_MINUTE];
        int head = 0;
        int count = 0;

        void prune(long now) {
            long cutoff = now - WINDOW_MS;
            int newCount = 0;
            int tail = (head - count + hits.length) % hits.length;
            for (int i = 0; i < count; i++) {
                int idx = (tail + i) % hits.length;
                if (hits[idx] >= cutoff) {
                    hits[(head - newCount + hits.length) % hits.length] = hits[idx];
                    newCount++;
                }
            }
            count = newCount;
        }
    }
}
