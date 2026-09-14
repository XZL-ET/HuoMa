package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 删除记录列表页 —— 跨方向查看「客户删除员工」与「员工删除客户」的历史记录。
 *
 * <p>展示维度：时间、方向、客户（微信名）、员工（姓名）、来源。
 * 支持时间范围预设（今天 / 近7天 / 近30天 / 自定义）、删除方向过滤与关键字
 * 筛选（匹配客户名、external_userid、员工姓名、userid、来源）。
 * 另提供 Excel 导出（导出当前筛选条件下的全部记录，不受列表页 500 条截断限制）。</p>
 */
@Slf4j
@Controller
@RequestMapping("/deletion-records")
@RequiredArgsConstructor
public class DeletionRecordController {

    private static final int MAX_ROWS = 500;

    private final CustomerDeletionEventRepository deletionRepo;
    private final CustomerRepository customerRepo;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;

    @GetMapping
    public String list(@RequestParam(defaultValue = "7d") String range,
                       @RequestParam(required = false) String start,
                       @RequestParam(required = false) String end,
                       @RequestParam(defaultValue = "CUSTOMER_DELETED_AGENT") String direction,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        LoadResult r = loadEvents(range, start, end, direction, keyword, true);

        model.addAttribute("events", r.events());
        model.addAttribute("customerNameMap", r.customerNameMap());
        model.addAttribute("employeeNameMap", r.employeeNameMap());
        model.addAttribute("range", range);
        model.addAttribute("start", r.window().start().toLocalDate().toString());
        model.addAttribute("end", r.window().end().toLocalDate().toString());
        model.addAttribute("direction", direction == null ? "" : direction);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("maxRows", MAX_ROWS);
        return "deletion-records";
    }

    /**
     * 导出删除记录为 Excel（SXSSFWorkbook 流式写入）。
     * <p>导出当前筛选条件下的全部记录，不受列表页 {@link #MAX_ROWS} 截断限制。</p>
     */
    @GetMapping("/export")
    public void export(@RequestParam(defaultValue = "7d") String range,
                       @RequestParam(required = false) String start,
                       @RequestParam(required = false) String end,
                       @RequestParam(defaultValue = "CUSTOMER_DELETED_AGENT") String direction,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws Exception {
        String operator = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "anonymous";
        log.info("导出删除记录: operator={}, range={}, direction={}, keyword={}",
                operator, range, direction, keyword);

        LoadResult r = loadEvents(range, start, end, direction, keyword, false);
        List<CustomerDeletionEvent> events = r.events();
        Map<String, String> customerNameMap = r.customerNameMap();
        Map<String, String> employeeNameMap = r.employeeNameMap();

        String filename = "删除记录_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        String encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);

        try {
            SXSSFWorkbook wb = new SXSSFWorkbook(100);
            var sheet = wb.createSheet("删除记录");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            String[] headers = {"删除时间", "方向", "客户", "员工", "来源"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIdx = 1;
            for (CustomerDeletionEvent e : events) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getDeletedAt() != null ? e.getDeletedAt().format(dateFmt) : "");
                row.createCell(1).setCellValue(
                        e.getDirection() == CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT
                                ? "客户删除员工" : "员工删除客户");
                row.createCell(2).setCellValue(
                        customerNameMap.getOrDefault(e.getExternalUserid(), e.getExternalUserid()));
                row.createCell(3).setCellValue(
                        employeeNameMap.getOrDefault(e.getUserid(), e.getUserid()));
                row.createCell(4).setCellValue(e.getSource() != null ? e.getSource() : "");
            }

            sheet.createFreezePane(0, 1);
            for (int i = 0; i < headers.length; i++) {
                sheet.trackColumnForAutoSizing(i);
                sheet.autoSizeColumn(i);
            }

            try {
                wb.write(response.getOutputStream());
                log.info("导出完成: {} 条删除记录已写入 {}", events.size(), filename);
            } finally {
                wb.dispose();
                wb.close();
            }
        } catch (Exception e) {
            response.reset();
            response.setContentType("text/plain; charset=UTF-8");
            throw e;
        }
    }

    /** 加载并过滤记录：解析窗口 → 查询 → 名称映射 → 方向过滤 → 关键字过滤。 */
    private LoadResult loadEvents(String range, String start, String end,
                                  String direction, String keyword, boolean limit) {
        Window window = resolveWindow(range, start, end);
        List<CustomerDeletionEvent> events = limit
                ? deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(
                        window.start(), window.end(), PageRequest.of(0, MAX_ROWS))
                : deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(
                        window.start(), window.end());

        Map<String, String> customerNameMap = buildCustomerNameMap(events);
        Map<String, String> employeeNameMap = buildEmployeeNameMap(events);
        events = filterByDirection(events, direction);
        events = filterByKeyword(events, keyword, customerNameMap, employeeNameMap);
        return new LoadResult(events, customerNameMap, employeeNameMap, window);
    }

