package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/form-templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", templateService.listAll());
        return "admin/form-templates";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("template", null);
        return "admin/form-template-edit";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            templateService.create(name, description, fields, tagMapping, remarkTemplate);
            redirect.addFlashAttribute("message", "模板创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("template", templateService.getById(id));
        return "admin/form-template-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            templateService.update(id, name, description, fields, tagMapping, remarkTemplate);
            redirect.addFlashAttribute("message", "模板已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            templateService.delete(id);
            redirect.addFlashAttribute("message", "模板已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }
}
