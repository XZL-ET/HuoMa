package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.ServiceTeacherDailyMaxService;
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
    private final ServiceTeacherDailyMaxService serviceTeacherDailyMaxService;

    /**
     * 配置列表页
     */
    @GetMapping
    public String index(Model model) {
        model.addAttribute("configs", configRepository.findAll());
        model.addAttribute("serviceTeacherDailyMax", serviceTeacherDailyMaxService.resolveDefault());
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

    /**
     * 服务老师日限：保存默认值，可选应用到全部服务老师。
     */
    @PostMapping("/service-teacher-daily-max")
    public String updateServiceTeacherDailyMax(@RequestParam int value,
                                               @RequestParam(required = false, defaultValue = "false") boolean apply,
                                               RedirectAttributes ra) {
        if (value <= 0 || value > ServiceTeacherDailyMaxService.MAX_DAILY_MAX) {
            ra.addFlashAttribute("error",
                    "日限必须在 1~" + ServiceTeacherDailyMaxService.MAX_DAILY_MAX + " 之间");
            return "redirect:/admin/system-config";
        }
        serviceTeacherDailyMaxService.saveDefault(value);
        if (apply) {
            int affected = serviceTeacherDailyMaxService.applyToAll(value);
            ra.addFlashAttribute("message",
                    "服务老师日限已设为 " + value + "，并应用到 " + affected + " 个服务老师");
        } else {
            ra.addFlashAttribute("message", "服务老师日限默认值已设为 " + value);
        }
        return "redirect:/admin/system-config";
    }
}
