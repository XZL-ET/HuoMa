package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrBackupPool;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrBackupPoolRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.service.QrImageService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Controller
@RequestMapping("/qrcodes")
@RequiredArgsConstructor
public class QrCodeController {

    private final QrCodeService qrCodeService;
    private final WecomApiClient wecomApiClient;
    private final QrAgentRepository qrAgentRepo;
    private final QrBackupPoolRepository backupPoolRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final QrImageService qrImageService;

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

        // 动态城市/区列表
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        // 今日新增统计：每个活码的今日新增客户数
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime todayEnd = LocalDateTime.now();
        Map<Long, Long> todayCountMap = new HashMap<>();
        Map<Long, String> agentCountMap = new HashMap<>(); // "值守/后备" 格式

        for (QrCode qr : qrCodes.getContent()) {
            // 值守数 = 活跃状态的员工数
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active).size();
            // 后备数
            long backupCount = backupPoolRepo.countByQrCodeIdAndStatus(
                qr.getId(), QrBackupPool.PoolStatus.standby);
            agentCountMap.put(qr.getId(), activeCount + "/" + backupCount);

            // 今日新增
            long todayCount = customerRepo.countBySourceQrIdAndAddTimeBetween(
                qr.getId(), todayStart, todayEnd);
            todayCountMap.put(qr.getId(), todayCount);
        }

        model.addAttribute("qrCodes", qrCodes);
        model.addAttribute("keyword", keyword);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("status", status);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        model.addAttribute("agentCountMap", agentCountMap);
        model.addAttribute("todayCountMap", todayCountMap);
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
            String taskId = qrCodeService.asyncBatchImport(file);
            redirect.addFlashAttribute("message", "导入任务已启动");
            return "redirect:/qrcodes/batch-import/progress?taskId=" + taskId;
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/qrcodes/batch-import";
        }
    }

    /** 批量导入进度页 */
    @GetMapping("/batch-import/progress")
    public String batchImportProgress(@RequestParam String taskId, Model model) {
        model.addAttribute("taskId", taskId);
        return "qrcode/batch-import";
    }

    /** 查询导入进度（JSON） */
    @GetMapping("/batch-import/progress/{taskId}")
    @ResponseBody
    public Map<Object, Object> getImportProgress(@PathVariable String taskId) {
        return qrCodeService.getBatchImportProgress(taskId);
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

        // 最近 20 条轮换日志
        model.addAttribute("rotateLogs",
            rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(id,
                org.springframework.data.domain.PageRequest.of(0, 20)));

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

    /** 批量切换轮换模式 */
    @PostMapping("/batch-rotate-mode")
    public String batchUpdateRotateMode(@RequestParam List<Long> ids,
                                        @RequestParam String mode,
                                        RedirectAttributes redirect) {
        try {
            QrCode.RotateMode rotateMode = QrCode.RotateMode.valueOf(mode);
            int count = qrCodeService.batchUpdateRotateMode(ids, rotateMode);
            redirect.addFlashAttribute("message", "已更新 " + count + " 个活码的轮换模式为 " + mode);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /** 更新预警阈值 */
    @PostMapping("/{id}/thresholds")
    public String updateThresholds(@PathVariable Long id,
                                   @RequestParam int warnRatio,
                                   @RequestParam int urgentRatio,
                                   RedirectAttributes redirect) {
        try {
            qrCodeService.updateThresholds(id, warnRatio, urgentRatio);
            redirect.addFlashAttribute("message", "预警阈值已更新");
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

    /** 更新活码外观样式 */
    @PostMapping("/{id}/style")
    public String updateStyle(@PathVariable Long id,
                              @RequestParam(required = false) String theme,
                              @RequestParam(required = false) String guideText,
                              @RequestParam(required = false, defaultValue = "true") Boolean showSchoolName,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.updateStyle(id, theme, guideText, showSchoolName, null);
            redirect.addFlashAttribute("message", "样式已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /** 单个活码二维码下载 */
    @GetMapping("/{id}/download")
    public void downloadSingle(@PathVariable Long id,
                               @RequestParam(defaultValue = "72") int dpi,
                               HttpServletResponse response) throws IOException {
        QrCode qr = qrCodeService.getById(id);
        byte[] imageBytes = qrImageService.generateQrImage(id, dpi);
        String filename = qr.getSchoolName() + "_" + dpi + "dpi.png";

        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString());
        response.setContentLength(imageBytes.length);
        response.getOutputStream().write(imageBytes);
        response.getOutputStream().flush();
    }

    /** 批量下载活码二维码（ZIP） */
    @PostMapping("/batch-download")
    public void downloadBatch(@RequestParam List<Long> ids,
                              @RequestParam(defaultValue = "72") int dpi,
                              HttpServletResponse response) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Long id : ids) {
                try {
                    QrCode qr = qrCodeService.getById(id);
                    byte[] imageBytes = qrImageService.generateQrImage(id, dpi);
                    String entryName = qr.getSchoolName() + "_" + dpi + "dpi.png";
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);
                    zos.write(imageBytes);
                    zos.closeEntry();
                } catch (Exception e) {
                    log.warn("批量下载跳过: id={}, error={}", id, e.getMessage());
                }
            }
        }

        byte[] zipBytes = baos.toByteArray();
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("qrcodes_" + dpi + "dpi.zip").build().toString());
        response.setContentLength(zipBytes.length);
        response.getOutputStream().write(zipBytes);
        response.getOutputStream().flush();
    }
}
