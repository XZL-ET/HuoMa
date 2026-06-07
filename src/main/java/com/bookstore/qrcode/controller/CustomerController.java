package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

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
        return "customer/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Customer customer = customerService.getById(id);
        model.addAttribute("customer", customer);
        model.addAttribute("tags", customerService.getTags(id));
        return "customer/detail";
    }
}
