package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/admin/form-templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;

    @Value("${upload.card-pic-dir:./data/uploads/card-pics}")
    private String cardPicDir;

    /** 保存上传的卡片图片，返回访问路径 */
    private String saveCardPic(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            Path dir = Path.of(cardPicDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String origName = file.getOriginalFilename();
            String ext = "";
            if (origName != null && origName.contains(".")) {
                ext = origName.substring(origName.lastIndexOf('.'));
            }
            String filename = UUID.randomUUID().toString().substring(0, 8) + ext;
            Path target = dir.resolve(filename);
            file.transferTo(target.toFile());
            log.info("Card pic saved: {}", target);
            return "/uploads/card-pics/" + filename;
        } catch (Exception e) {
            log.error("Failed to save card pic", e);
            throw new RuntimeException("图片上传失败: " + e.getMessage());
        }
    }

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
                         @RequestParam(required = false) String subtitle,
                         @RequestParam(required = false) String cardTitle,
                         @RequestParam(required = false) String cardDesc,
                         @RequestParam(required = false) MultipartFile cardPicFile,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            String cardPicUrl = saveCardPic(cardPicFile);
            templateService.create(name, description, subtitle, cardTitle, cardDesc, cardPicUrl,
                fields, tagMapping, remarkTemplate);
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
                         @RequestParam(required = false) String subtitle,
                         @RequestParam(required = false) String cardTitle,
                         @RequestParam(required = false) String cardDesc,
                         @RequestParam(required = false) MultipartFile cardPicFile,
                         @RequestParam(required = false) String existingCardPicUrl,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            String newPicUrl = saveCardPic(cardPicFile);
            // 新上传的图片优先；未上传则保留原有图片
            String cardPicUrl = (newPicUrl != null) ? newPicUrl : existingCardPicUrl;
            templateService.update(id, name, description, subtitle, cardTitle, cardDesc, cardPicUrl,
                fields, tagMapping, remarkTemplate);
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
