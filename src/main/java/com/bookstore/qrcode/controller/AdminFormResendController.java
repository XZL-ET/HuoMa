package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.FormResendService;
import com.bookstore.qrcode.service.PaperLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 管理后台「补发表单」控制器。
 * <p>
 * 提供未打标签家长筛选预览、一键发起群发、群发任务进度查看、
 * 以及「年级 × 科目 → 试题链接」映射的增删改查。
 * </p>
 *
 * @author Bookstore Dev
 */
@Controller
@RequestMapping("/admin/form-resend")
@RequiredArgsConstructor
public class AdminFormResendController {

    private final FormResendService formResendService;
    private final PaperLinkService paperLinkService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("untaggedCount", formResendService.countUntagged());
        model.addAttribute("untaggedByAgent", formResendService.untaggedByAgent());
        model.addAttribute("tasks", formResendService.listTasks());
        model.addAttribute("paperLinks", paperLinkService.listAll());
        return "admin/form-resend";
    }

    @PostMapping("/initiate")
    public String initiate(@RequestParam(required = false) String agentUserid,
                           RedirectAttributes ra) {
        try {
            Map<String, Object> r = formResendService.initiateResend(agentUserid);
            ra.addFlashAttribute("message", String.format(
                "群发已发起：覆盖 %s 人、%s 个任务、失败 %s 个、催办 %s 名员工",
                r.get("total"), r.get("tasks"), r.get("failed"), r.get("agents")));
        } catch (Exception e) {
            ra.addFlashAttribute("error", "发起失败：" + e.getMessage());
        }
        return "redirect:/admin/form-resend";
    }

    @PostMapping("/paper-link/save")
    public String savePaperLink(@RequestParam String grade,
                                @RequestParam String subject,
                                @RequestParam String title,
                                @RequestParam String paperUrl,
                                RedirectAttributes ra) {
        paperLinkService.save(grade, subject, title, paperUrl);
        ra.addFlashAttribute("message", "试题链接已保存");
        return "redirect:/admin/form-resend";
    }

    @PostMapping("/paper-link/delete")
    public String deletePaperLink(@RequestParam Long id, RedirectAttributes ra) {
        paperLinkService.delete(id);
        ra.addFlashAttribute("message", "试题链接已删除");
        return "redirect:/admin/form-resend";
    }
}
