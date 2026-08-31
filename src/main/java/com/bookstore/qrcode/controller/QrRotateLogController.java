package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.QrRotateLog;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局轮换日志列表页 —— 跨所有活码查看下码/上码记录。
 *
 * <p>展示维度：时间、活码（学校名）、下码员工、激活员工、原因。
 * 支持时间范围预设（今天 / 近7天 / 近30天 / 自定义）与关键字筛选
 * （匹配学校名、员工姓名或 userid、原因）。</p>
 */
@Controller
@RequestMapping("/rotate-logs")
@RequiredArgsConstructor
public class QrRotateLogController {

    private static final int MAX_ROWS = 500;

    private final QrRotateLogRepository rotateLogRepo;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;

    @GetMapping
    public String list(@RequestParam(defaultValue = "today") String range,
                       @RequestParam(required = false) String start,
                       @RequestParam(required = false) String end,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        Window window = resolveWindow(range, start, end);

        List<QrRotateLog> logs = rotateLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(
            window.start(), window.end(), PageRequest.of(0, MAX_ROWS));

        // 学校名映射（qrCodeId → schoolName）
        Set<Long> qrIds = new HashSet<>();
        for (QrRotateLog l : logs) qrIds.add(l.getQrCodeId());
        Map<Long, String> schoolNameMap = new HashMap<>();
        if (!qrIds.isEmpty()) {
            qrCodeRepo.findAllById(qrIds).forEach(q ->
                schoolNameMap.put(q.getId(), q.getSchoolName()));
        }

        // 员工姓名映射（userid → name），优先通讯录，回退 Agent 主数据，再回退 userid
        Set<String> userids = new HashSet<>();
        for (QrRotateLog l : logs) {
            if (l.getFromUserid() != null) userids.add(l.getFromUserid());
            if (l.getToUserid() != null) userids.add(l.getToUserid());
        }
        Map<String, String> nameMap = new HashMap<>();
        if (!userids.isEmpty()) {
            employeeRepo.findByUseridIn(userids).forEach(e ->
                nameMap.put(e.getUserid(), e.getName()));
            agentRepo.findAllById(userids).forEach(a ->
                nameMap.putIfAbsent(a.getUserid(), a.getName()));
            userids.forEach(uid -> nameMap.putIfAbsent(uid, uid));
        }

        // 关键字筛选：学校名 / 员工姓名 / userid / 原因
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            logs = logs.stream().filter(l -> {
                String school = schoolNameMap.get(l.getQrCodeId());
                return contains(school, kw)
                    || contains(nameMap.get(l.getFromUserid()), kw)
                    || contains(nameMap.get(l.getToUserid()), kw)
                    || contains(l.getReason(), kw);
            }).toList();
        }

        model.addAttribute("logs", logs);
        model.addAttribute("schoolNameMap", schoolNameMap);
        model.addAttribute("nameMap", nameMap);
        model.addAttribute("range", range);
        model.addAttribute("start", window.start().toLocalDate().toString());
        model.addAttribute("end", window.end().toLocalDate().toString());
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("maxRows", MAX_ROWS);
        return "qrcode/rotate-logs";
    }

    private boolean contains(String value, String kw) {
        return value != null && value.contains(kw);
    }

    /** 时间窗口解析：today / 7d / 30d / custom */
    private Window resolveWindow(String range, String start, String end) {
        LocalDateTime now = LocalDateTime.now();
        // 上界放宽 1 秒：DB 时间戳按微秒/秒舍入可能把 created_at 进位到 now 之后，
        // 上界严格取 now 会漏掉"秒末"写入的日志，导致轮换日志列表间歇性少一条。
        LocalDateTime upperBound = now.plusSeconds(1);
        return switch (range == null ? "today" : range) {
            case "7d" -> new Window(now.minusDays(7), upperBound);
            case "30d" -> new Window(now.minusDays(30), upperBound);
            case "custom" -> new Window(parseStart(start, now), parseEnd(end, upperBound));
            default -> new Window(now.toLocalDate().atStartOfDay(), upperBound);
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
