package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * 管理后台 API — 不依赖页面路由的运维端点。
 */
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final TagService tagService;

    /**
     * 从企微同步全部标签到本地 DB，并刷新标签组缓存。
     *
     * <p>POST /admin/tags/sync —— 部署后一键运行，将旧标签的 group_keyword 从空串升级为正确的企微组名。
     *
     * @return JSON {@code {"skipped": N, "imported": N}}
     */
    @PostMapping("/admin/tags/sync")
    @ResponseBody
    public Map<String, Integer> syncTags() {
        return tagService.syncTagsFromWecom();
    }
}
