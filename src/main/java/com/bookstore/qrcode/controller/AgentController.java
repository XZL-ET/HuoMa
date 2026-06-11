package com.bookstore.qrcode.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 员工管理页面控制器。
 * <p>
 * 处理员工（接待人员）管理相关页面的请求，包括员工列表展示、日接上限配置等。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/agents")
public class AgentController {

    /**
     * GET {@code /agents}
     * <p>
     * 跳转到员工管理列表页，展示所有已添加的员工及其日接上限、当前接待量等信息。
     * </p>
     *
     * @param model Spring MVC 模型，用于向视图传递数据
     * @return 视图路径 {@code agent/list}
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "员工管理");
        return "agent/list";
    }
}
