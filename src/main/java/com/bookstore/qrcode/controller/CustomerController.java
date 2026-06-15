package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.service.CustomerService;
import com.bookstore.qrcode.service.AgentBindService;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.TagRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户管理页面控制器。
 * <p>
 * 处理客户列表、客户详情、数据修复、员工名称同步以及测试客户创建等页面的请求。
 * 依赖 {@link CustomerService} 进行核心业务操作，通过 {@link WecomApiClient} 调用企业微信 API
 * 获取员工信息，并结合 {@link AgentBindService} 完成客户分配与日接计数更新。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final AgentBindService agentBindService;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final TagRepository tagRepo;
    private final WecomApiClient wecomApiClient;

    /**
     * GET {@code /customers}
     * <p>
     * 客户列表页。支持按关键字、学校、当前接待员工、客户状态以及时间范围进行筛选。
     * 同时向视图传递以下辅助数据以供前台渲染：
     * </p>
     * <ul>
     *   <li>客户分页结果集</li>
     *   <li>所有员工列表及 userid→姓名 映射（优先从企微 API 获取，失败时回退到本地表）</li>
     *   <li>schoolId→学校名称 映射</li>
     *   <li>客户总量和今日新增计数</li>
     * </ul>
     *
     * @param keyword      搜索关键字（客户名称 / 手机号等，模糊匹配）
     * @param schoolId     学校 ID，按学校筛选
     * @param currentAgent 当前接待员工 userid
     * @param status       客户状态字符串，对应 {@link Customer.CustomerStatus} 枚举名
     * @param startTime    创建时间区间起始（ISO 日期时间格式）
     * @param endTime      创建时间区间结束（ISO 日期时间格式）
     * @param page         页码，从 0 开始
     * @param size         每页条数，默认 20
     * @param model        Spring MVC 模型
     * @return 视图路径 {@code customer/list}
     */
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
        // ---- 空字符串 → null 规范化 ----
        // 前端下拉框未选中时会发送空字符串，JPQL 会把 "" 当作有效筛选值去匹配
        // （WHERE schoolId = '' 永远匹配不到），导致翻页后结果为空
        if (keyword != null && keyword.isBlank()) keyword = null;
        if (schoolId != null && schoolId.isBlank()) schoolId = null;
        if (currentAgent != null && currentAgent.isBlank()) currentAgent = null;
        if (status != null && status.isBlank()) status = null;

        // ---- 状态参数解析 ----
        Customer.CustomerStatus cs = null;
        if (status != null && !status.isEmpty()) {
            try { cs = Customer.CustomerStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // ---- 执行分页搜索 ----
        Page<Customer> customers = customerService.search(
            keyword, schoolId, currentAgent, cs,
            startTime, endTime, PageRequest.of(page, size));
        model.addAttribute("customers", customers);
        model.addAttribute("keyword", keyword);
        model.addAttribute("schoolId", schoolId);
        model.addAttribute("currentAgent", currentAgent);
        model.addAttribute("status", status);

        // ---- 统计数据 ----
        model.addAttribute("total", customerService.countTotal());
        model.addAttribute("todayCount", customerService.countToday());

        // ---- 员工列表（用于下拉筛选） ----
        model.addAttribute("agents", agentRepo.findAll());
        // 活码列表（用于下拉筛选）
        model.addAttribute("qrCodes", qrCodeRepo.findAll());

        // ---- 构建 userid → 姓名 映射 ----
        // 优先从企业微信 API 获取员工姓名，确保名称与企微一致
        Map<String, String> agentNameMap = new HashMap<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                for (JsonNode u : result.get("userlist")) {
                    agentNameMap.put(u.get("userid").asText(), u.get("name").asText());
                }
            }
        } catch (Exception ignored) {
            // API 调用失败（网络/超时/权限），静默处理
        }
        // fallback：企微 API 未返回的员工，从本地 Agent 表补充
        for (Agent a : agentRepo.findAll()) {
            agentNameMap.putIfAbsent(a.getUserid(), a.getName());
        }
        model.addAttribute("agentNameMap", agentNameMap);

        // ---- 构建 schoolId → 学校名 映射 ----
        // 用于在列表中展示学校名称而非 ID
        Map<String, String> schoolNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("schoolNameMap", schoolNameMap);

        return "customer/list";
    }

    /**
     * GET {@code /customers/{id}}
     * <p>
     * 客户详情页。展示指定客户的基本信息及已打标签。
     * 同时构建 tagId→标签名 映射以便前台展示。
     * </p>
     *
     * @param id    客户 ID（主键）
     * @param model Spring MVC 模型
     * @return 视图路径 {@code customer/detail}
     * @see CustomerService#getById(Long)
     * @see CustomerService#getTags(Long)
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Customer customer = customerService.getById(id);
        model.addAttribute("customer", customer);
        model.addAttribute("tags", customerService.getTags(id));

        // 构建 tagId → 标签名 映射
        Map<Long, String> tagNameMap = new HashMap<>();
        for (Tag t : tagRepo.findAll()) {
            tagNameMap.put(t.getId(), t.getName());
        }
        model.addAttribute("tagNameMap", tagNameMap);

        // 构建 schoolId → 学校名 映射
        Map<String, String> schoolNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("schoolNameMap", schoolNameMap);

        // 活码列表，用于展示来源活码信息
        Map<Long, String> qrNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("qrNameMap", qrNameMap);

        return "customer/detail";
    }

    /**
     * GET {@code /customers/export}
     * <p>
     * 导出客户列表为 CSV 文件。支持与列表页相同的筛选条件。
     * 输出 UTF-8 BOM 头确保 Excel 正确识别中文。
     * </p>
     *
     * @param response {@link HttpServletResponse}，CSV 内容直接写入其输出流
     */
    @GetMapping("/export")
    public void exportCsv(@RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String schoolId,
                          @RequestParam(required = false) String currentAgent,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                          @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                          HttpServletResponse response) throws IOException {
        // 空字符串 → null 规范化（同 list 方法，防止空字符串被 JPQL 当作有效条件）
        if (keyword != null && keyword.isBlank()) keyword = null;
        if (schoolId != null && schoolId.isBlank()) schoolId = null;
        if (currentAgent != null && currentAgent.isBlank()) currentAgent = null;
        if (status != null && status.isBlank()) status = null;

        Customer.CustomerStatus cs = null;
        if (status != null && !status.isEmpty()) {
            try { cs = Customer.CustomerStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // 不限制条数，导出所有匹配结果
        Page<Customer> customers = customerService.search(
            keyword, schoolId, currentAgent, cs,
            startTime, endTime, Pageable.unpaged());

        // 构建名称映射
        Map<String, String> schoolNameMap = qrCodeRepo.findAll().stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));

        Map<String, String> agentNameMap = new HashMap<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                for (JsonNode u : result.get("userlist")) {
                    agentNameMap.put(u.get("userid").asText(), u.get("name").asText());
                }
            }
        } catch (Exception ignored) {}
        for (Agent a : agentRepo.findAll()) {
            agentNameMap.putIfAbsent(a.getUserid(), a.getName());
        }

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("customers.csv").build().toString());

        PrintWriter w = new PrintWriter(response.getOutputStream(), true, StandardCharsets.UTF_8);
        // UTF-8 BOM（U+FEFF），确保 Excel 正确识别中文
        w.write('﻿');
        w.println("ID,昵称,企微ID,类型,接待员,服务老师,学校,来源活码,添加时间,状态");

        for (Customer c : customers.getContent()) {
            w.printf("%d,\"%s\",\"%s\",%s,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s%n",
                c.getId(),
                csvEscape(c.getName()),
                csvEscape(c.getExternalUserid()),
                c.getType() == 1 ? "微信" : "企业微信",
                csvEscape(agentNameMap.getOrDefault(c.getAddedAgent(), c.getAddedAgent())),
                csvEscape(agentNameMap.getOrDefault(c.getCurrentAgent(), c.getCurrentAgent())),
                csvEscape(schoolNameMap.getOrDefault(c.getSchoolId(), c.getSchoolId())),
                c.getSourceQrId() != null ? c.getSourceQrId().toString() : "",
                c.getAddTime() != null ? c.getAddTime().toString() : "",
                c.getStatus() != null ? c.getStatus().name() : "");
        }
        w.flush();
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    /**
     * POST {@code /customers/repair-data}
     * <p>
     * 修复存量客户缺失的数据字段（unionid / avatar / name）。
     * 遍历所有缺失数据的客户记录，尝试从企业微信 API 补全。
     * </p>
     *
     * @return 重定向到客户列表页
     * @see CustomerService#repairCustomerData()
     */
    @PostMapping("/repair-data")
    public String repairCustomerData() {
        int repaired = customerService.repairCustomerData();
        return "redirect:/customers";
    }

    /**
     * POST {@code /customers/sync-agent-names}
     * <p>
     * 从企业微信 API 同步员工姓名到本地 {@link Agent} 表。
     * 仅当本地姓名字段与 userid 相同时视为未设置，才进行覆盖更新，避免覆盖已手动修改的名称。
     * </p>
     *
     * @return 重定向到客户列表页
     * @see WecomApiClient#getUserSimplelist()
     */
    @PostMapping("/sync-agent-names")
    public String syncAgentNames() {
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                for (JsonNode u : result.get("userlist")) {
                    String userid = u.get("userid").asText();
                    String name = u.get("name").asText();
                    agentRepo.findById(userid).ifPresent(agent -> {
                        // 仅当本地 name 仍为 userid（未设置）时更新，不覆盖已手动配置的名称
                        if (userid.equals(agent.getName())) {
                            agent.setName(name);
                            agentRepo.save(agent);
                        }
                    });
                }
            }
        } catch (Exception e) {
            // 忽略同步失败，不影响主流程
        }
        return "redirect:/customers";
    }

    /**
     * POST {@code /customers/create-test}
     * <p>
     * 创建一条测试客户记录（用于开发调试），并同步更新对应员工的日接待计数。
     * 测试客户的外部 ID 以 {@code test_} 前缀加时间戳生成，避免与真实客户冲突。
     * 如果指定了活码，则自动从中解析出学校 ID；相关逻辑与真实客户扫码进线流程保持一致。
     * </p>
     *
     * @param agentUserid 分配的接待员工 userid（必填）
     * @param qrCodeId    活码 ID（可选），用于关联学校
     * @param name        客户名称，默认为 "测试客户"
     * @return 重定向到客户列表页
     * @see CustomerService#createManual(String, String, String, String, Long)
     * @see AgentBindService#incrementDailyCount(String, String)
     */
    @PostMapping("/create-test")
    public String createTest(@RequestParam String agentUserid,
                             @RequestParam(required = false) String qrCodeId,
                             @RequestParam(defaultValue = "测试客户") String name) {
        // 生成唯一的外部 ID，以 "test_" 前缀标记为测试数据
        String externalId = "test_" + System.currentTimeMillis();

        // 根据活码 ID 解析学校 ID
        String schoolId = null;
        Long qrId = null;
        if (qrCodeId != null && !qrCodeId.isBlank()) {
            try {
                qrId = Long.parseLong(qrCodeId);
                QrCode qr = qrCodeRepo.findById(qrId).orElse(null);
                if (qr != null) schoolId = qr.getSchoolId();
            } catch (NumberFormatException ignored) {
                // 传入了非数字的 qrCodeId，忽略
            }
        }

        // 创建客户记录
        customerService.createManual(name, externalId, agentUserid, schoolId, qrId);

        // 同步更新员工日接待计数，触发轮换检查（与真实客户分配后逻辑一致）
        if (schoolId != null) {
            agentBindService.incrementDailyCount(agentUserid, schoolId);
        }

        return "redirect:/customers";
    }
}
