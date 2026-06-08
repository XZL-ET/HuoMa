package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.service.CustomerService;
import com.bookstore.qrcode.service.AgentBindService;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final AgentBindService agentBindService;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final WecomApiClient wecomApiClient;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String schoolId,
                       @RequestParam(required = false) String currentAgent,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                       @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        Customer.CustomerStatus cs = null;
        if (status != null && !status.isEmpty()) {
            try { cs = Customer.CustomerStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        Page<Customer> customers = customerService.search(
            keyword, schoolId, currentAgent, cs,
            startTime, endTime, PageRequest.of(page, size));
        model.addAttribute("customers", customers);
        model.addAttribute("keyword", keyword);
        model.addAttribute("schoolId", schoolId);
        model.addAttribute("currentAgent", currentAgent);
        model.addAttribute("status", status);
        model.addAttribute("total", customerService.countTotal());
        model.addAttribute("todayCount", customerService.countToday());
        model.addAttribute("agents", agentRepo.findAll());
        model.addAttribute("qrCodes", qrCodeRepo.findAll());

        // 构建 userid → 姓名 映射
        Map<String, String> agentNameMap = new HashMap<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                for (JsonNode u : result.get("userlist")) {
                    agentNameMap.put(u.get("userid").asText(), u.get("name").asText());
                }
            }
        } catch (Exception ignored) {}
        // fallback: 从 Agent 表补充
        for (Agent a : agentRepo.findAll()) {
            agentNameMap.putIfAbsent(a.getUserid(), a.getName());
        }
        model.addAttribute("agentNameMap", agentNameMap);

        // 构建 schoolId → 学校名 映射
        Map<String, String> schoolNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("schoolNameMap", schoolNameMap);

        return "customer/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Customer customer = customerService.getById(id);
        model.addAttribute("customer", customer);
        model.addAttribute("tags", customerService.getTags(id));
        return "customer/detail";
    }

    @PostMapping("/create-test")
    public String createTest(@RequestParam String agentUserid,
                             @RequestParam(required = false) String qrCodeId,
                             @RequestParam(defaultValue = "测试客户") String name) {
        String externalId = "test_" + System.currentTimeMillis();
        String schoolId = null;
        Long qrId = null;
        if (qrCodeId != null && !qrCodeId.isBlank()) {
            try {
                qrId = Long.parseLong(qrCodeId);
                QrCode qr = qrCodeRepo.findById(qrId).orElse(null);
                if (qr != null) schoolId = qr.getSchoolId();
            } catch (NumberFormatException ignored) {}
        }
        customerService.createManual(name, externalId, agentUserid, schoolId, qrId);

        // 同步更新员工日计数 + 触发轮换检查
        if (schoolId != null) {
            agentBindService.incrementDailyCount(agentUserid, schoolId);
        }

        return "redirect:/customers";
    }
}
