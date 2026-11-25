package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.StrictDeptCleanupResult;
import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.service.DepartmentWhitelistService;
import com.bookstore.qrcode.service.StrictDeptCleanupService;
import com.bookstore.qrcode.service.StrictDeptConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 严格同部门清退后台：开关、只读预览、确认执行与漂移统计。
 *
 * <p>清退是破坏性操作，整组路由在 {@code SecurityConfig} 中限定为 ADMIN
 * （{@code /strict-dept/**}）。预览为实时快照，apply 时会按当时库状态重新计算。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-25
 */
@Slf4j
@Controller
@RequestMapping("/strict-dept")
@RequiredArgsConstructor
public class StrictDeptController {

    private final StrictDeptCleanupService cleanupService;
    private final StrictDeptConfigService strictDeptConfigService;
    private final PoolAllowedDepartmentRepository whitelistRepo;

    /**
     * GET {@code /strict-dept} — 清退页。
     *
     * <p>展示开关、豁免名单、漂移统计与区县过滤选项。漂移统计或区县白名单读取失败时降级，
     * 页面本身仍可访问。</p>
     */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("title", "严格同部门清退");
        model.addAttribute("view", "strict-dept");
        model.addAttribute("strictModeEnabled", strictDeptConfigService.isStrictModeEnabled());
        Set<String> exemptUserids = strictDeptConfigService.getExemptUserids();
        model.addAttribute("exemptUseridsText",
                String.join("\n", exemptUserids.stream().sorted().toList()));

        List<PoolAllowedDepartment> countyOptions;
        try {
            countyOptions = whitelistRepo.findAllByOrderByDeptIdAsc();
        } catch (RuntimeException e) {
            log.warn("清退页：区县白名单读取失败", e);
            countyOptions = List.of();
        }
        model.addAttribute("countyOptions", countyOptions);

        long drifted = 0L;
        try {
            drifted = cleanupService.countDriftedActiveReceptionists();
        } catch (RuntimeException e) {
            log.warn("清退页：漂移接待员统计失败", e);
            model.addAttribute("error", "漂移接待员统计失败，请稍后重试");
        }
        model.addAttribute("driftedReceptionistCount", drifted);
        return "strict-dept";
    }

    /**
     * POST {@code /strict-dept/preview} — 只读预览清退影响面（JSON）。
     *
     * @param deptIds 可选的根部门过滤（含子部门）；为空表示全部活码
     */
    @PostMapping("/preview")
    @ResponseBody
    public ResponseEntity<?> preview(
            @RequestParam(name = "deptIds", required = false) List<Long> deptIds) {
        Set<Long> filter = deptIds == null ? null : new LinkedHashSet<>(deptIds);
        try {
            return ResponseEntity.ok(cleanupService.preview(filter));
        } catch (DepartmentWhitelistService.DeptTreeUnavailableException e) {
            log.warn("严格同部门预览：部门树拉取失败", e);
            return ResponseEntity.status(409)
                .body(Map.of("error", "部门树拉取失败，请稍后重试"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "参数不合法"));
        } catch (RuntimeException e) {
            log.error("严格同部门预览失败", e);
            return ResponseEntity.status(500)
                .body(Map.of("error", "预览失败，请稍后重试"));
        }
    }

    /**
     * POST {@code /strict-dept/apply} — 确认执行清退。
     *
     * @param deptIds 可选的根部门过滤（含子部门）；为空表示全部活码
     */
    @PostMapping("/apply")
    public String apply(
            @RequestParam(name = "deptIds", required = false) List<Long> deptIds,
            RedirectAttributes redirect) {
        Set<Long> filter = deptIds == null ? null : new LinkedHashSet<>(deptIds);
        try {
            StrictDeptCleanupResult result = cleanupService.apply(filter, currentOperator());
            String message = String.format(
                "严格同部门清退已执行：处理 %d 码，跳过 %d 码，撤 active %d 人、full %d 人，补位 %d 人",
                result.codesProcessed(), result.codesSkipped(),
                result.activeRemoved(), result.fullRemoved(), result.backfilled());
            redirect.addFlashAttribute("message", message);
            if (result.failures() != null && !result.failures().isEmpty()) {
                redirect.addFlashAttribute("applyFailures", result.failures());
            }
        } catch (DepartmentWhitelistService.DeptTreeUnavailableException e) {
            log.warn("严格同部门清退：部门树不可用", e);
            redirect.addFlashAttribute("error",
                e.getMessage() != null ? e.getMessage() : "部门树拉取失败，请稍后重试");
        } catch (IllegalStateException e) {
            // 全局/轮换锁占用：原样透出「稍后再试」类消息
            log.warn("严格同部门清退：任务锁占用", e);
            redirect.addFlashAttribute("error",
                e.getMessage() != null ? e.getMessage() : "另一个清退任务正在进行，请稍后重试");
        } catch (RuntimeException e) {
            // 非预期异常不透出原始信息，避免泄漏内部细节
            log.error("严格同部门清退失败", e);
            redirect.addFlashAttribute("error", "操作失败，请稍后重试");
        }
        return "redirect:/strict-dept";
    }

    /**
     * POST {@code /strict-dept/toggle} — 切换严格同部门取人开关。
     */
    @PostMapping("/toggle")
    public String toggle(@RequestParam boolean enabled, RedirectAttributes redirect) {
        try {
            strictDeptConfigService.setStrictModeEnabled(enabled);
            redirect.addFlashAttribute("message",
                enabled ? "严格同部门取人已开启" : "严格同部门取人已关闭");
        } catch (RuntimeException e) {
            log.warn("严格同部门开关写入失败", e);
            redirect.addFlashAttribute("error", "开关保存失败，请稍后重试");
        }
        return "redirect:/strict-dept";
    }

    /**
     * POST {@code /strict-dept/exempt-userids} — 保存豁免服务老师名单。
     *
     * <p>接受换行、逗号（中英文）、分号或空白分隔；去空行、去首尾空白、按首次出现顺序去重。
     * 提交空内容表示清空名单（不再豁免任何人）。</p>
     */
    @PostMapping("/exempt-userids")
    public String updateExemptUserids(
            @RequestParam(name = "userids", required = false) String userids,
            RedirectAttributes redirect) {
        List<String> parsed = userids == null ? List.of()
            : Arrays.stream(userids.split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        try {
            strictDeptConfigService.setExemptUserids(parsed);
            redirect.addFlashAttribute("message", parsed.isEmpty()
                ? "豁免名单已清空"
                : "豁免名单已更新，共 " + parsed.size() + " 人");
        } catch (RuntimeException e) {
            log.warn("严格同部门豁免名单写入失败", e);
            redirect.addFlashAttribute("error", "豁免名单保存失败，请稍后重试");
        }
        return "redirect:/strict-dept";
    }

    /** 当前登录操作人；无认证上下文（如单测）回退为 {@code system}。 */
    private String currentOperator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "system";
    }
}
