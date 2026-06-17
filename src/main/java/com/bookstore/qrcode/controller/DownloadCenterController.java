package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工活码下载中心控制器。
 * <p>
 * 企微 OAuth 登录后，员工浏览/搜索/下载活码二维码，查看个人下载历史。
 * 所有路由挂载在 {@code /download} 下。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Controller
@RequestMapping("/download")
@RequiredArgsConstructor
public class DownloadCenterController {

    private final WecomOAuthService wecomOAuthService;
    private final DownloadLogService downloadLogService;
    private final DistrictManagerService districtManagerService;
    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeService qrCodeService;

    // ==================== OAuth 认证 ====================

    /**
     * OAuth 入口：构造授权 URL 并 302 跳转到企微。
     */
    @GetMapping("/oauth/entry")
    public String oauthEntry(HttpServletRequest request) {
        String redirectUri = request.getRequestURL().toString()
            .replace("/entry", "/callback");
        String authUrl = wecomOAuthService.buildAuthUrl(redirectUri);
        return "redirect:" + authUrl;
    }

    /**
     * OAuth 回调：企微带 code 回跳，完成认证后进入下载主页。
     */
    @GetMapping("/oauth/callback")
    public String oauthCallback(@RequestParam String code,
                                HttpSession session,
                                Model model) {
        try {
            Employee employee = wecomOAuthService.authenticate(code, session);
            return "redirect:/download";
        } catch (Exception e) {
            log.error("OAuth 认证失败: {}", e.getMessage());
            model.addAttribute("error", "认证失败：" + e.getMessage());
            return "download/error";
        }
    }

    // ==================== 下载主页 ====================

    /**
     * 活码浏览主页（卡片网格）。
     * <p>默认展示当前员工绑定的活码，可通过 mode=all 切换到全部活码。</p>
     */
    @GetMapping
    public String index(@RequestParam(required = false) String keyword,
                        @RequestParam(required = false) String mode,
                        @RequestParam(required = false) String managerUserid,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "12") int size,
                        HttpSession session,
                        Model model) {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            return "redirect:/download/oauth/entry";
        }
        String employeeName = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);

        // 1. 获取当前员工的绑定活码 ID 集合
        List<QrAgent> myAgents = qrAgentRepo.findByAgentUseridAndStatus(
            userid, QrAgent.AgentStatus.active);
        Set<Long> myQrCodeIds = myAgents.stream()
            .map(QrAgent::getQrCodeId)
            .collect(Collectors.toSet());

        // 2. 搜索活码
        Page<QrCode> qrCodePage;
        if ("all".equals(mode)) {
            // 全部活码模式
            qrCodePage = qrCodeRepo.search(keyword, null, null, QrCode.QrCodeStatus.active,
                PageRequest.of(page, size));
        } else {
            // 我的活码模式：只显示绑定的 — 一次性批量加载，避免 N+1
            List<Long> ids = new ArrayList<>(myQrCodeIds);
            // Batch load all my QR codes, then filter + paginate in memory
            List<QrCode> allMyQrCodes = qrCodeRepo.findAllById(ids);
            Map<Long, QrCode> qrCodeMap = new HashMap<>();
            for (QrCode qr : allMyQrCodes) qrCodeMap.put(qr.getId(), qr);
            // Keyword filtering in memory
            if (keyword != null && !keyword.isEmpty()) {
                List<Long> filtered = new ArrayList<>();
                for (Long id : ids) {
                    QrCode qr = qrCodeMap.get(id);
                    if (qr != null && (qr.getSchoolName().contains(keyword)
                        || qr.getSchoolId().contains(keyword)
                        || qr.getRegionCity().contains(keyword)
                        || qr.getRegionDistrict().contains(keyword))) {
                        filtered.add(id);
                    }
                }
                ids = filtered;
            }
            // Paginate from the pre-loaded map
            int fromIdx = page * size;
            int toIdx = Math.min(fromIdx + size, ids.size());
            List<QrCode> pageContent = new ArrayList<>();
            if (fromIdx < ids.size()) {
                for (Long id : ids.subList(fromIdx, toIdx)) {
                    QrCode qr = qrCodeMap.get(id);
                    if (qr != null) pageContent.add(qr);
                }
            }
            qrCodePage = new org.springframework.data.domain.PageImpl<>(
                pageContent, PageRequest.of(page, size), ids.size());
        }

        // 3. 区县负责人缓存
        Map<String, DistrictManager> managerMap = districtManagerService.getAllAsMap();

        // 4. 已下载活码 ID（用于卡片标记）
        Set<Long> downloadedIds = downloadLogService.getDownloadedQrCodeIds(userid);

        // 5. 负责人筛选下拉列表
        List<Map<String, String>> managerOptions = managerMap.values().stream()
            .map(m -> Map.of("userid", m.getManagerUserid(), "name", m.getManagerName()))
            .distinct()
            .toList();

        model.addAttribute("employeeName", employeeName);
        model.addAttribute("qrCodes", qrCodePage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("mode", mode != null ? mode : "mine");
        model.addAttribute("managerUserid", managerUserid);
        model.addAttribute("managerMap", managerMap);
        model.addAttribute("downloadedIds", downloadedIds);
        model.addAttribute("managerOptions", managerOptions);
        model.addAttribute("downloadCounts",
            downloadLogService.getDownloadCounts(userid,
                qrCodePage.getContent().stream().map(QrCode::getId).toList()));
        return "download/index";
    }

    // ==================== 下载操作 ====================

    /**
     * 下载活码二维码图片（代理企微图片 + 记录日志）。
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id,
                         HttpSession session,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        QrCode qr = qrCodeService.getById(id);
        if (qr.getQrUrl() == null || qr.getQrUrl().isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "该活码暂无二维码图片");
            return;
        }

        // 记录下载日志
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        downloadLogService.recordDownload(id, userid, ip);

        // 代理下载企微图片
        URL url = new URL(qr.getQrUrl());
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.connect();
        String filename = qr.getRegionDistrict() + "-" + qr.getSchoolName() + "-" + qr.getRegionCity() + ".png";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.setContentLength(conn.getContentLength());
        try (InputStream in = conn.getInputStream()) {
            in.transferTo(response.getOutputStream());
        }
        response.getOutputStream().flush();
    }

    // ==================== 下载记录 ====================

    /**
     * 当前员工的下载历史。
     */
    @GetMapping("/history")
    public String history(HttpSession session, Model model) {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            return "redirect:/download/oauth/entry";
        }
        String employeeName = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);

        List<Map<String, Object>> history = downloadLogService.getPersonalHistory(userid);
        long totalDownloads = history.size();
        long distinctSchools = history.stream()
            .map(r -> r.get("schoolName"))
            .distinct().count();

        model.addAttribute("employeeName", employeeName);
        model.addAttribute("history", history);
        model.addAttribute("totalDownloads", totalDownloads);
        model.addAttribute("distinctSchools", distinctSchools);
        return "download/history";
    }
}
