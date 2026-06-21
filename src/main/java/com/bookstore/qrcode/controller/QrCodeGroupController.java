package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.QrCodeGroupService;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrCodeGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class QrCodeGroupController {

    private final QrCodeGroupService groupService;
    private final FormTemplateService formTemplateService;
    private final QrCodeRepository qrCodeRepo;

    @GetMapping
    public String list(Model model) {
        List<QrCodeGroup> groups = groupService.listAll();
        List<QrCode> allQrCodes = qrCodeRepo.findAll();

        // 活码 ID → 活码 快速查找
        Map<Long, QrCode> qrCodeMap = new LinkedHashMap<>();
        for (QrCode qr : allQrCodes) {
            qrCodeMap.put(qr.getId(), qr);
        }

        // 每个联盟的学校数量
        Map<Long, Integer> schoolCounts = new LinkedHashMap<>();
        for (QrCodeGroup g : groups) {
            if (g.getSchoolList() != null && !g.getSchoolList().isEmpty()) {
                schoolCounts.put(g.getId(), g.getSchoolList().split("\\R").length);
            } else {
                schoolCounts.put(g.getId(), 0);
            }
        }

        model.addAttribute("groups", groups);
        model.addAttribute("formTemplates", formTemplateService.listAll());
        model.addAttribute("allQrCodes", allQrCodes);
        model.addAttribute("qrCodeMap", qrCodeMap);
        model.addAttribute("schoolCounts", schoolCounts);
        return "admin/groups";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         @RequestParam(required = false) Long qrCodeId,
                         @RequestParam(required = false) String schoolList,
                         RedirectAttributes redirect) {
        try {
            groupService.create(name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId, qrCodeId, schoolList);
            redirect.addFlashAttribute("message", "联盟创建成功");
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
                         @RequestParam(required = false) Long qrCodeId,
                         @RequestParam(required = false) String schoolList,
                         RedirectAttributes redirect) {
        try {
            groupService.update(id, name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId, qrCodeId, schoolList);
            redirect.addFlashAttribute("message", "联盟已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            groupService.delete(id);
            redirect.addFlashAttribute("message", "联盟已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }
}
