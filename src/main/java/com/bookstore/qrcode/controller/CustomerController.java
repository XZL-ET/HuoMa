package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/customers")
public class CustomerController {

    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "客户管理");
        return "customer/list";
    }
}
