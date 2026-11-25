package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.DeletionGroupRow;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DeletionReportService;
import com.bookstore.qrcode.service.DepartmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 删除记录列表页 —— 跨方向查看「客户删除员工」与「员工删除客户」的历史记录。
 *
 * <p>展示维度：客户（微信名）、员工（姓名）、方向、删除次数、首次/最近删除时间、来源。
 * 同一客户对同一员工在同一时间窗内的多次删除聚合为一行（事件流水表不改结构），
 * 展开可看每次删除时间。支持时间范围预设（今天 / 近7天 / 近30天 / 自定义）、
 * 删除方向过滤与关键字筛选（匹配客户名、external_userid、员工姓名、userid、来源）。
 * 另提供 Excel 导出（按同样口径聚合，导出当前筛选条件下的全部记录，不受列表页 500 组截断限制）。</p>
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
    private final QrCodeRepository qrCodeRepo;
    private final DeletionReportService deletionReportService;
    private final DepartmentService departmentService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String list(@RequestParam(defaultValue = "7d") String range,
                       @RequestParam(required = false) String start,
                       @RequestParam(required = false) String end,
                       @RequestParam(defaultValue = "CUSTOMER_DELETED_AGENT") String direction,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        LoadResult r = loadEvents(range, start, end, direction, keyword);
        List<DeletionGroupRow> groups = r.groups();
        int totalGroups = groups.size();

        model.addAttribute("groups", totalGroups > MAX_ROWS
                ? new ArrayList<>(groups.subList(0, MAX_ROWS)) : groups);
        model.addAttribute("totalGroups", totalGroups);
        model.addAttribute("totalEvents", r.totalEvents());
        model.addAttribute("customerNameMap", r.customerNameMap());
        model.addAttribute("employeeNameMap", r.employeeNameMap());
        model.addAttribute("employeeDeptMap", r.employeeDeptMap());
        model.addAttribute("deptSummary", r.deptSummary());
        model.addAttribute("sourceMap", r.sourceMap());
        model.addAttribute("range", range);
        model.addAttribute("start", r.window().start().toLocalDate().toString());
        model.addAttribute("end", r.window().end().toLocalDate().toString());
        model.addAttribute("direction", direction == null ? "" : direction);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("maxRows", MAX_ROWS);
        String todayRecipients = deletionReportService.getTodayRecipients();
        model.addAttribute("todayRecipients", todayRecipients);
        model.addAttribute("todayRecipientNames", buildTodayRecipientNameMap(todayRecipients));
        return "deletion-records";
    }

    /**
     * 立即推送「今天 00:00 至当前时刻」的客户删除员工汇总给指定接收人。
     * <p>接收人由删除记录页输入框传入，独立于每日日报接收人配置，保存后下次自动带出。</p>
     */
    @PostMapping("/push-today")
    public String pushToday(@RequestParam String recipients, RedirectAttributes ra) {
        deletionReportService.saveTodayRecipients(recipients);
        DeletionReportService.TodayPushResult result = deletionReportService.reportTodayWithLock(recipients);
        if (result.busy()) {
            ra.addFlashAttribute("message", "推送正在进行中，请稍后重试");
        } else if (!result.failed().isEmpty()) {
            ra.addFlashAttribute("message", "已推送今日汇总给 " + result.sent() + " 位接收人；"
                    + "以下账号推送失败：" + String.join("、", result.failed())
                    + "（可能不在日报应用可见范围内）");
        } else if (result.sent() > 0) {
            ra.addFlashAttribute("message", "已推送今日汇总给 " + result.sent() + " 位接收人");
        } else {
            ra.addFlashAttribute("message", "未推送：今日暂无删除事件，或接收人为空");
        }
        return "redirect:/deletion-records";
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

        LoadResult r = loadEvents(range, start, end, direction, keyword);
        List<DeletionGroupRow> groups = r.groups();
        Map<String, String> customerNameMap = r.customerNameMap();
        Map<String, String> employeeNameMap = r.employeeNameMap();
        Map<String, String> employeeDeptMap = r.employeeDeptMap();
        Map<String, String> sourceMap = r.sourceMap();

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

            String[] headers = {"客户", "客户ID", "员工", "员工ID", "方向", "删除次数",
                    "首次删除", "最近删除", "单位", "来源"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIdx = 1;
            for (DeletionGroupRow g : groups) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(
                        customerNameMap.getOrDefault(g.externalUserid(), g.externalUserid()));
                row.createCell(1).setCellValue(g.externalUserid());
                row.createCell(2).setCellValue(
                        employeeNameMap.getOrDefault(g.userid(), g.userid()));
                row.createCell(3).setCellValue(g.userid());
                row.createCell(4).setCellValue(
                        g.direction() == CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT
                                ? "客户删除员工" : "员工删除客户");
                row.createCell(5).setCellValue(g.count());
                row.createCell(6).setCellValue(g.firstDeletedAt() != null
                        ? g.firstDeletedAt().format(dateFmt) : "");
                row.createCell(7).setCellValue(g.lastDeletedAt() != null
                        ? g.lastDeletedAt().format(dateFmt) : "");
                row.createCell(8).setCellValue(employeeDeptMap.getOrDefault(g.userid(), ""));
                row.createCell(9).setCellValue(sourceMap.getOrDefault(g.externalUserid(), ""));
            }

            sheet.createFreezePane(0, 1);
            for (int i = 0; i < headers.length; i++) {
                sheet.trackColumnForAutoSizing(i);
                sheet.autoSizeColumn(i);
            }

            try {
                wb.write(response.getOutputStream());
                log.info("导出完成: {} 组删除关系（{} 条事件）已写入 {}",
                        groups.size(), r.totalEvents(), filename);
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

    /**
     * 加载并聚合记录：解析窗口 → 查询全部事件 → 名称映射 → 方向过滤 → 关键字过滤 → 聚合。
     * <p>必须加载窗口内的全部事件（而非先截断），否则聚合计数与员工排序都会失真。</p>
     */
    private LoadResult loadEvents(String range, String start, String end,
                                  String direction, String keyword) {
        Window window = resolveWindow(range, start, end);
        List<CustomerDeletionEvent> events = deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(
                window.start(), window.end());

        CustomerMaps customerMaps = buildCustomerMaps(events);
        EmployeeMaps employeeMaps = buildEmployeeMaps(events);
        events = filterByDirection(events, direction);
        events = filterByKeyword(events, keyword, customerMaps.customerNameMap(), employeeMaps.employeeNameMap(), customerMaps.sourceMap());
        List<DeletionGroupRow> groups = aggregate(events);
        Map<String, Long> deptSummary = buildDeptSummary(events, employeeMaps.employeeDeptMap());
        return new LoadResult(groups, events.size(), customerMaps.customerNameMap(),
                employeeMaps.employeeNameMap(), employeeMaps.employeeDeptMap(),
                deptSummary, customerMaps.sourceMap(), window);
    }

    /**
     * 按「客户 + 员工 + 方向」聚合删除事件。
     * <p>同一客户对同一员工在同一时间窗内的多次删除合并为一行，
     * 保留次数、首次/最近删除时间与每次删除明细，避免重复删除虚增记录行数。</p>
     */
    private List<DeletionGroupRow> aggregate(List<CustomerDeletionEvent> events) {
        Map<GroupKey, List<CustomerDeletionEvent>> grouped = new LinkedHashMap<>();
        for (CustomerDeletionEvent e : events) {
            GroupKey key = new GroupKey(e.getDirection(), e.getExternalUserid(), e.getUserid());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<DeletionGroupRow> rows = new ArrayList<>(grouped.size());
        for (Map.Entry<GroupKey, List<CustomerDeletionEvent>> entry : grouped.entrySet()) {
            GroupKey key = entry.getKey();
            List<CustomerDeletionEvent> groupEvents = entry.getValue();
            List<LocalDateTime> times = groupEvents.stream()
                    .map(CustomerDeletionEvent::getDeletedAt)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            LocalDateTime first = times.isEmpty() ? null : times.get(times.size() - 1);
            LocalDateTime last = times.isEmpty() ? null : times.get(0);
            rows.add(new DeletionGroupRow(key.externalUserid(), key.userid(), key.direction(),
                    groupEvents.size(), first, last, times));
        }
        rows.sort(Comparator.comparing(DeletionGroupRow::lastDeletedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /** 今日推送接收人 userid → 姓名映射，供前端 chips 回显；查不到时回退 userid。 */
    private Map<String, String> buildTodayRecipientNameMap(String recipients) {
        if (recipients == null || recipients.isBlank()) return Map.of();
        List<String> userids = Arrays.stream(recipients.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (userids.isEmpty()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        employeeRepo.findByUseridIn(userids).forEach(e -> map.put(e.getUserid(), e.getName()));
        userids.forEach(uid -> map.putIfAbsent(uid, uid));
        return map;
    }

    /** 客户维度映射：一次查询 customer 表，同时产出客户名映射与来源（学校）映射。 */
    private CustomerMaps buildCustomerMaps(List<CustomerDeletionEvent> events) {
        Set<String> externalUserids = new HashSet<>();
        for (CustomerDeletionEvent e : events) externalUserids.add(e.getExternalUserid());

        Map<String, String> customerNameMap = new HashMap<>();
        Map<String, String> sourceMap = new HashMap<>();
        if (externalUserids.isEmpty()) return new CustomerMaps(customerNameMap, sourceMap);

        List<Customer> customers = customerRepo.findByExternalUseridIn(externalUserids);

        // 客户名：非「未知」时显示微信名，否则回退 ID
        for (Customer c : customers) {
            String name = c.getName();
            if (name != null && !name.isBlank() && !"未知".equals(name)) {
                customerNameMap.put(c.getExternalUserid(), name);
            }
        }
        externalUserids.forEach(euid -> customerNameMap.putIfAbsent(euid, euid));

        // 来源：external_userid → sourceQrId → QrCode.schoolName
        Map<String, Long> extToQrId = new HashMap<>();
        for (Customer c : customers) {
            if (c.getSourceQrId() != null) extToQrId.put(c.getExternalUserid(), c.getSourceQrId());
        }
        if (!extToQrId.isEmpty()) {
            Map<Long, String> qrIdToSchool = new HashMap<>();
            qrCodeRepo.findAllById(extToQrId.values()).forEach(q -> qrIdToSchool.put(q.getId(), q.getSchoolName()));
            extToQrId.forEach((euid, qrId) -> {
                String school = qrIdToSchool.get(qrId);
                if (school != null) sourceMap.put(euid, school);
            });
        }
        return new CustomerMaps(customerNameMap, sourceMap);
    }

    /** 员工维度映射：一次查询 employee 表，同时产出员工名映射与单位（部门）映射。 */
    private EmployeeMaps buildEmployeeMaps(List<CustomerDeletionEvent> events) {
        Set<String> userids = new HashSet<>();
        for (CustomerDeletionEvent e : events) userids.add(e.getUserid());

        Map<String, String> employeeNameMap = new HashMap<>();
        Map<String, String> employeeDeptMap = new HashMap<>();
        if (userids.isEmpty()) return new EmployeeMaps(employeeNameMap, employeeDeptMap);

        List<Employee> employees = employeeRepo.findByUseridIn(userids);

        // 员工名：通讯录优先，回退 Agent 主数据，再回退 userid
        for (Employee em : employees) employeeNameMap.put(em.getUserid(), em.getName());
        agentRepo.findAllById(userids).forEach(a -> employeeNameMap.putIfAbsent(a.getUserid(), a.getName()));
        userids.forEach(uid -> employeeNameMap.putIfAbsent(uid, uid));

        // 单位：userid → 主部门 ID → 部门名称
        Map<String, Long> useridToDeptId = new HashMap<>();
        for (Employee em : employees) {
            Long deptId = extractPrimaryDeptId(em.getDepartment());
            if (deptId != null) useridToDeptId.put(em.getUserid(), deptId);
        }
        if (!useridToDeptId.isEmpty()) {
            Map<Long, String> deptIdToName = departmentService.loadDeptIdNameMap();
            useridToDeptId.forEach((uid, deptId) -> {
                String name = deptIdToName.get(deptId);
                if (name != null) employeeDeptMap.put(uid, name);
            });
        }
        return new EmployeeMaps(employeeNameMap, employeeDeptMap);
    }

    /**
     * 按单位汇总「被删人数」（deptName → 去重后的客户数），降序。
     * <p>同一客户重复删除同一员工只计 1 人，与列表页的聚合口径一致；
     * 同一客户若删了不同单位的员工，则分别计入各单位。</p>
     */
    private Map<String, Long> buildDeptSummary(List<CustomerDeletionEvent> events,
                                               Map<String, String> employeeDeptMap) {
        Map<String, Set<String>> deptCustomers = new HashMap<>();
        for (CustomerDeletionEvent e : events) {
            String dept = employeeDeptMap.get(e.getUserid());
            if (dept == null || dept.isBlank()) continue;
            deptCustomers.computeIfAbsent(dept, k -> new HashSet<>()).add(e.getExternalUserid());
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        deptCustomers.forEach((dept, customers) -> counts.put(dept, (long) customers.size()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 从 Employee.department JSON 数组字符串中提取主部门 ID（取第一个元素）。 */
    private Long extractPrimaryDeptId(String departmentJson) {
        if (departmentJson == null || departmentJson.isBlank()) return null;
        try {
            JsonNode arr = objectMapper.readTree(departmentJson);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).asLong();
            }
        } catch (Exception e) {
            log.warn("解析部门 JSON 失败: {}", departmentJson, e);
        }
        return null;
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
                                                        Map<String, String> employeeNameMap,
                                                        Map<String, String> sourceMap) {
        if (keyword == null || keyword.isBlank()) {
            return events;
        }
        String kw = keyword.trim();
        return events.stream().filter(e ->
                contains(customerNameMap.get(e.getExternalUserid()), kw)
                        || contains(e.getExternalUserid(), kw)
                        || contains(employeeNameMap.get(e.getUserid()), kw)
                        || contains(e.getUserid(), kw)
                        || contains(sourceMap.get(e.getExternalUserid()), kw)
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

    private record GroupKey(CustomerDeletionEvent.Direction direction,
                            String externalUserid, String userid) {}

    private record LoadResult(List<DeletionGroupRow> groups,
                              int totalEvents,
                              Map<String, String> customerNameMap,
                              Map<String, String> employeeNameMap,
                              Map<String, String> employeeDeptMap,
                              Map<String, Long> deptSummary,
                              Map<String, String> sourceMap,
                              Window window) {}

    private record CustomerMaps(Map<String, String> customerNameMap,
                                Map<String, String> sourceMap) {}

    private record EmployeeMaps(Map<String, String> employeeNameMap,
                                Map<String, String> employeeDeptMap) {}
}
