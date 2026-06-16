package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.UserRepository;
import com.bookstore.qrcode.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 用户管理控制器。
 * <p>
 * 仅 admin 角色可访问。提供用户列表、创建、启用/禁用、删除和密码修改功能。
 * 所有操作均写入审计日志。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.1
 */
@Slf4j
@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;

    /**
     * GET /users — 用户列表（分页）。
     */
    @GetMapping
    public String list(@PageableDefault(size = 20) Pageable pageable,
                       Model model) {
        Page<User> page = userRepository.findAll(pageable);
        model.addAttribute("page", page);
        model.addAttribute("title", "用户管理");
        return "user/list";
    }

    /**
     * GET /users/create — 创建用户表单。
     */
    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("title", "创建用户");
        return "user/create";
    }

    /**
     * POST /users/create — 保存新用户。
     */
    @PostMapping("/create")
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam String displayName,
                         @RequestParam(defaultValue = "operator") String role,
                         Authentication auth,
                         RedirectAttributes ra) {
        if (userRepository.existsByUsername(username)) {
            ra.addFlashAttribute("error", "用户名已存在: " + username);
            return "redirect:/users/create";
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(displayName)
                .role(User.UserRole.valueOf(role))
                .enabled(true)
                .build();
        userRepository.save(user);

        operationLogService.log(auth.getName(), "create", "user",
                user.getId().toString(), "创建用户 " + username);
        log.info("用户 {} 已被 {} 创建", username, auth.getName());
        ra.addFlashAttribute("message", "用户 " + username + " 创建成功");
        return "redirect:/users";
    }

    /**
     * POST /users/{id}/toggle — 启用/禁用用户。
     */
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         Authentication auth,
                         RedirectAttributes ra) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            ra.addFlashAttribute("error", "用户不存在");
            return "redirect:/users";
        }

        // 不允许禁用自己
        if (user.getUsername().equals(auth.getName())) {
            ra.addFlashAttribute("error", "不能禁用当前登录用户");
            return "redirect:/users";
        }

        user.setEnabled(!user.getEnabled());
        userRepository.save(user);

        String action = user.getEnabled() ? "启用" : "禁用";
        operationLogService.log(auth.getName(), action.toLowerCase(), "user",
                id.toString(), action + "用户 " + user.getUsername());
        ra.addFlashAttribute("message", "用户 " + user.getUsername() + " 已" + action);
        return "redirect:/users";
    }

    /**
     * POST /users/{id}/delete — 删除用户。
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         Authentication auth,
                         RedirectAttributes ra) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            ra.addFlashAttribute("error", "用户不存在");
            return "redirect:/users";
        }

        // 不允许删除自己
        if (user.getUsername().equals(auth.getName())) {
            ra.addFlashAttribute("error", "不能删除当前登录用户");
            return "redirect:/users";
        }

        String username = user.getUsername();
        userRepository.delete(user);

        operationLogService.log(auth.getName(), "delete", "user",
                id.toString(), "删除用户 " + username);
        log.info("用户 {} 已被 {} 删除", username, auth.getName());
        ra.addFlashAttribute("message", "用户 " + username + " 已删除");
        return "redirect:/users";
    }

    /**
     * POST /users/{id}/change-password — 修改密码。
     */
    @PostMapping("/{id}/change-password")
    public String changePassword(@PathVariable Long id,
                                 @RequestParam String newPassword,
                                 Authentication auth,
                                 RedirectAttributes ra) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            ra.addFlashAttribute("error", "用户不存在");
            return "redirect:/users";
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        operationLogService.log(auth.getName(), "change_password", "user",
                id.toString(), "修改密码");
        ra.addFlashAttribute("message", "用户 " + user.getUsername() + " 密码已更新");
        return "redirect:/users";
    }
}