    /** 客户名映射（external_userid → 微信名，非「未知」时才显示名，否则回退 ID）。 */
    private Map<String, String> buildCustomerNameMap(List<CustomerDeletionEvent> events) {
        Set<String> externalUserids = new HashSet<>();
        for (CustomerDeletionEvent e : events) externalUserids.add(e.getExternalUserid());
        Map<String, String> map = new HashMap<>();
        if (!externalUserids.isEmpty()) {
            customerRepo.findByExternalUseridIn(externalUserids).forEach(c -> {
                String name = c.getName();
                if (name != null && !name.isBlank() && !"未知".equals(name)) {
                    map.put(c.getExternalUserid(), name);
                }
            });
            externalUserids.forEach(euid -> map.putIfAbsent(euid, euid));
        }
        return map;
    }

    /** 员工名映射（userid → 姓名），通讯录优先，回退 Agent 主数据，再回退 userid。 */
    private Map<String, String> buildEmployeeNameMap(List<CustomerDeletionEvent> events) {
        Set<String> userids = new HashSet<>();
        for (CustomerDeletionEvent e : events) userids.add(e.getUserid());
        Map<String, String> map = new HashMap<>();
        if (!userids.isEmpty()) {
            employeeRepo.findByUseridIn(userids).forEach(em ->
                    map.put(em.getUserid(), em.getName()));
            agentRepo.findAllById(userids).forEach(a ->
                    map.putIfAbsent(a.getUserid(), a.getName()));
            userids.forEach(uid -> map.putIfAbsent(uid, uid));
        }
        return map;
    }

    private List<CustomerDeletionEvent> filterByDirection(List<CustomerDeletionEvent> events, String direction) {
        CustomerDeletionEvent.Direction dirFilter = null;
        if (direction != null && !direction.isBlank()) {
            try {
                dirFilter = CustomerDeletionEvent.Direction.valueOf(direction);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (dirFilter != null) {
            final CustomerDeletionEvent.Direction df = dirFilter;
            return events.stream().filter(e -> e.getDirection() == df).toList();
        }
        return events;
    }

    private List<CustomerDeletionEvent> filterByKeyword(List<CustomerDeletionEvent> events, String keyword,
                                                        Map<String, String> customerNameMap,
                                                        Map<String, String> employeeNameMap) {
        if (keyword == null || keyword.isBlank()) {
            return events;
        }
        String kw = keyword.trim();
        return events.stream().filter(e ->
                contains(customerNameMap.get(e.getExternalUserid()), kw)
                        || contains(e.getExternalUserid(), kw)
                        || contains(employeeNameMap.get(e.getUserid()), kw)
                        || contains(e.getUserid(), kw)
                        || contains(e.getSource(), kw)
        ).toList();
    }

    private boolean contains(String value, String kw) {
        return value != null && value.contains(kw);
    }

    /** 时间窗口解析：today / 7d / 30d / custom */
    private Window resolveWindow(String range, String start, String end) {
        LocalDateTime now = LocalDateTime.now();
        // 上界放宽 1 秒：DB 时间戳按秒舍入可能把 deleted_at 进位到 now 之后
        LocalDateTime upperBound = now.plusSeconds(1);
        return switch (range == null ? "7d" : range) {
            case "today" -> new Window(now.toLocalDate().atStartOfDay(), upperBound);
            case "yesterday" -> {
                LocalDate yesterday = now.toLocalDate().minusDays(1);
                yield new Window(yesterday.atStartOfDay(), yesterday.atTime(23, 59, 59));
            }
            case "30d" -> new Window(now.minusDays(30), upperBound);
            case "custom" -> new Window(parseStart(start, now), parseEnd(end, upperBound));
            default -> new Window(now.minusDays(7), upperBound);
        };
    }

    private LocalDateTime parseStart(String start, LocalDateTime now) {
        if (start == null || start.isBlank()) return now.minusDays(7);
        try {
            return LocalDate.parse(start).atStartOfDay();
        } catch (Exception e) {
            return now.minusDays(7);
        }
    }

    private LocalDateTime parseEnd(String end, LocalDateTime now) {
        if (end == null || end.isBlank()) return now;
        try {
            return LocalDate.parse(end).atTime(23, 59, 59);
        } catch (Exception e) {
            return now;
        }
    }

    private record Window(LocalDateTime start, LocalDateTime end) {}

    private record LoadResult(List<CustomerDeletionEvent> events,
                              Map<String, String> customerNameMap,
                              Map<String, String> employeeNameMap,
                              Window window) {}
}
