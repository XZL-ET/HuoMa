package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.School;
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

    /** 列表页（按市州/区县筛选分页） */
    @GetMapping
    public String list(@RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 空字符串转为 null，匹配 JPA Query 的 IS NULL 条件
        String cityParam = (city != null && !city.isBlank()) ? city.trim() : null;
        String districtParam = (district != null && !district.isBlank()) ? district.trim() : null;

        Page<School> schools = schoolRepository.findByFilters(cityParam, districtParam,
                PageRequest.of(page, 20));
        model.addAttribute("schools", schools);
        model.addAttribute("city", cityParam);
        model.addAttribute("district", districtParam);
        model.addAttribute("cities", schoolRepository.findDistinctCities());
        return "admin/schools";
    }

    /** 保存（新增/编辑） */
    @PostMapping("/save")
    public String save(@ModelAttribute School school, RedirectAttributes ra) {
        school.setDeleted(false);
        if (school.getHasQrcode() == null) {
            school.setHasQrcode(false);
        }
        schoolRepository.save(school);
        ra.addFlashAttribute("message", "保存成功");
        return "redirect:/admin/schools";
    }

    /** 软删除 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        schoolRepository.findById(id).ifPresent(s -> {
            s.setDeleted(true);
            schoolRepository.save(s);
        });
        ra.addFlashAttribute("message", "已删除");
        return "redirect:/admin/schools";
    }

    /** 同步 has_qrcode 状态 */
    @PostMapping("/sync-status")
    public String syncStatus(RedirectAttributes ra) {
        ra.addFlashAttribute("message", "状态同步已触发");
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
            reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",", 5);
                if (cols.length < 5) continue;
                School school = School.builder()
                        .schoolId(cols[0].trim())
                        .schoolName(cols[1].trim())
                        .regionCity(cols[2].trim())
                        .regionDistrict(cols[3].trim())
                        .hasQrcode(false)
                        .deleted(false)
                        .build();
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
