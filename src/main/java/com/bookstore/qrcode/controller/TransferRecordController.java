package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 转接记录查看页（两级视图）。
 * <p>
 * <b>一级：</b>{@code GET /transfers} —— 选择时间范围后，列出该时间段内加过新客户的活码，
 * 每行展示学校名、新增客户数、转接成功数、转接失败数、进行中数。
 * <b>二级：</b>{@code GET /transfers/{qrCodeId}} —— 展示该活码在时间范围内
 * 各客户的转移明细（客户姓名、转出方→转入方、状态、时间、失败原因）。
 * </p>
 *
 * <p>时间筛选统一基于 {@link Customer#addTime}（加人时间）；成功 = confirmed，
 * 失败 = rejected/timeout/api_failed/retry_limit，进行中 = pending_confirm。</p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Controller
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferRecordController {

    private final CustomerTransferRepository transferRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;

    /**
     * 一级视图：按活码汇总转接结果。
     *
     * @param range 时间预设：today / 7d / 30d / custom（默认 today）
     * @param start 自定义起始日期（YYYY-MM-DD，仅 range=custom 时生效）
     * @param end   自定义结束日期（YYYY-MM-DD，仅 range=custom 时生效）
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "today") String range,
                       @RequestParam(required = false) String start,
                       @RequestParam(required = false) String end,
                       Model model) {
        Window window = resolveWindow(range, start, end);

        List<Object[]> rows = transferRepo.summarizeTransfersByQrCode(window.start(), window.end());
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("qrCodeId", ((Number) r[0]).longValue());
            m.put("schoolName", r[1]);
            m.put("newCount", ((Number) r[2]).longValue());
            m.put("successCount", ((Number) r[3]).longValue());
            m.put("failCount", ((Number) r[4]).longValue());
            m.put("pendingCount", ((Number) r[5]).longValue());
            return m;
        }).toList();

        model.addAttribute("items", items);
        model.addAttribute("range", range);
        model.addAttribute("start", window.start().toLocalDate().toString());
        model.addAttribute("end", window.end().toLocalDate().toString());
        return "transfer/list";
    }

    /**
     * 二级视图：某活码在时间范围内的转移明细。
     */
    @GetMapping("/{qrCodeId}")
    public String detail(@PathVariable Long qrCodeId,
                         @RequestParam(defaultValue = "today") String range,
                         @RequestParam(required = false) String start,
                         @RequestParam(required = false) String end,
                         Model model) {
        QrCode qr = qrCodeRepo.findById(qrCodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活码不存在"));
        Window window = resolveWindow(range, start, end);

        List<CustomerTransfer> transfers = transferRepo
            .findByQrCodeAndCustomerAddTimeBetween(qrCodeId, window.start(), window.end());

        // 客户姓名映射（customerId → name）
        Set<Long> customerIds = new HashSet<>();
        for (CustomerTransfer t : transfers) {
            customerIds.add(t.getCustomerId());
        }
        Map<Long, String> customerNameMap = new HashMap<>();
        if (!customerIds.isEmpty()) {
            customerRepo.findAllById(customerIds).forEach(c ->
                customerNameMap.put(c.getId(), c.getName() != null ? c.getName() : ""));
        }

        // 员工姓名映射（userid → name），优先 Employee 通讯录，回退 Agent 主数据
        Set<String> userids = new HashSet<>();
        for (CustomerTransfer t : transfers) {
            userids.add(t.getFromUserid());
            userids.add(t.getToUserid());
        }
        Map<String, String> nameMap = new HashMap<>();
        if (!userids.isEmpty()) {
            employeeRepo.findByUseridIn(userids).forEach(e ->
                nameMap.put(e.getUserid(), e.getName()));
            agentRepo.findAllById(userids).forEach(a ->
                nameMap.putIfAbsent(a.getUserid(), a.getName()));
        }
        for (CustomerTransfer t : transfers) {
            nameMap.putIfAbsent(t.getFromUserid(), t.getFromUserid());
            nameMap.putIfAbsent(t.getToUserid(), t.getToUserid());
        }

        long successCount = 0;
        long failCount = 0;
        long pendingCount = 0;
        for (CustomerTransfer t : transfers) {
            switch (t.getStatus()) {
                case confirmed -> successCount++;
                case rejected, timeout, api_failed, retry_limit -> failCount++;
                case pending_confirm -> pendingCount++;
            }
        }

        model.addAttribute("qr", qr);
        model.addAttribute("transfers", transfers);
        model.addAttribute("customerNameMap", customerNameMap);
        model.addAttribute("nameMap", nameMap);
        model.addAttribute("successCount", successCount);
        model.addAttribute("failCount", failCount);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("range", range);
        model.addAttribute("start", window.start().toLocalDate().toString());
        model.addAttribute("end", window.end().toLocalDate().toString());
        return "transfer/detail";
    }

    /** 时间窗口解析：today / 7d / 30d / custom */
    private Window resolveWindow(String range, String start, String end) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range == null ? "today" : range) {
            case "7d" -> new Window(now.minusDays(7), now);
            case "30d" -> new Window(now.minusDays(30), now);
            case "custom" -> new Window(parseStart(start, now), parseEnd(end, now));
            default -> new Window(now.toLocalDate().atStartOfDay(), now);
        };
    }

    private LocalDateTime parseStart(String start, LocalDateTime now) {
        if (start == null || start.isBlank()) return now.minusDays(30);
        try {
            return LocalDate.parse(start).atStartOfDay();
        } catch (Exception e) {
            return now.minusDays(30);
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
}
