package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.DailyReport;
import com.bookstore.qrcode.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板页面控制器。
 *
 * <p>展示平台核心运营指标：活码状态分布、全局员工池余量、
 * 今日新增客户数、异常告警数、封号/熔断员工数。
 * 支持趋势图表、漏斗、排行榜和 Excel 导出。</p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET {@code /dashboard} — 数据看板首页。
     */
    @GetMapping
    public String index(Model model) {
        Map<String, Object> stats = dashboardService.gatherStats("today");
        stats.forEach(model::addAttribute);
        model.addAttribute("title", "数据看板");
        return "dashboard/index";
    }

    // ── REST API ──────────────────────────────────

    /**
     * GET {@code /dashboard/api/stats} — 统计卡片数据，供 AJAX 轮询。
     */
    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> statsApi(
            @RequestParam(defaultValue = "today") String range) {
        return dashboardService.gatherStats(range);
    }

    /**
     * GET {@code /dashboard/api/trends} — 趋势图时间序列数据。
     *
     * @param days 天数（7 或 30），默认 7
     */
    @GetMapping("/api/trends")
    @ResponseBody
    public Map<String, Object> trendsApi(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("days 必须在 1-365 之间");
        }
        return dashboardService.getTrendData(days);
    }

    /**
     * GET {@code /dashboard/api/funnels} — 漏斗数据。
     */
    @GetMapping("/api/funnels")
    @ResponseBody
    public Map<String, Object> funnelsApi() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poolFunnel", dashboardService.getPoolFunnel());
        result.put("qrFunnel", dashboardService.getQrFunnel());
        return result;
    }

    /**
     * GET {@code /dashboard/api/leaderboards} — 排行榜数据。
     *
     * @param range 时间范围：today / 7days / 30days
     */
    @GetMapping("/api/leaderboards")
    @ResponseBody
    public Map<String, Object> leaderboardsApi(
            @RequestParam(defaultValue = "today") String range) {
        LocalDateTime start;
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();

        switch (range) {
            case "7days":
                start = LocalDate.now().minusDays(7).atStartOfDay();
                break;
            case "30days":
                start = LocalDate.now().minusDays(30).atStartOfDay();
                break;
            default:
                start = LocalDate.now().atStartOfDay();
                break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employees", dashboardService.getEmployeeLeaderboard(start, end));
        result.put("qrCodes", dashboardService.getQrLeaderboard(start, end));
        return result;
    }

    // ── Excel 导出 ─────────────────────────────────

    /**
     * GET {@code /dashboard/export} — 导出日报数据为 Excel。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     */
    @GetMapping("/export")
    public void exportExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            HttpServletResponse response) throws IOException {

        // 校验
        if (start.isAfter(end)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "起始日期不能晚于结束日期");
            return;
        }
        if (end.toEpochDay() - start.toEpochDay() > 366) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "导出范围不能超过 366 天");
            return;
        }

        List<DailyReport> reports = dashboardService.getDailyReportsForExport(start, end);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename("daily_report_" + start + "_" + end + ".xlsx")
                        .build().toString());

        XSSFWorkbook wb = new XSSFWorkbook();
        try {
            Sheet sheet = wb.createSheet("日报数据");

            // 表头样式
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            String[] headers = {"日期", "新增客户", "转接数", "转接成功", "告警数",
                    "活跃活码", "满员活码", "封号员工", "熔断员工"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 数据行
            int rowIdx = 1;
            for (DailyReport r : reports) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.getDate().toString());
                row.createCell(1).setCellValue(r.getTotalAdd());
                row.createCell(2).setCellValue(r.getTotalTransfer());
                row.createCell(3).setCellValue(r.getTotalTransferOk());
                row.createCell(4).setCellValue(r.getTotalAlert());
                row.createCell(5).setCellValue(r.getActiveQr());
                row.createCell(6).setCellValue(r.getFullQr());
                row.createCell(7).setCellValue(r.getBlockedAgent());
                row.createCell(8).setCellValue(r.getMeltedAgent());
            }

            // 自动列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(response.getOutputStream());
            response.flushBuffer();
        } finally {
            wb.close();
        }
    }
}
