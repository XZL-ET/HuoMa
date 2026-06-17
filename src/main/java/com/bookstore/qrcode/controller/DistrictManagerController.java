package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DistrictManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台：区县负责人配置页面。
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Controller
@RequestMapping("/admin/district-managers")
@RequiredArgsConstructor
public class DistrictManagerController {

    private final DistrictManagerService districtManagerService;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;

    @GetMapping
    public String list(Model model) {
        List<DistrictManager> managers = districtManagerService.findAll();
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        // 员工列表（供新增/编辑弹窗选择负责人）
        List<Map<String, String>> employeeList = employeeRepo.findAllByActiveTrueOrderByName()
            .stream()
            .map(e -> Map.of("userid", e.getUserid(), "name", e.getName()))
            .collect(Collectors.toList());

        model.addAttribute("managers", managers);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        model.addAttribute("employeeList", employeeList);
        return "admin/district-managers";
    }

    @PostMapping("/create")
    public String create(@RequestParam String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam String managerUserid,
                         RedirectAttributes redirect) {
        try {
            districtManagerService.create(regionCity, regionDistrict, managerUserid);
            redirect.addFlashAttribute("message", "区县负责人已添加");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String managerUserid,
                         RedirectAttributes redirect) {
        try {
            districtManagerService.update(id, managerUserid);
            redirect.addFlashAttribute("message", "区县负责人已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            districtManagerService.delete(id);
            redirect.addFlashAttribute("message", "区县负责人已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }
}
