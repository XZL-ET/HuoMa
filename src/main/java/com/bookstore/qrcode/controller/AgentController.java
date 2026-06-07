package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/agents")
public class AgentController {

    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "员工管理");
        return "agent/list";
    }
}
