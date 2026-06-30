package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.QrCodeGroupService;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrCodeGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class QrCodeGroupController {

    private final QrCodeGroupService groupService;
    private final FormTemplateService formTemplateService;
    private final QrCodeRepository qrCodeRepo;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 空字符串 → null
        String keywordParam = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        Page<QrCodeGroup> groupPage = groupService.search(keywordParam, PageRequest.of(page, 20));
        List<QrCodeGroup> groups = groupPage.getContent();

        // 仅加载当前页需要的活码（按 qrCodeId 批量查）
        Set<Long> qrCodeIds = groups.stream()
            .map(QrCodeGroup::getQrCodeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, QrCode> qrCodeMap = new LinkedHashMap<>();
        if (!qrCodeIds.isEmpty()) {
            List<QrCode> qrCodes = qrCodeRepo.findAllById(qrCodeIds);
            for (QrCode qr : qrCodes) {
                qrCodeMap.put(qr.getId(), qr);
            }
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

        // 分页页码范围
        int totalPages = groupPage.getTotalPages();
        int current = groupPage.getNumber();
        int pageStart = Math.max(0, current - 2);
        int pageEnd = Math.min(totalPages - 1, current + 2);
        List<Integer> pageNumbers = new ArrayList<>();
        for (int i = pageStart; i <= pageEnd; i++) {
            pageNumbers.add(i);
        }

        model.addAttribute("groups", groups);
        model.addAttribute("groupPage", groupPage);
        model.addAttribute("formTemplates", formTemplateService.listAll());
        model.addAttribute("qrCodeMap", qrCodeMap);
        model.addAttribute("schoolCounts", schoolCounts);
        model.addAttribute("keyword", keywordParam);
        model.addAttribute("pageNumbers", pageNumbers);
        model.addAttribute("pageStart", pageStart);
        model.addAttribute("pageEnd", pageEnd);
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
                         @RequestParam(required = false) String keyword,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes redirect) {
        try {
            groupService.create(name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId, qrCodeId, schoolList);
            redirect.addFlashAttribute("message", "联盟创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        if (keyword != null && !keyword.isBlank()) redirect.addAttribute("keyword", keyword);
        if (page > 0) redirect.addAttribute("page", page);
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
                         @RequestParam(required = false) String keyword,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes redirect) {
        try {
            groupService.update(id, name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId, qrCodeId, schoolList);
            redirect.addFlashAttribute("message", "联盟已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        if (keyword != null && !keyword.isBlank()) redirect.addAttribute("keyword", keyword);
        if (page > 0) redirect.addAttribute("page", page);
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String keyword,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes redirect) {
        try {
            groupService.delete(id);
            redirect.addFlashAttribute("message", "联盟已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        if (keyword != null && !keyword.isBlank()) redirect.addAttribute("keyword", keyword);
        if (page > 0) redirect.addAttribute("page", page);
        return "redirect:/admin/groups";
    }
}
