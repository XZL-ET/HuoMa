package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.GradeTextbookCoverService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/admin/form-templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;
    private final SystemConfigRepository systemConfigRepo;
    private final GradeTextbookCoverService gradeTextbookCoverService;
    private final ObjectMapper objectMapper;

    @Value("${upload.card-pic-dir:./data/uploads/card-pics}")
    private String cardPicDir;

    @Value("${upload.grade-cover-dir:./data/uploads/grade-covers}")
    private String gradeCoverDir;

    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");

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
            String extKey = ext.length() > 1 ? ext.substring(1).toLowerCase() : "";
            if (!ALLOWED_EXT.contains(extKey)) {
                throw new RuntimeException("不支持的图片格式: " + ext);
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

    /** 保存上传的年级课本封面图，返回访问路径。 */
    private String saveGradeCover(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            Path dir = Path.of(gradeCoverDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String origName = file.getOriginalFilename();
            String ext = "";
            if (origName != null && origName.contains(".")) {
                ext = origName.substring(origName.lastIndexOf('.'));
            }
            String extKey = ext.length() > 1 ? ext.substring(1).toLowerCase() : "";
            if (!ALLOWED_EXT.contains(extKey)) {
                throw new RuntimeException("不支持的图片格式: " + ext);
            }
            String filename = UUID.randomUUID().toString().substring(0, 8) + ext;
            Path target = dir.resolve(filename);
            file.transferTo(target.toFile());
            log.info("Grade cover saved: {}", target);
            return "/uploads/grade-covers/" + filename;
        } catch (Exception e) {
            log.error("Failed to save grade cover", e);
            throw new RuntimeException("图片上传失败: " + e.getMessage());
        }
    }

    private String currentVersion() {
        SystemConfig cfg = systemConfigRepo.findById(FormTemplateService.VERSION_CONFIG_KEY).orElse(null);
        return (cfg != null && FormTemplateService.IMAGE_VERSION.equals(cfg.getConfigValue())) ? "image" : "text";
    }

    private String gradeImagesJson() {
        try {
            return objectMapper.writeValueAsString(gradeTextbookCoverService.listAsMap());
        } catch (Exception e) {
            log.warn("序列化年级封面图失败", e);
            return "{}";
        }
    }

    private String gradeOptionsJson() {
        try {
            return objectMapper.writeValueAsString(FormTemplateService.GRADE_OPTIONS);
        } catch (Exception e) {
            log.warn("序列化年级选项失败", e);
            return "[]";
        }
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", templateService.listAll());
        model.addAttribute("formVersion", currentVersion());
        model.addAttribute("gradeImagesJson", gradeImagesJson());
        model.addAttribute("gradeOptionsJson", gradeOptionsJson());
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

    /** 切换收集表单全局版本：text=原解析链，image=全站覆盖图片版。 */
    @PostMapping("/version")
    public String updateVersion(@RequestParam String version, RedirectAttributes redirect) {
        if (!FormTemplateService.IMAGE_VERSION.equals(version) && !"text".equals(version)) {
            redirect.addFlashAttribute("error", "非法版本: " + version);
            return "redirect:/admin/form-templates";
        }
        SystemConfig config = systemConfigRepo.findById(FormTemplateService.VERSION_CONFIG_KEY)
            .orElseGet(() -> {
                SystemConfig c = new SystemConfig();
                c.setConfigKey(FormTemplateService.VERSION_CONFIG_KEY);
                c.setConfigName("收集表单全局版本");
                return c;
            });
        config.setConfigValue(version);
        systemConfigRepo.save(config);
        redirect.addFlashAttribute("message",
            FormTemplateService.IMAGE_VERSION.equals(version) ? "已切换到图片版" : "已切换到文字版");
        return "redirect:/admin/form-templates";
    }

    /** 上传某年级语文课本封面图，写入 grade_textbook_cover 表并返回 URL。 */
    @PostMapping("/grade-cover")
    @ResponseBody
    public Map<String, Object> uploadGradeCover(@RequestParam String grade,
                                                @RequestParam MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String url = saveGradeCover(file);
            gradeTextbookCoverService.save(grade, url);
            result.put("success", true);
            result.put("url", url);
        } catch (Exception e) {
            log.error("年级封面图上传失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 删除某年级语文课本封面图。 */
    @PostMapping("/grade-cover/delete")
    @ResponseBody
    public Map<String, Object> deleteGradeCover(@RequestParam String grade) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            gradeTextbookCoverService.delete(grade);
            result.put("success", true);
        } catch (Exception e) {
            log.error("删除年级封面图失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
