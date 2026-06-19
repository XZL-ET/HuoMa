package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.SchoolCityDTO;
import com.bookstore.qrcode.dto.SchoolDetailDTO;
import com.bookstore.qrcode.dto.SchoolDistrictDTO;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.SchoolAccessLogService;
import com.bookstore.qrcode.service.SchoolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.UUID;

/**
 * 学校自助查询入口控制器。
 * <p>
 * 面向学校人员的公开页面（/s），无需登录。
 * 所有接口返回 HTMX 局部 HTML 片段，实现阶梯式卡片选择交互。
 * </p>
 */
@Slf4j
@Controller
@RequestMapping("/s")
@RequiredArgsConstructor
public class SchoolEntryController {

    private final SchoolService schoolService;
    private final SchoolAccessLogService logService;
    private final QrCodeRepository qrCodeRepository;

    // ========================================================================
    // 首页：市州列表 + 全局联系人
    // ========================================================================

    @GetMapping
    public String index(Model model, HttpServletRequest request, HttpSession session) {
        ensureSession(session);
        List<SchoolCityDTO> cities = schoolService.getCities();
        log.info("GET /s — cities: {} entries, htmx: {}", cities.size(), request.getHeader("HX-Request"));
        model.addAttribute("cities", cities);
        model.addAttribute("globalContactName", schoolService.getGlobalContactName());
        // HTMX 请求返回片段，避免整页嵌套
        if ("true".equals(request.getHeader("HX-Request"))) {
            return "school/cities-fragment";
        }
        return "school/cities";
    }

    // ========================================================================
    // HTMX 局部刷新：县区列表
    // ========================================================================

    @GetMapping("/districts")
    public String districts(@RequestParam String city, Model model) {
        List<SchoolDistrictDTO> districtList = schoolService.getDistricts(city);
        log.info("GET /s/districts?city={} — {} districts", city, districtList.size());
        model.addAttribute("city", city);
        model.addAttribute("districts", districtList);
        return "school/districts";
    }

    // ========================================================================
    // HTMX 局部刷新：学校列表
    // ========================================================================

    @GetMapping("/schools")
    public String schools(@RequestParam String city,
                          @RequestParam String district,
                          Model model) {
        List<School> schoolList = schoolService.getSchools(city, district);
        log.info("GET /s/schools?city={}&district={} — {} schools", city, district, schoolList.size());
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("schools", schoolList);
        return "school/schools";
    }

    // ========================================================================
    // HTMX 局部刷新：学校详情（完整页面）
    // ========================================================================

    @GetMapping("/school/{schoolId}")
    public String schoolDetail(@PathVariable String schoolId,
                                Model model,
                                HttpServletRequest request) {
        SchoolDetailDTO detail = schoolService.getSchoolDetail(schoolId);

        // 记录审计日志
        if (detail.isQrAvailable()) {
            QrCode qr = qrCodeRepository.findBySchoolId(schoolId).orElse(null);
            if (qr != null) {
                logService.logView(qr.getId(), request);
            }
        }

        model.addAttribute("detail", detail);
        return "school/detail";
    }

    // ========================================================================
    // 搜索
    // ========================================================================

    @GetMapping("/search")
    public String search(@RequestParam String keyword, Model model) {
        List<School> results = schoolService.searchSchools(keyword);
        model.addAttribute("keyword", keyword);
        model.addAttribute("schools", results);
        model.addAttribute("globalContactName", schoolService.getGlobalContactName());
        return "school/search-results";
    }

    // ========================================================================
    // 全局联系人详情
    // ========================================================================

    @GetMapping("/global-contact")
    public String globalContact(Model model, HttpServletRequest request) {
        model.addAttribute("contactName", schoolService.getGlobalContactName());
        model.addAttribute("qrUrl", schoolService.getGlobalContactQrUrl());
        logService.logGlobalContactView(request);
        return "school/global-contact";
    }

    // ========================================================================
    // 下载活码图片（代理下载 + 记录日志）
    // ========================================================================

    @GetMapping("/school/{schoolId}/download")
    public ResponseEntity<?> downloadQrCode(@PathVariable String schoolId,
                                             HttpServletRequest request) {
        SchoolDetailDTO detail = schoolService.getSchoolDetail(schoolId);
        if (!detail.isQrAvailable() || detail.getQrUrl() == null) {
            return ResponseEntity.notFound().build();
        }

        // 记录下载日志
        QrCode qr = qrCodeRepository.findBySchoolId(schoolId).orElse(null);
        if (qr != null) {
            logService.logDownload(qr.getId(), request);
        }

        // 代理下载企微活码图片
        try {
            URL url = URI.create(detail.getQrUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                ByteArrayResource resource = new ByteArrayResource(bytes);
                String filename = detail.getSchoolName() + "_活码.png";
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, "UTF-8"))
                        .body(resource);
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // ========================================================================
    // 临时会话
    // ========================================================================

    private void ensureSession(HttpSession session) {
        if (session.getAttribute("school_visitor_id") == null) {
            session.setAttribute("school_visitor_id", UUID.randomUUID().toString());
            session.setMaxInactiveInterval(1800); // 30 分钟
        }
    }
}
