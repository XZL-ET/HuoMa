package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/qrcodes")
@RequiredArgsConstructor
public class QrCodeController {

    private final QrCodeService qrCodeService;
    private final WecomApiClient wecomApiClient;

    /** 活码列表页 */
    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        QrCode.QrCodeStatus qrStatus = null;
        if (status != null && !status.isEmpty()) {
            try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }
        Page<QrCode> qrCodes = qrCodeService.search(keyword, city, district,
            qrStatus, PageRequest.of(page, size));
        model.addAttribute("qrCodes", qrCodes);
        model.addAttribute("keyword", keyword);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("status", status);
        return "qrcode/list";
    }

    /** 手动创建页 */
    @GetMapping("/create")
    public String createForm(Model model) {
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (result.has("errcode") && result.get("errcode").asInt() != 0) {
                model.addAttribute("loadError", "成员列表加载失败: " + result.get("errmsg").asText());
                model.addAttribute("userList", List.of());
            } else {
                List<Map<String, String>> userList = new ArrayList<>();
                for (JsonNode u : result.get("userlist")) {
                    userList.add(Map.of("userid", u.get("userid").asText(),
                                        "name", u.get("name").asText()));
                }
                model.addAttribute("userList", userList);
            }
        } catch (Exception e) {
            model.addAttribute("loadError", "成员列表加载失败: " + e.getMessage());
            model.addAttribute("userList", List.of());
        }
        return "qrcode/create";
    }

    /** 手动创建提交 */
    @PostMapping("/create")
    public String create(@ModelAttribute QrCodeCreateRequest req,
                          RedirectAttributes redirect) {
        try {
            qrCodeService.create(req);
            redirect.addFlashAttribute("message", "活码创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /** 批量导入页 */
    @GetMapping("/batch-import")
    public String batchImportForm() {
        return "qrcode/batch-import";
    }

    /** 批量导入提交 */
    @PostMapping("/batch-import")
    public String batchImport(@RequestParam("file") MultipartFile file,
                               RedirectAttributes redirect) {
        try {
            Map<String, Object> result = qrCodeService.batchImport(file);
            redirect.addFlashAttribute("importResult", result);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /** 活码详情页 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        QrCode qr = qrCodeService.getById(id);
        List<QrAgent> agents = qrCodeService.getAgents(id);
        model.addAttribute("qr", qr);
        model.addAttribute("receptionists",
            agents.stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                                   || a.getRole() == QrAgent.AgentRole.dual).toList());
        model.addAttribute("services",
            agents.stream().filter(a -> a.getRole() == QrAgent.AgentRole.service).toList());
        model.addAttribute("backups", qrCodeService.getBackups(id));
        return "qrcode/detail";
    }

    /** 删除活码 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            qrCodeService.delete(id);
            redirect.addFlashAttribute("message", "活码已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /** 切换轮换模式 */
    @PostMapping("/{id}/rotate-mode")
    public String updateRotateMode(@PathVariable Long id,
                                    @RequestParam String mode,
                                    RedirectAttributes redirect) {
        try {
            qrCodeService.updateRotateMode(id, QrCode.RotateMode.valueOf(mode));
            redirect.addFlashAttribute("message", "轮换模式已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 手动同步企微活码用户列表 */
    @PostMapping("/{id}/sync")
    public String syncQrUsers(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            qrCodeService.syncQrUsersToWechat(id);
            redirect.addFlashAttribute("message", "活码已同步 — 仅 active 员工在活码上");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 暂停/启启用活码 */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            QrCode qr = qrCodeService.getById(id);
            QrCode.QrCodeStatus newStatus = qr.getStatus() == QrCode.QrCodeStatus.active
                ? QrCode.QrCodeStatus.paused : QrCode.QrCodeStatus.active;
            qrCodeService.updateStatus(id, newStatus);
            redirect.addFlashAttribute("message",
                "活码已" + (newStatus == QrCode.QrCodeStatus.active ? "启用" : "暂停"));
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }
}
