package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.GradeTagMigrationProgressRepository;
import com.bookstore.qrcode.service.GradeTagMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台：年级标签归并看板。
 *
 * <p>Phase A 约 25–40 分钟，不能挂在 HTTP 线程上同步跑，故 {@code /run-a} 立即返回、
 * 由 {@link Async} 后台执行；页面刷新看进度（进度表也只是观测，重跑=续跑）。</p>
 */
@Slf4j
@Controller
@RequestMapping("/admin/tag-migration")
@RequiredArgsConstructor
public class TagMigrationController {

    private final GradeTagMigrationService service;
    private final GradeTagMigrationProgressRepository progressRepo;

    @GetMapping
    public String dashboard(Model model) {
        var index = service.loadWecomTagIndex();
        model.addAttribute("deletePlan", service.planSchoolGroupGradeDeletions(index));
        model.addAttribute("verify", service.verify());

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("R1", progressRepo.countByRowClass("R1"));
        counts.put("R2", progressRepo.countByRowClass("R2"));
        counts.put("R3", progressRepo.countByRowClass("R3"));
        counts.put("review", progressRepo.countByRequiresReview(true));
        model.addAttribute("counts", counts);
        model.addAttribute("reviewList", progressRepo.findTop200ByRequiresReviewOrderByIdAsc(true));
        return "admin/tag-migration";
    }

    @PostMapping("/run-a")
    public String runA(@RequestParam(defaultValue = "false") boolean dryRun,
                       RedirectAttributes redirect) {
        if (dryRun) {
            var report = service.runPhaseA(true);
            redirect.addFlashAttribute("message", "Phase A dry-run: " + report);
        } else {
            service.runPhaseAAsync();
            redirect.addFlashAttribute("message", "Phase A 已后台启动，刷新本页看进度（约 25–40 分钟）");
        }
        return "redirect:/admin/tag-migration";
    }

    @PostMapping("/delete-school-tags")
    public String deleteSchoolTags(@RequestParam(defaultValue = "false") boolean confirmed,
                                   @RequestParam(defaultValue = "false") boolean probeOnly,
                                   RedirectAttributes redirect) {
        var index = service.loadWecomTagIndex();
        if (probeOnly) {
            var probe = service.probeSingleDeletion(index);
            if (probe == null) {
                redirect.addFlashAttribute("message", "待删清单为空，无需探针");
            } else {
                service.deleteSchoolGroupGradeTags(java.util.List.of(probe.wecomTagId()));
                redirect.addFlashAttribute("message", "已实测删除 1 个: [" + probe.groupName() + "]"
                    + probe.tagName() + " wid=" + probe.wecomTagId());
            }
            return "redirect:/admin/tag-migration";
        }
        if (!confirmed) {
            redirect.addFlashAttribute("error", "需勾选确认后再执行批量删除");
            return "redirect:/admin/tag-migration";
        }
        var plan = service.planSchoolGroupGradeDeletions(index);
        int n = service.deleteSchoolGroupGradeTags(plan.stream()
            .map(GradeTagMigrationService.DeletePlanEntry::wecomTagId).toList());
        redirect.addFlashAttribute("message", "已删除企微学校组年级标签 " + n + " 个");
        return "redirect:/admin/tag-migration";
    }

    @PostMapping("/purge-orphans")
    public String purgeOrphans(@RequestParam(defaultValue = "false") boolean dryRun,
                               @RequestParam(defaultValue = "false") boolean confirmed,
                               RedirectAttributes redirect) {
        if (!dryRun && !confirmed) {
            redirect.addFlashAttribute("error", "需勾选确认后再执行删除");
            return "redirect:/admin/tag-migration";
        }
        long n = service.purgeOrphanGradeTagRows(dryRun);
        redirect.addFlashAttribute("message", (dryRun ? "[dry-run] 可删" : "已删") + "孤儿 tag 行 " + n + " 行");
        return "redirect:/admin/tag-migration";
    }

    /** 供页面 fetch 轮询的轻量 JSON。 */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("R1", progressRepo.countByRowClass("R1"));
        m.put("R2", progressRepo.countByRowClass("R2"));
        m.put("R3", progressRepo.countByRowClass("R3"));
        m.put("review", progressRepo.countByRequiresReview(true));
        return m;
    }
}
