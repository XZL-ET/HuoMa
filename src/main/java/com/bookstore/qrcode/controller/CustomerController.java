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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.time.Instant;
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
@Slf4j
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

    // 企微员工名单缓存（避免每次请求都调企微 API）
    private volatile Map<String, String> cachedAgentNameMap;
    private volatile Instant agentNameCacheExpiry = Instant.EPOCH;
    private static final long AGENT_NAME_CACHE_TTL_SEC = 300; // 5 分钟

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
        if (keyword != null && keyword.isBlank()) keyword = null;
        if (schoolId != null && schoolId.isBlank()) schoolId = null;
        if (currentAgent != null && currentAgent.isBlank()) currentAgent = null;
        if (status != null && status.isBlank()) status = null;

        // ---- 页码/每页条数校验，防止非法值导致 PageRequest 抛异常 ----
        if (page < 0) page = 0;
        if (size < 1) size = 20;
        if (size > 200) size = 200; // 单页上限 200 条

        // ---- 智能输入转换：学校名 → schoolId，员工名 → userid ----
        // 用户可能输入学校名（如"前进小学"）而非 schoolId（如"SCH20260..."），
        // 也可能是 userid 格式。优先精确匹配，失败时模糊搜索学校名。
        // 使用缓存避免重复 findAll()
        List<QrCode> allQrCodes = qrCodeRepo.findAll();
        List<Agent> allAgents = agentRepo.findAll();

        if (schoolId != null) {
            schoolId = resolveSchoolId(schoolId, allQrCodes);
        }
        if (currentAgent != null) {
            currentAgent = resolveAgentUserid(currentAgent, allAgents);
        }

        // ---- 状态参数解析 ----
        Customer.CustomerStatus cs = null;
        if (status != null && !status.isEmpty()) {
            try { cs = Customer.CustomerStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // ---- 执行分页搜索 ----
        Page<Customer> customers = customerService.search(
            keyword, schoolId, currentAgent, cs,
            startTime, endTime,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "addTime")));
        model.addAttribute("customers", customers);
        model.addAttribute("keyword", keyword);
        model.addAttribute("schoolId", schoolId);
        model.addAttribute("currentAgent", currentAgent);
        model.addAttribute("status", status);
        model.addAttribute("startTime", startTime);
        model.addAttribute("endTime", endTime);

        // ---- 统计数据 ----
        model.addAttribute("total", customerService.countTotal());
        model.addAttribute("todayCount", customerService.countToday());

        // ---- 员工列表（用于下拉筛选） ----
        model.addAttribute("agents", allAgents);
        // 活码列表（用于下拉筛选）
        model.addAttribute("qrCodes", allQrCodes);

        // ---- 构建 userid → 姓名 映射（带缓存） ----
        Map<String, String> agentNameMap = getCachedAgentNameMap(allAgents);
        model.addAttribute("agentNameMap", agentNameMap);

        // ---- 构建 schoolId → 学校名 映射（复用已加载的 allQrCodes） ----
        Map<String, String> schoolNameMap = allQrCodes.stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("schoolNameMap", schoolNameMap);

        // 搜索框回显：把 schoolId/currentAgent 转成可读名称
        model.addAttribute("schoolDisplay", schoolId != null
            ? schoolNameMap.getOrDefault(schoolId, schoolId) : null);
        model.addAttribute("agentDisplay", currentAgent != null
            ? agentNameMap.getOrDefault(currentAgent, currentAgent) : null);

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

        // 复用缓存，避免重复调企微 API
        List<QrCode> allQrCodes = qrCodeRepo.findAll();
        List<Agent> allAgents = agentRepo.findAll();

        // 构建 schoolId → 学校名 映射
        Map<String, String> schoolNameMap = allQrCodes.stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        model.addAttribute("schoolNameMap", schoolNameMap);

        // 活码列表，用于展示来源活码信息（显示为 "学校名(ID)" 格式，避免与学校字段重复）
        Map<Long, String> qrNameMap = allQrCodes.stream()
            .collect(Collectors.toMap(QrCode::getId,
                q -> q.getSchoolName() + " (" + q.getSchoolId() + ")",
                (a, b) -> a));
        model.addAttribute("qrNameMap", qrNameMap);

        // 构建 userid → 姓名 映射（带缓存）
        Map<String, String> agentNameMap = getCachedAgentNameMap(allAgents);
        model.addAttribute("agentNameMap", agentNameMap);

        return "customer/detail";
    }

    /**
     * GET {@code /customers/export}
     * <p>
     * 导出客户列表为 CSV 文件。支持与列表页相同的筛选条件。
     * 采用分批查询写入，避免一次性加载全部数据导致 OOM。
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
        // 空字符串 → null 规范化
        if (keyword != null && keyword.isBlank()) keyword = null;
        if (schoolId != null && schoolId.isBlank()) schoolId = null;
        if (currentAgent != null && currentAgent.isBlank()) currentAgent = null;
        if (status != null && status.isBlank()) status = null;

        // 智能输入转换（同 list 方法）
        List<QrCode> allQrCodes = qrCodeRepo.findAll();
        List<Agent> allAgents = agentRepo.findAll();
        if (schoolId != null) schoolId = resolveSchoolId(schoolId, allQrCodes);
        if (currentAgent != null) currentAgent = resolveAgentUserid(currentAgent, allAgents);

        Customer.CustomerStatus cs = null;
        if (status != null && !status.isEmpty()) {
            try { cs = Customer.CustomerStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // 构建名称映射（复用缓存）
        Map<String, String> schoolNameMap = allQrCodes.stream()
            .collect(Collectors.toMap(QrCode::getSchoolId, QrCode::getSchoolName, (a, b) -> a));
        Map<String, String> agentNameMap = getCachedAgentNameMap(allAgents);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("customers.csv").build().toString());

        PrintWriter w = new PrintWriter(response.getOutputStream(), true, StandardCharsets.UTF_8);
        // UTF-8 BOM（U+FEFF），确保 Excel 正确识别中文
        w.write('﻿');
        w.println("ID,昵称,企微ID,类型,接待员,服务老师,学校,来源活码,添加时间,状态");

        // 分批查询写入，每批 500 条，避免 OOM
        int batchSize = 500;
        int pageNum = 0;
        Page<Customer> batch;
        int totalWritten = 0;
        do {
            batch = customerService.search(keyword, schoolId, currentAgent, cs,
                startTime, endTime, PageRequest.of(pageNum++, batchSize,
                    Sort.by(Sort.Direction.DESC, "addTime")));
            for (Customer c : batch.getContent()) {
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
                totalWritten++;
            }
        } while (batch.hasNext());

        w.flush();
        log.info("CSV 导出完成: {} 条记录, keyword={}, schoolId={}, currentAgent={}, status={}",
            totalWritten, keyword, schoolId, currentAgent, status);
    }

    /** CSV 字段转义：双引号、换行符、回车符 */
    private static String csvEscape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
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
        log.info("客户数据修复完成: 共修复 {} 条", repaired);
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
        int updated = 0;
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            // parseAndCheck 保证 errcode=0
            for (JsonNode u : result.get("userlist")) {
                String userid = u.get("userid").asText();
                String name = u.get("name").asText();
                updated += agentRepo.findById(userid).map(agent -> {
                    // 仅当本地 name 仍为 userid（未设置）时更新，不覆盖已手动配置的名称
                    if (userid.equals(agent.getName())) {
                        agent.setName(name);
                        agentRepo.save(agent);
                        return 1;
                    }
                    return 0;
                }).orElse(0);
            }
            log.info("员工姓名同步完成: 更新 {} 人", updated);
            // 清除缓存，下次请求重新加载
            agentNameCacheExpiry = Instant.EPOCH;
        } catch (Exception e) {
            log.warn("同步员工姓名失败，将在下次请求时重试", e);
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
        // 校验接待员是否存在
        if (!agentRepo.existsById(agentUserid)) {
            log.warn("创建测试客户失败: 接待员不存在 userid={}", agentUserid);
            return "redirect:/customers";
        }

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

        log.info("创建测试客户: name={}, agentUserid={}, schoolId={}", name, agentUserid, schoolId);
        return "redirect:/customers";
    }

    // ==================== 智能输入解析 ====================

    /**
     * 智能解析学校输入：支持 schoolId 精确匹配、学校名精确匹配、学校名模糊匹配。
     */
    private String resolveSchoolId(String input, List<QrCode> allQrCodes) {
        // 1) 精确匹配 schoolId
        for (QrCode q : allQrCodes) {
            if (input.equals(q.getSchoolId())) return input;
        }
        // 2) 精确匹配学校名
        for (QrCode q : allQrCodes) {
            if (input.equals(q.getSchoolName())) return q.getSchoolId();
        }
        // 3) 模糊匹配学校名（包含）
        for (QrCode q : allQrCodes) {
            if (q.getSchoolName() != null && q.getSchoolName().contains(input)) return q.getSchoolId();
        }
        // 4) 无匹配 → 原样返回
        return input;
    }

    /**
     * 智能解析员工输入：支持 userid 精确匹配、姓名精确匹配、姓名模糊匹配。
     */
    private String resolveAgentUserid(String input, List<Agent> allAgents) {
        // 1) 精确匹配 userid
        for (Agent a : allAgents) {
            if (input.equals(a.getUserid())) return input;
        }
        // 2) 精确匹配姓名
        for (Agent a : allAgents) {
            if (input.equals(a.getName())) return a.getUserid();
        }
        // 3) 模糊匹配姓名（包含）
        for (Agent a : allAgents) {
            if (a.getName() != null && a.getName().contains(input)) return a.getUserid();
        }
        // 4) 无匹配 → 原样返回
        return input;
    }

    // ==================== 名称缓存 ====================

    /**
     * 获取 agentNameMap（带缓存，5 分钟 TTL）。
     * 优先从企微 API 获取，失败时回退到本地 Agent 表。
     */
    private Map<String, String> getCachedAgentNameMap(List<Agent> allAgents) {
        if (cachedAgentNameMap != null && Instant.now().isBefore(agentNameCacheExpiry)) {
            return cachedAgentNameMap;
        }
        Map<String, String> map = new HashMap<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            // parseAndCheck 保证 errcode=0
            for (JsonNode u : result.get("userlist")) {
                map.put(u.get("userid").asText(), u.get("name").asText());
            }
        } catch (Exception e) {
            log.warn("从企微 API 获取员工列表失败，回退到本地 Agent 表: {}", e.getMessage());
        }
        // fallback：企微 API 未返回的（或调用失败），从本地 Agent 表补充
        for (Agent a : allAgents) {
            map.putIfAbsent(a.getUserid(), a.getName());
        }
        cachedAgentNameMap = map;
        agentNameCacheExpiry = Instant.now().plusSeconds(AGENT_NAME_CACHE_TTL_SEC);
        return map;
    }
}
