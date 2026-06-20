package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.QrCodeGroupService;
import com.bookstore.qrcode.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class QrCodeGroupController {

    private final QrCodeGroupService groupService;
    private final FormTemplateService formTemplateService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("groups", groupService.listAll());
        model.addAttribute("formTemplates", formTemplateService.listAll());
        return "admin/groups";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            groupService.create(name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分组创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @RequestParam String name,
                         @RequestParam(required = false) String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            groupService.update(id, name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分组已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            groupService.delete(id);
            redirect.addFlashAttribute("message", "分组已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }
}
