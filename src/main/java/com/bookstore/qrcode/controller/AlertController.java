package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/alerts")
public class AlertController {

    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "异常告警");
        return "alert/list";
    }
}
