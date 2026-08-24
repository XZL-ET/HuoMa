package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学校管理后台控制器。
 * <p>
 * 提供学校主数据的 CRUD、筛选分页和 CSV 批量导入功能。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Controller
@RequestMapping("/admin/schools")
@RequiredArgsConstructor
public class AdminSchoolController {

    private final SchoolRepository schoolRepository;
    private final QrCodeRepository qrCodeRepository;
    private final QrAccessLogRepository qrAccessLogRepository;
    private final SchoolCategoryRepository categoryRepo;

    /** 列表页（按关键词/市州/区县/分类筛选分页） */
    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 空字符串转为 null，匹配 JPA Query 的 IS NULL 条件
        String keywordParam = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        String cityParam = (city != null && !city.isBlank()) ? city.trim() : null;
        String districtParam = (district != null && !district.isBlank()) ? district.trim() : null;

        Page<School> schools = schoolRepository.search(keywordParam, cityParam, districtParam, categoryId,
                PageRequest.of(page, 20));
        // 加载访问统计
        Map<Long, long[]> statsMap = new HashMap<>();
        List<Object[]> stats = qrAccessLogRepository.findSchoolAccessStats();
        for (Object[] row : stats) {
            statsMap.put(((Number) row[0]).longValue(),
                         new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        // 分类 id→name 映射（供表格显示）
        Map<Long, String> categoryMap = new HashMap<>();
        for (SchoolCategory cat : categoryRepo.findAllByOrderBySortOrderAscName()) {
            categoryMap.put(cat.getId(), cat.getName());
        }
        model.addAttribute("schools", schools);
        model.addAttribute("statsMap", statsMap);
        model.addAttribute("keyword", keywordParam);
        model.addAttribute("city", cityParam);
        model.addAttribute("district", districtParam);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("cities", schoolRepository.findDistinctCities());
        model.addAttribute("categories", categoryRepo.findAllByOrderBySortOrderAscName());
        model.addAttribute("categoryMap", categoryMap);

        // 计算分页页码范围（当前页前后各 2 页）
        int totalPages = schools.getTotalPages();
        int current = schools.getNumber();
        int pageStart = Math.max(0, current - 2);
        int pageEnd = Math.min(totalPages - 1, current + 2);
        List<Integer> pageNumbers = new ArrayList<>();
        for (int i = pageStart; i <= pageEnd; i++) {
            pageNumbers.add(i);
        }
        model.addAttribute("pageStart", pageStart);
        model.addAttribute("pageEnd", pageEnd);
        model.addAttribute("pageNumbers", pageNumbers);

        return "admin/schools";
    }

    /** 保存（新增/编辑） */
    @PostMapping("/save")
    public String save(@ModelAttribute School school,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(name = "filterCatId", required = false) Long filterCatId,
                       @RequestParam(defaultValue = "0") int page,
                       RedirectAttributes ra) {
        school.setDeleted(false);
        if (school.getHasQrcode() == null) {
            if (school.getId() != null) {
                schoolRepository.findById(school.getId()).ifPresent(existing ->
                        school.setHasQrcode(existing.getHasQrcode()));
            } else {
                school.setHasQrcode(false);
            }
        }
        schoolRepository.save(school);
        ra.addFlashAttribute("message", "保存成功");
        if (keyword != null && !keyword.isBlank()) ra.addAttribute("keyword", keyword);
        if (city != null && !city.isBlank()) ra.addAttribute("city", city);
        if (district != null && !district.isBlank()) ra.addAttribute("district", district);
        if (filterCatId != null) ra.addAttribute("categoryId", filterCatId);
        if (page > 0) ra.addAttribute("page", page);
        return "redirect:/admin/schools";
    }

    /** 软删除 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String keyword,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String district,
                         @RequestParam(name = "filterCatId", required = false) Long filterCatId,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes ra) {
        schoolRepository.findById(id).ifPresent(s -> {
            s.setDeleted(true);
            schoolRepository.save(s);
        });
        ra.addFlashAttribute("message", "已删除");
        if (keyword != null && !keyword.isBlank()) ra.addAttribute("keyword", keyword);
        if (city != null && !city.isBlank()) ra.addAttribute("city", city);
        if (district != null && !district.isBlank()) ra.addAttribute("district", district);
        if (filterCatId != null) ra.addAttribute("categoryId", filterCatId);
        if (page > 0) ra.addAttribute("page", page);
        return "redirect:/admin/schools";
    }

    /** 同步 has_qrcode 状态（从 qr_code 表同步到 school 表） */
    @PostMapping("/sync-status")
    public String syncStatus(RedirectAttributes ra) {
        int updated = schoolRepository.syncHasQrcodeFromQrCode();
        log.info("has_qrcode status synced: {} schools updated", updated);
        ra.addFlashAttribute("message", "已同步：更新了 " + updated + " 所学校的活码状态");
        return "redirect:/admin/schools";
    }

    /** 从 qr_code 表导入学校 */
    @PostMapping("/import-from-qrcode")
    public String importFromQrCode(RedirectAttributes ra) {
        int count = schoolRepository.importSchoolsFromQrCode();
        log.info("Imported {} schools from qr_code", count);
        ra.addFlashAttribute("message", "从活码导入了 " + count + " 所学校");
        // 导入后同步 has_qrcode 状态
        int synced = schoolRepository.syncHasQrcodeFromQrCode();
        if (synced > 0) {
            ra.addFlashAttribute("message", "从活码导入了 " + count + " 所学校，同步了 " + synced + " 所活码状态");
        }
        return "redirect:/admin/schools";
    }

    /** CSV 批量导入 */
    @PostMapping("/import")
    public String importCsv(@RequestParam("file") MultipartFile file,
                            RedirectAttributes ra) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            int count = 0;
            String line;
            String header = reader.readLine(); // skip header
            if (header != null) {
                String[] headerCols = header.split(",", -1);
                if (headerCols.length < 4) {
                    log.warn("CSV 表头列数不足: {} 列, 期望至少 4 列", headerCols.length);
                }
            }
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 4) continue;
                School school = School.builder()
                        .schoolId(cols[0].trim())
                        .schoolName(cols[1].trim())
                        .regionCity(cols[2].trim())
                        .regionDistrict(cols[3].trim())
                        .hasQrcode(false)
                        .deleted(false)
                        .build();
                // 可选第5列：分类名称
                if (cols.length >= 5 && !cols[4].trim().isEmpty()) {
                    categoryRepo.findByName(cols[4].trim())
                        .ifPresent(cat -> school.setCategoryId(cat.getId()));
                }
                schoolRepository.save(school);
                count++;
            }
            ra.addFlashAttribute("message", "成功导入 " + count + " 所学校");
        } catch (Exception e) {
            log.error("CSV 导入失败", e);
            ra.addFlashAttribute("error", "导入失败: " + e.getMessage());
        }
        return "redirect:/admin/schools";
    }
}
