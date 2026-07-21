package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.TagService;
import com.bookstore.qrcode.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 管理后台 API — 不依赖页面路由的运维端点。
 */
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final TagService tagService;
    private final TransferService transferService;

    /**
     * 从企微同步全部标签到本地 DB，并刷新标签组缓存。
     *
     * <p>POST /admin/tags/sync —— 部署后一键运行，将旧标签的 group_keyword 从空串升级为正确的企微组名。
     */
    @PostMapping("/admin/tags/sync")
    public String syncTags(RedirectAttributes redirect) {
        Map<String, Integer> result = tagService.syncTagsFromWecom();
        redirect.addFlashAttribute("message",
            String.format("标签同步完成：导入 %d 个，跳过 %d 个", result.get("imported"), result.get("skipped")));
        return "redirect:/admin/system-config";
    }

    /**
     * 修复因表单字段名与备注模板占位符不匹配导致的损坏备注。
     *
     * <p>POST /admin/repair-remarks —— 从客户最近一次表单提交的 field_data
     * 重新构建备注并推送到企微。一次性运维操作，可重复执行（幂等）。
     */
    @PostMapping("/admin/repair-remarks")
    @ResponseBody
    public Map<String, Object> repairRemarks() {
        return transferService.repairBrokenRemarks();
    }
}
