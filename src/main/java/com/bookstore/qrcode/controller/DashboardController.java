package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 数据看板页面控制器。
 * <p>
 * 处理数据看板相关页面的请求，提供核心指标的可视化展示入口。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    /**
     * GET {@code /dashboard}
     * <p>
     * 跳转到数据看板首页，渲染核心运营指标面板（如客户总量、今日新增、员工接待量等）。
     * </p>
     *
     * @param model Spring MVC 模型，用于向视图传递数据
     * @return 视图路径 {@code dashboard/index}
     */
    @GetMapping
    public String index(Model model) {
        model.addAttribute("title", "数据看板");
        return "dashboard/index";
    }
}
