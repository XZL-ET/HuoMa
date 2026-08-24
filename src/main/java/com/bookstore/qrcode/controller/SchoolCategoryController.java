package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.SchoolCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学校分类管理控制器。
 * <p>
 * 提供分类的 CRUD 页面和操作，以及批量将学校归入分类的能力。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2026-06-26
 */
@Slf4j
@Controller
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class SchoolCategoryController {

    private final SchoolCategoryService categoryService;
    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;
    private final FormTemplateService formTemplateService;

    /** 分类列表页 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("categories", categoryRepo.findAllByOrderBySortOrderAscName());
        model.addAttribute("formTemplates", formTemplateService.listAll());
        // 模板 id→name 映射
        Map<Long, String> templateNameMap = new LinkedHashMap<>();
        for (var t : formTemplateService.listAll()) {
            templateNameMap.put(t.getId(), t.getName());
        }
        model.addAttribute("templateNameMap", templateNameMap);
        // 学校数统计
        List<Object[]> counts = schoolRepo.countSchoolsByCategory();
        Map<Long, Long> schoolCounts = new LinkedHashMap<>();
        for (Object[] row : counts) {
            schoolCounts.put((Long) row[0], (Long) row[1]);
        }
        model.addAttribute("schoolCounts", schoolCounts);
        return "admin/categories";
    }

    /** 创建分类 */
    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false, defaultValue = "0") Integer sortOrder,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            categoryService.create(name, sortOrder, defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分类创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    /** 更新分类 */
    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false, defaultValue = "0") Integer sortOrder,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            categoryService.update(id, name, sortOrder, defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分类已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    /** 删除分类 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            int linkedCount = categoryService.delete(id);
            if (linkedCount > 0) {
                redirect.addFlashAttribute("message",
                    "分类已删除，已清空 " + linkedCount + " 所学校的分类关联");
            } else {
                redirect.addFlashAttribute("message", "分类已删除");
            }
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    /** 批量将学校归入分类 */
    @PostMapping("/batch-assign")
    @ResponseBody
    public Map<String, Object> batchAssign(@RequestParam List<Long> schoolIds,
                                            @RequestParam(required = false) Long categoryId) {
        int n = schoolRepo.batchUpdateCategoryId(categoryId, schoolIds);
        return Map.of("ok", true, "count", n);
    }
}
