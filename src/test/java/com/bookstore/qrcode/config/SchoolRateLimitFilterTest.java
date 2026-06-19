package com.bookstore.qrcode.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SchoolRateLimitFilter 单元测试。
 * <p>验证滑动窗口限流逻辑：允许范围内的请求，拒绝超限的请求。</p>
 */
@DisplayName("SchoolRateLimitFilter 限流")
class SchoolRateLimitFilterTest {

    private final SchoolRateLimitFilter filter = new SchoolRateLimitFilter();

    @Test
    @DisplayName("允许首次 /s 请求通过")
    void shouldAllowFirstRequest() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/s"), resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("30 次请求全部应被允许")
    void shouldAllowUpTo30Requests() throws Exception {
        for (int i = 0; i < 30; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request("/s"), resp, chain);

            assertThat(resp.getStatus())
                    .as("请求第 %d 次应被允许", i + 1)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("第 31 次请求应返回 429")
    void shouldRejectRequestOver30() throws Exception {
        for (int i = 0; i < 30; i++) {
            filter.doFilter(request("/s"), new MockHttpServletResponse(), mock(FilterChain.class));
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/s"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("不应拦截非 /s 路径")
    void shouldSkipNonSchoolPath() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/download"), resp, chain);

        verify(chain).doFilter(any(), eq(resp));
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("不同 IP 应独立计数")
    void shouldCountPerIp() throws Exception {
        MockHttpServletRequest req1 = request("/s");
        req1.setRemoteAddr("10.0.0.1");
        MockHttpServletRequest req2 = request("/s");
        req2.setRemoteAddr("10.0.0.2");

        // IP1: 30次（满）
        for (int i = 0; i < 30; i++) {
            filter.doFilter(request("/s", "10.0.0.1"), new MockHttpServletResponse(), mock(FilterChain.class));
        }
        // IP1 第31次应被拒
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(request("/s", "10.0.0.1"), resp1, mock(FilterChain.class));
        assertThat(resp1.getStatus()).isEqualTo(429);

        // IP2 首次应被允许
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(request("/s", "10.0.0.2"), resp2, chain2);
        assertThat(resp2.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String path) {
        return request(path, "127.0.0.1");
    }

    private static MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        return req;
    }
}
