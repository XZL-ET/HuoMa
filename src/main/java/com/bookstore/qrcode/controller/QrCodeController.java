package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrBackupPool;
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

import java.util.*;

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
        List<QrBackupPool> backups = qrCodeService.getBackups(id);
        model.addAttribute("backups", backups);

        // 加载企业全部员工（供后备新增弹窗使用）
        Map<String, String> agentNameMap = new HashMap<>();
        List<Map<String, String>> userList = new ArrayList<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (result.has("errcode") && result.get("errcode").asInt() != 0) {
                model.addAttribute("loadError", "成员列表加载失败: " + result.get("errmsg").asText());
            } else {
                for (JsonNode u : result.get("userlist")) {
                    String userid = u.get("userid").asText();
                    String name = u.get("name").asText();
                    userList.add(Map.of("userid", userid, "name", name));
                    agentNameMap.put(userid, name);
                }
            }
        } catch (Exception e) {
            model.addAttribute("loadError", "成员列表加载失败: " + e.getMessage());
        }
        model.addAttribute("userList", userList);
        model.addAttribute("agentNameMap", agentNameMap);

        // 已在活码中的 userid 列表（供"新增联系人"弹窗过滤用）
        List<String> contactUserids = agents.stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .map(QrAgent::getAgentUserid)
            .distinct()
            .toList();
        model.addAttribute("contactUserids", contactUserids);

        return "qrcode/detail";
    }

    /** 添加后备接待员 */
    @PostMapping("/{id}/backups")
    public String addBackup(@PathVariable Long id,
                            @RequestParam String agentUserid,
                            RedirectAttributes redirect) {
        try {
            qrCodeService.addBackup(id, agentUserid);
            redirect.addFlashAttribute("message", "后备接待员已添加: " + agentUserid);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 新增活码联系人 */
    @PostMapping("/{id}/agents")
    public String addAgent(@PathVariable Long id,
                           @RequestParam String agentUserid,
                           RedirectAttributes redirect) {
        try {
            qrCodeService.addAgent(id, agentUserid);
            redirect.addFlashAttribute("message", "联系人已添加: " + agentUserid);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 编辑活码联系人 */
    @PostMapping("/{id}/agents/{agentId}/update")
    public String updateAgent(@PathVariable Long id,
                              @PathVariable Long agentId,
                              @RequestParam(required = false) Integer dailyMax,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) Integer sortOrder,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.updateAgent(id, agentId, dailyMax, role, sortOrder);
            redirect.addFlashAttribute("message", "联系人已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 移除活码联系人 */
    @PostMapping("/{id}/agents/{agentId}/remove")
    public String removeAgent(@PathVariable Long id,
                              @PathVariable Long agentId,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.removeAgent(id, agentId);
            redirect.addFlashAttribute("message", "联系人已移除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 移除后备接待员 */
    @PostMapping("/{id}/backups/{backupId}/remove")
    public String removeBackup(@PathVariable Long id,
                               @PathVariable Long backupId,
                               RedirectAttributes redirect) {
        try {
            qrCodeService.removeBackup(id, backupId);
            redirect.addFlashAttribute("message", "后备接待员已移除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 调整后备接待员优先级 */
    @PostMapping("/{id}/backups/{backupId}/move")
    public String moveBackup(@PathVariable Long id,
                             @PathVariable Long backupId,
                             @RequestParam String direction,
                             RedirectAttributes redirect) {
        try {
            qrCodeService.moveBackup(id, backupId, direction);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
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
