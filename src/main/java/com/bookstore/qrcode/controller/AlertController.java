package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 异常告警页面控制器。
 * <p>
 * 处理异常告警相关页面的请求，例如员工超上限、轮换异常等告警信息的展示。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/alerts")
public class AlertController {

    /**
     * GET {@code /alerts}
     * <p>
     * 跳转到异常告警列表页，展示系统中产生的各类告警记录。
     * </p>
     *
     * @param model Spring MVC 模型，用于向视图传递数据
     * @return 视图路径 {@code alert/list}
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "异常告警");
        return "alert/list";
    }
}
