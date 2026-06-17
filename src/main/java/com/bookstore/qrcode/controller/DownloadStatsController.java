package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DownloadLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 管理后台：下载统计页面。
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Controller
@RequestMapping("/admin/download-stats")
@RequiredArgsConstructor
public class DownloadStatsController {

    private final DownloadLogService downloadLogService;
    private final QrCodeRepository qrCodeRepo;

    @GetMapping
    public String stats(@RequestParam(required = false) String city,
                        @RequestParam(required = false) String district,
                        @RequestParam(required = false) String managerUserid,
                        @RequestParam(required = false) String downloadStatus,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {

        Map<String, Object> stats = downloadLogService.getGlobalStats(
            city, district, managerUserid, downloadStatus, page, size);

        // 城市/区县下拉列表
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        model.addAttribute("stats", stats);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("managerUserid", managerUserid);
        model.addAttribute("downloadStatus", downloadStatus);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        return "admin/download-stats";
    }
}
