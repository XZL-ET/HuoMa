package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.job.DeletionReportJob;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.DeletionReportService;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.ServiceTeacherDailyMaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.stream.Collectors;

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
    private final DeletionReportService deletionReportService;
    private final DeletionReportJob deletionReportJob;
    private final FormTemplateService formTemplateService;

    /**
     * 配置列表页
     */
    @GetMapping
    public String index(Model model) {
        model.addAttribute("configs", configRepository.findAll());
        model.addAllAttributes(formTemplateService.resolveImageCopy());
        model.addAttribute("serviceTeacherDailyMax", serviceTeacherDailyMaxService.resolveDefault());
        model.addAttribute("deletionReportRecipients", deletionReportService.getEffectiveRecipients());
        model.addAttribute("deletionReportTime",
                deletionReportService.resolvePushTime().format(DateTimeFormatter.ofPattern("HH:mm")));
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
     * 客户删除员工日报接收人：保存逗号分隔的企微 userid 列表。
     */
    @PostMapping("/deletion-report-recipients")
    public String updateDeletionReportRecipients(@RequestParam String recipients,
                                                 RedirectAttributes ra) {
        String normalized = Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
        SystemConfig config = configRepository.findByConfigKey(DeletionReportService.RECIPIENTS_CONFIG_KEY)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(DeletionReportService.RECIPIENTS_CONFIG_KEY);
                    c.setConfigName("客户删除员工日报接收人（企微 userid，逗号分隔）");
                    return c;
                });
        config.setConfigValue(normalized);
        configRepository.save(config);
        ra.addFlashAttribute("message", "日报接收人已更新");
        return "redirect:/admin/system-config";
    }

    /**
     * 手动推送昨日客户删除员工日报（与每日定时任务同一口径）。
     */
    @PostMapping("/deletion-report/push")
    public String pushDeletionReport(RedirectAttributes ra) {
        int sent = deletionReportService.reportWithLock(
                LocalDate.now(DeletionReportService.REPORT_ZONE).minusDays(1));
        if (sent == DeletionReportService.LOCK_BUSY) {
            ra.addFlashAttribute("message", "日报推送正在进行中，请稍后重试");
        } else if (sent > 0) {
            ra.addFlashAttribute("message", "日报已推送，成功发送给 " + sent + " 位管理员");
        } else {
            ra.addFlashAttribute("message", "日报未推送：昨日无删除事件，或未配置接收人");
        }
        return "redirect:/admin/system-config";
    }

    /**
     * 客户删除员工日报推送时间：保存 HH:mm 并即时重新排程。
     */
    @PostMapping("/deletion-report-time")
    public String updateDeletionReportTime(@RequestParam String time, RedirectAttributes ra) {
        LocalTime parsed;
        try {
            parsed = LocalTime.parse(time.trim());
        } catch (DateTimeParseException e) {
            ra.addFlashAttribute("error", "日报推送时间格式不正确，请使用 HH:mm");
            return "redirect:/admin/system-config";
        }
        String normalized = parsed.format(DateTimeFormatter.ofPattern("HH:mm"));
        SystemConfig config = configRepository.findByConfigKey(DeletionReportService.TIME_CONFIG_KEY)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(DeletionReportService.TIME_CONFIG_KEY);
                    c.setConfigName("客户删除员工日报推送时间（HH:mm）");
                    return c;
                });
        config.setConfigValue(normalized);
        configRepository.save(config);
        deletionReportJob.reschedule(parsed);
        ra.addFlashAttribute("message", "日报推送时间已设为 " + normalized + "，每日定时推送");
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

    /**
     * 图片版表单文案：保存 6 项到 system_config，空值也写入（读取时回退默认值）。
     */
    @PostMapping("/image-copy")
    public String updateImageCopy(@RequestParam(required = false) String headingLine1,
                                  @RequestParam(required = false) String headingLine2,
                                  @RequestParam(required = false) String subtitle,
                                  @RequestParam(required = false) String gradeHint,
                                  @RequestParam(required = false) String buttonText,
                                  @RequestParam(required = false) String privacyNotice,
                                  RedirectAttributes ra) {
        saveConfig(FormTemplateService.IMAGE_HEADING_LINE1_KEY, "图片版学校行第一行", headingLine1);
        saveConfig(FormTemplateService.IMAGE_HEADING_LINE2_KEY, "图片版学校行第二行", headingLine2);
        saveConfig(FormTemplateService.IMAGE_SUBTITLE_KEY, "图片版副标题", subtitle);
        saveConfig(FormTemplateService.IMAGE_GRADE_HINT_KEY, "图片版封面下提示", gradeHint);
        saveConfig(FormTemplateService.IMAGE_BUTTON_TEXT_KEY, "图片版按钮文字", buttonText);
        saveConfig(FormTemplateService.PRIVACY_NOTICE_KEY, "隐私声明", privacyNotice);
        ra.addFlashAttribute("message", "图片版文案已更新");
        return "redirect:/admin/system-config";
    }

    private void saveConfig(String key, String name, String value) {
        SystemConfig config = configRepository.findByConfigKey(key)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(key);
                    c.setConfigName(name);
                    return c;
                });
        config.setConfigValue(value == null ? "" : value);
        configRepository.save(config);
    }
}
