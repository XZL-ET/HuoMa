package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 系统配置管理后台控制器。
 * <p>
 * 提供全局配置项（键值对）的查看和编辑功能。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Controller
@RequestMapping("/admin/system-config")
@RequiredArgsConstructor
public class AdminSystemConfigController {

    private final SystemConfigRepository configRepository;

    /**
     * 配置列表页
     */
    @GetMapping
    public String index(Model model) {
        model.addAttribute("configs", configRepository.findAll());
        return "admin/system-config";
    }

    /**
     * 保存配置项（新增 / 更新）
     */
    @PostMapping("/save")
    public String save(@RequestParam String configKey,
                       @RequestParam String configValue,
                       RedirectAttributes ra) {
        SystemConfig config = configRepository.findById(configKey)
                .orElse(new SystemConfig());
        config.setConfigKey(configKey);
        config.setConfigValue(configValue);
        configRepository.save(config);
        ra.addFlashAttribute("message", "配置已更新");
        return "redirect:/admin/system-config";
    }
}
