package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.DeletionGroupRow;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除记录页模板渲染冒烟测试：用真实的 Thymeleaf + SpringEL 渲染聚合表，
 * 校验展开行 id 与 onclick 索引一致（{@code gi} 是迭代状态对象，须取 {@code gi.index}）。
 * 布局用最小 stub 替换，避免依赖 Spring Security 方言。
 */
@DisplayName("deletion-records 模板聚合渲染")
class DeletionRecordTemplateRenderTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("多次删除渲染为一行并可展开，单次删除无展开行")
    void rendersAggregatedTable() throws Exception {
        Files.copy(Path.of("src/main/resources/templates/deletion-records.html"),
                dir.resolve("deletion-records.html"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve("layout.html"), """
                <!DOCTYPE html>
                <html xmlns:th="http://www.thymeleaf.org" th:fragment="layout(title, content)">
                <head><title th:text="${title}"></title></head>
                <body><th:block th:replace="${content}"></th:block></body>
                </html>
                """);

        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(dir + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 18, 15, 36);
        DeletionGroupRow repeated = new DeletionGroupRow("wm-c1", "agent1",
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, 2,
                now.minusHours(1), now, List.of(now, now.minusHours(1)));
        DeletionGroupRow single = new DeletionGroupRow("wm-c2", "agent1",
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, 1,
                now.minusDays(1), now.minusDays(1), List.of(now.minusDays(1)));

        JakartaServletWebApplication app =
                JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange, Locale.CHINA);
        ctx.setVariable("groups", List.of(repeated, single));
        ctx.setVariable("totalGroups", 2);
        ctx.setVariable("totalEvents", 3);
        ctx.setVariable("customerNameMap", Map.of("wm-c1", "张三", "wm-c2", "李四"));
        ctx.setVariable("employeeNameMap", Map.of("agent1", "王老师"));
        ctx.setVariable("employeeDeptMap", Map.of("agent1", "运营部"));
        ctx.setVariable("sourceMap", Map.of("wm-c1", "北京第一中学"));
        ctx.setVariable("deptSummary", Map.of("运营部", 2L));
        ctx.setVariable("range", "7d");
        ctx.setVariable("start", "2026-11-16");
        ctx.setVariable("end", "2026-11-22");
        ctx.setVariable("direction", "CUSTOMER_DELETED_AGENT");
        ctx.setVariable("keyword", "");
        ctx.setVariable("maxRows", 500);
        ctx.setVariable("todayRecipients", "");
        ctx.setVariable("todayRecipientNames", Map.of());

        String html = engine.process("deletion-records", ctx);

        assertThat(html).contains("共 <strong>2</strong> 组删除关系（<span>3</span> 条事件）");
        assertThat(html).contains("张三").contains("王老师").contains("运营部").contains("北京第一中学");
        assertThat(html).contains("onclick=\"toggleGroupDetail(0)\"");
        assertThat(html).contains("id=\"detail-0\"");
        assertThat(html).contains("×2");
        assertThat(html).contains("2026-11-22 18:15:36").contains("2026-11-22 17:15:36");
        assertThat(html).doesNotContain("detail-1");
    }
}
