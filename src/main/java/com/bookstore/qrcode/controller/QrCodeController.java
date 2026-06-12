package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import com.bookstore.qrcode.repository.TagRepository;
import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.service.EmployeeSyncService;
import com.bookstore.qrcode.service.QrCodeService;
import com.bookstore.qrcode.service.QrImageService;
import com.bookstore.qrcode.service.TagService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 活码管理页面控制器。
 *
 * <p>处理活码的 CRUD、批量导入、轮换、后备池、样式管理等全部操作。
 * 所有页面路由均挂载在 {@code /qrcodes} 路径下，返回 Thymeleaf 模板视图。
 *
 * <p>核心依赖：
 * <ul>
 *   <li>{@link QrCodeService} —— 活码业务逻辑（创建、搜索、轮换、同步等）</li>
 *   <li>{@link QrImageService} —— 活码二维码图片生成</li>
 *   <li>{@link WecomApiClient} —— 企业微信 API 调用（获取成员列表等）</li>
 *   <li>{@link QrAgentRepository} —— 活码联系人数据访问</li>
 *   <li>{@link GlobalAgentPoolRepository} —— 全局池数据访问</li>
 *   <li>{@link CustomerRepository} —— 客户数据访问（今日新增统计）</li>
 *   <li>{@link QrCodeRepository} —— 活码数据访问（城市/区县去重列表）</li>
 *   <li>{@link QrRotateLogRepository} —— 轮换日志数据访问</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Slf4j
@Controller
@RequestMapping("/qrcodes")
@RequiredArgsConstructor
public class QrCodeController {

    private final QrCodeService qrCodeService;
    private final WecomApiClient wecomApiClient;
    private final QrAgentRepository qrAgentRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final TagRepository tagRepo;
    private final QrImageService qrImageService;
    private final TagService tagService;
    private final EmployeeRepository employeeRepo;
    private final EmployeeSyncService employeeSyncService;

    // 企微标签缓存（避免每次打开创建页都调企微接口）
    private volatile java.time.LocalDateTime lastTagSyncTime = null;
    private volatile List<String> cachedCityOptions = null;
    private volatile List<String> cachedDistrictOptions = null;
    private volatile List<String> cachedSchoolOptions = null;
    private static final long TAG_CACHE_MINUTES = 10;

    /**
     * 活码列表页 —— 支持关键词搜索、城市/区县/状态筛选、分页。
     *
     * <p>附加统计：
     * <ul>
     *   <li>{@code agentCountMap}：每个活码的 "值守人数/后备人数" 字符串，供列表页展示</li>
     *   <li>{@code todayCountMap}：每个活码的当日新增客户数</li>
     * </ul>
     *
     * <p>城市和区县下拉列表从已有活码数据中动态去重获取（{@link QrCodeRepository#findDistinctRegionCity()} /
     * {@link QrCodeRepository#findDistinctRegionDistrict()}），确保只展示有数据的筛选项。
     *
     * @param keyword  搜索关键词（匹配学校名称/备注等，由 {@link QrCodeService#search} 处理）
     * @param city     城市筛选（精确匹配 regionCity 字段），可选
     * @param district 区县筛选（精确匹配 regionDistrict 字段），可选
     * @param status   活码状态筛选（{@link QrCode.QrCodeStatus} 枚举名），可选
     * @param page     页码，从 0 开始，默认 0
     * @param size     每页记录数，默认 20
     * @param model    Spring MVC {@link Model}
     * @return 模板视图名 {@code "qrcode/list"}
     */
    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        // ---- 1. 解析状态枚举参数，非法值忽略 ----
        QrCode.QrCodeStatus qrStatus = null;
        if (status != null && !status.isEmpty()) {
            try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // ---- 2. 分页搜索活码（委托 QrCodeService 处理关键词、城市、区县、状态多条件组合） ----
        Page<QrCode> qrCodes = qrCodeService.search(keyword, city, district,
            qrStatus, PageRequest.of(page, size));

        // ---- 3. 构建城市/区县动态筛选下拉列表（从 DB 去重获取，仅展示有活码数据的城市/区县） ----
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        // ---- 4. 计算今日新增客户统计（当天 00:00:00 到当前时刻） ----
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime todayEnd = LocalDateTime.now();

        // key=活码ID, value=今日新增客户数
        Map<Long, Long> todayCountMap = new HashMap<>();
        // key=活码ID, value="值守人数/后备人数" 格式化字符串
        Map<Long, String> agentCountMap = new HashMap<>();

        for (QrCode qr : qrCodes.getContent()) {
            // 4a. 值守数 = 该活码下状态为 active 的联系人数量
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active).size();
            // 4b. 后备数 = 全局池中 standby 员工数量（所有活码共享）
            long backupCount = poolRepo.countByStatus(
                GlobalAgentPool.PoolStatus.standby);
            // 4c. 组装展示字符串："值守数/全局后备数"
            agentCountMap.put(qr.getId(), activeCount + "/" + backupCount);

            // 4d. 查询该活码在今日时间窗口内新增的客户数
            long todayCount = customerRepo.countBySourceQrIdAndAddTimeBetween(
                qr.getId(), todayStart, todayEnd);
            todayCountMap.put(qr.getId(), todayCount);
        }

        // ---- 5. 填充 Model 并返回列表视图 ----
        model.addAttribute("qrCodes", qrCodes);
        model.addAttribute("keyword", keyword);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("status", status);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        model.addAttribute("agentCountMap", agentCountMap);
        model.addAttribute("todayCountMap", todayCountMap);
        return "qrcode/list";
    }

    /**
     * 手动创建活码页面 —— 加载企业微信成员列表。
     *
     * <p>通过 {@link WecomApiClient#getUserSimplelist()} 获取企微通讯录全部成员，
     * 提取 {@code userid} 和 {@code name} 构建下拉列表供前端选择。
     * 若 API 调用失败，设置 {@code loadError} 属性并在页面展示错误提示。
     *
     * @param model Spring MVC {@link Model}
     *              <ul>
     *                <li>{@code userList} —— List&lt;Map&lt;String,String&gt;&gt;，每项含 userid/name</li>
     *                <li>{@code loadError} —— 错误消息字符串（API失败时）</li>
     *              </ul>
     * @return 模板视图名 {@code "qrcode/create"}
     */
    @GetMapping("/create")
    public String createForm(Model model) {
        // ── 员工列表：优先从本地 DB 读取，空库时触发同步 ──
        List<Map<String, String>> userList = buildUserList();
        model.addAttribute("userList", userList);

        // ---- 自动生成建议的学校 ID（格式: SCH + 时间戳，确保唯一） ----
        String suggestedSchoolId = "SCH" + java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        model.addAttribute("suggestedSchoolId", suggestedSchoolId);

        // ---- 调用企微接口获取全部标签（带缓存，10 分钟内不重复调） ----
        JsonNode wecomTagResp = null;
        boolean needApiCall = lastTagSyncTime == null
            || java.time.Duration.between(lastTagSyncTime, java.time.LocalDateTime.now())
                .toMinutes() >= TAG_CACHE_MINUTES;

        if (needApiCall) {
            try {
                wecomTagResp = wecomApiClient.getCorpTagList();
                log.info("企微标签 API 原始响应: {}", wecomTagResp.toString());
                // 同步到本地 DB
                tagService.syncTagsFromWecom(wecomTagResp);
                // 直接从企微标签组提取下拉选项（先提取数据，成功后再更新时间戳）
                var extracted = extractOptionsFromGroups(wecomTagResp);
                cachedCityOptions = extracted.get("city");
                cachedDistrictOptions = extracted.get("district");
                cachedSchoolOptions = extracted.get("school");
                lastTagSyncTime = java.time.LocalDateTime.now();
            } catch (Exception e) {
                log.warn("企微标签同步失败，使用缓存数据: {}", e.getMessage());
            }
        } else {
            log.debug("标签缓存未过期，跳过企微接口调用 (上次同步: {})", lastTagSyncTime);
        }

        // ---- 加载下拉框选项数据 ----
        // 优先从企微标签组名称直接提取；缓存过期后刷新
        try {
            List<String> cityOptions = cachedCityOptions != null
                ? new ArrayList<>(cachedCityOptions) : new ArrayList<>();
            List<String> districtOptions = cachedDistrictOptions != null
                ? new ArrayList<>(cachedDistrictOptions) : new ArrayList<>();
            List<String> schoolOptions = cachedSchoolOptions != null
                ? new ArrayList<>(cachedSchoolOptions) : new ArrayList<>();

            // ── 降级：缓存全为空时用 DB + 命名规则兜底 ──
            if (cityOptions.isEmpty() && districtOptions.isEmpty() && schoolOptions.isEmpty()) {
                log.info("缓存为空，使用 DB 标签命名规则兜底");
                List<Tag> allTags = tagRepo.findAll();
                for (Tag t : allTags) {
                    String name = t.getName();
                    if (name.endsWith("市") || name.endsWith("州") || name.endsWith("盟")) {
                        cityOptions.add(name);
                    } else if (name.endsWith("区") || name.endsWith("县")
                            || name.endsWith("旗") || name.endsWith("乡")) {
                        districtOptions.add(name);
                    } else if (isSchoolName(name)) {
                        schoolOptions.add(name);
                    }
                }
                Collections.sort(cityOptions);
                Collections.sort(districtOptions);
                Collections.sort(schoolOptions);
            }

            log.info("下拉选项统计: city={}, district={}, school={}",
                cityOptions.size(), districtOptions.size(), schoolOptions.size());

            model.addAttribute("cityOptions", cityOptions);
            model.addAttribute("districtOptions", districtOptions);
            model.addAttribute("schoolOptions", schoolOptions);
        } catch (Exception e) {
            // 下拉数据加载失败不影响页面使用，降级为空列表
            log.warn("加载下拉选项数据失败: {}", e.getMessage());
            model.addAttribute("cityOptions", List.of());
            model.addAttribute("districtOptions", List.of());
            model.addAttribute("schoolOptions", List.of());
        }

        return "qrcode/create";
    }

    /**
     * 构建前端员工列表：优先读 DB，DB 为空时触发企微同步。
     *
     * @return {@code List<Map<"userid"|"name", String>>}
     */
    private List<Map<String, String>> buildUserList() {
        // ① 从本地 DB 读取在职员工
        List<com.bookstore.qrcode.entity.Employee> employees = employeeRepo.findAllByActiveTrueOrderByName();
        if (!employees.isEmpty()) {
            List<Map<String, String>> list = new ArrayList<>();
            for (com.bookstore.qrcode.entity.Employee e : employees) {
                list.add(Map.of("userid", e.getUserid(), "name", e.getName()));
            }
            log.info("从 DB 加载员工列表: {} 人", list.size());
            return list;
        }

        // ② DB 为空（首次启动），触发企微同步
        log.info("DB 中无员工数据，触发首次同步");
        try {
            int count = employeeSyncService.syncFromWecom();
            if (count > 0) {
                employees = employeeRepo.findAllByActiveTrueOrderByName();
                List<Map<String, String>> list = new ArrayList<>();
                for (com.bookstore.qrcode.entity.Employee e : employees) {
                    list.add(Map.of("userid", e.getUserid(), "name", e.getName()));
                }
                log.info("首次同步完成，从 DB 加载员工列表: {} 人", list.size());
                return list;
            }
        } catch (Exception e) {
            log.warn("首次员工同步失败: {}", e.getMessage());
        }

        // ③ 同步失败，降级到直接调企微 API
        log.info("DB 同步失败，降级为直接调企微 API");
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            if (result.has("errcode") && result.get("errcode").asInt() != 0) {
                log.warn("企微 API 获取员工列表失败: {}", result.get("errmsg").asText());
                return List.of();
            }
            List<Map<String, String>> list = new ArrayList<>();
            for (JsonNode u : result.get("userlist")) {
                list.add(Map.of("userid", u.get("userid").asText(),
                                "name", u.has("name") ? u.get("name").asText() : ""));
            }
            return list;
        } catch (Exception e) {
            log.error("企微 API 获取员工列表异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 判断标签名是否像一个学校名称（而非年级/班级等子分类）。
     * <p>
     * 学校名称特征：包含 中学/小学/学校/幼儿园/大学/学院，或以"中"结尾（如"一中""二中"等缩写）。
     * 排除"年级"（如"小学一年级"）和"班"（如"一班"）等子分类名称。
     * </p>
     */
    private static boolean isSchoolName(String name) {
        if (name == null) return false;
        // 排除年级/班级级别名称
        if (name.contains("年级") || name.contains("班")) return false;
        // 匹配学校特征：完整词或以"中"结尾的缩写（如"兰州一中"）
        return name.contains("中学") || name.contains("小学")
            || name.contains("学校") || name.contains("幼儿园")
            || name.contains("大学") || name.contains("学院")
            || name.endsWith("中");
    }

    /**
     * 从企微标签列表响应中按标签组名称提取市/区/学校选项。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>标签组名含"学校" → 学校选项</li>
     *   <li>标签组名含"市"/"州"/"盟" → 城市选项</li>
     *   <li>标签组名含"区"/"县"/"旗"/"乡" → 区县选项</li>
     * </ul>
     *
     * @param resp 企微 {@code get_corp_tag_list} 响应
     * @return Map，key 为 city / district / school
     */
    private static Map<String, List<String>> extractOptionsFromGroups(JsonNode resp) {
        List<String> cities = new ArrayList<>();
        List<String> districts = new ArrayList<>();
        List<String> schools = new ArrayList<>();

        if (resp == null || !resp.has("tag_group")) {
            return Map.of("city", cities, "district", districts, "school", schools);
        }

        // 打印企微返回的全部标签组概览（调试用）
        List<String> groupSummaries = new ArrayList<>();

        for (JsonNode group : resp.get("tag_group")) {
            String groupName = group.has("group_name")
                ? group.get("group_name").asText().trim() : "";
            if (!group.has("tag")) continue;

            // 收集该组下所有标签名
            List<String> tagNames = new ArrayList<>();
            for (JsonNode t : group.get("tag")) {
                String name = t.has("name") ? t.get("name").asText().trim() : "";
                if (!name.isEmpty()) tagNames.add(name);
            }

            // 按组名归类
            String matched = null;
            if (groupName.contains("学校")) {
                schools.addAll(tagNames);
                matched = "学校";
            } else if (groupName.contains("市") || groupName.contains("州")
                    || groupName.contains("盟")) {
                cities.addAll(tagNames);
                matched = "城市";
            } else if (groupName.contains("区") || groupName.contains("县")
                    || groupName.contains("旗") || groupName.contains("乡")) {
                districts.addAll(tagNames);
                matched = "区县";
            }
            groupSummaries.add(groupName + "(" + tagNames.size() + "个)→" + (matched != null ? matched : "未归类"));
        }

        log.info("企微标签组总数: {}, 明细: {}",
            groupSummaries.size(), String.join(" | ", groupSummaries));

        Collections.sort(cities);
        Collections.sort(districts);
        Collections.sort(schools);
        return Map.of("city", cities, "district", districts, "school", schools);
    }

    /**
     * 提交创建活码请求。
     *
     * <p>POST /qrcodes/create —— 接收 {@link QrCodeCreateRequest} 表单数据，
     * 委托 {@link QrCodeService#create(QrCodeCreateRequest)} 完成活码及初始联系人的创建。
     *
     * @param req      创建请求 DTO，由 Spring 自动绑定表单字段
     * @param redirect {@link RedirectAttributes}，用于重定向后传递 flash 消息
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码列表页 {@code "redirect:/qrcodes"}
     */
    @PostMapping("/create")
    public String create(@ModelAttribute QrCodeCreateRequest req,
                          RedirectAttributes redirect) {
        try {
            // 如果学校ID为空（用户清掉了自动生成的值），自动补生成一个
            if (req.getSchoolId() == null || req.getSchoolId().isBlank()) {
                String autoId = "SCH" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                req.setSchoolId(autoId);
                log.info("学校ID为空，已自动生成: {}", autoId);
            }
            qrCodeService.create(req);
            redirect.addFlashAttribute("message", "活码创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /**
     * 批量导入页面 —— 提供 Excel 文件上传入口。
     *
     * <p>GET /qrcodes/batch-import
     *
     * @return 模板视图名 {@code "qrcode/batch-import"}
     */
    @GetMapping("/batch-import")
    public String batchImportForm() {
        return "qrcode/batch-import";
    }

    /**
     * 提交批量导入文件，启动异步导入任务。
     *
     * <p>POST /qrcodes/batch-import —— 接收上传的 Excel 文件，
     * 委托 {@link QrCodeService#asyncBatchImport(MultipartFile)} 异步解析并创建活码，
     * 返回异步任务 ID 供前端轮询进度。
     *
     * @param file     上传的 Excel 文件（{@link MultipartFile}），包含活码批量数据
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 含 taskId 的成功提示</li>
     *                   <li>{@code error} —— 文件解析失败时的错误信息</li>
     *                 </ul>
     * @return 成功时重定向到进度查询页 {@code "redirect:/qrcodes/batch-import/progress?taskId=..."}；
     *         失败时返回导入页
     */
    @PostMapping("/batch-import")
    public String batchImport(@RequestParam("file") MultipartFile file,
                               RedirectAttributes redirect) {
        try {
            String taskId = qrCodeService.asyncBatchImport(file);
            redirect.addFlashAttribute("message", "导入任务已启动");
            // 携带 taskId 跳转到进度页，前端通过 JS 轮询进度接口
            return "redirect:/qrcodes/batch-import/progress?taskId=" + taskId;
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/qrcodes/batch-import";
        }
    }

    /**
     * 批量导入进度页面 —— 以 taskId 初始化进度展示。
     *
     * <p>GET /qrcodes/batch-import/progress?taskId=...
     *
     * @param taskId 异步任务 ID（由 {@link #batchImport} 创建）
     * @param model  Spring MVC {@link Model}，传入 taskId 供前端轮询使用
     * @return 模板视图名 {@code "qrcode/batch-import"}
     */
    @GetMapping("/batch-import/progress")
    public String batchImportProgress(@RequestParam String taskId, Model model) {
        model.addAttribute("taskId", taskId);
        return "qrcode/batch-import";
    }

    /**
     * 查询批量导入进度（JSON 接口）。
     *
     * <p>GET /qrcodes/batch-import/progress/{taskId} —— 返回 JSON 格式的进度信息，
     * 由前端定时轮询以更新进度条和结果展示。
     *
     * @param taskId 异步任务 ID
     * @return Map 包含进度信息，具体字段由 {@link QrCodeService#getBatchImportProgress(String)} 定义
     *         （通常包含 total/success/fail/errors 等字段）
     */
    @GetMapping("/batch-import/progress/{taskId}")
    @ResponseBody
    public Map<Object, Object> getImportProgress(@PathVariable String taskId) {
        return qrCodeService.getBatchImportProgress(taskId);
    }

    /**
     * 活码详情页 —— 加载活码的全部关联数据。
     *
     * <p>GET /qrcodes/{id} —— 聚合加载以下信息：
     * <ol>
     *   <li>活码基本信息（{@link QrCode}）</li>
     *   <li>接待员列表（role 为 receptionist 或 dual 的联系人）</li>
     *   <li>服务老师列表（role 为 service 的联系人）</li>
     *   <li>后备接待员列表（{@link GlobalAgentPool}）</li>
     *   <li>企业微信全员列表（用于后备新增/联系人新增弹窗的下拉选择）</li>
     *   <li>已在活码中的联系人 userid 集合（用于前端过滤已添加用户）</li>
     *   <li>最近 20 条轮换日志</li>
     * </ol>
     *
     * @param id    活码 ID
     * @param model Spring MVC {@link Model}
     *              <ul>
     *                <li>{@code qr} —— 活码实体</li>
     *                <li>{@code receptionists} —— 接待员列表</li>
     *                <li>{@code services} —— 服务老师列表</li>
     *                <li>{@code backups} —— 后备接待员列表</li>
     *                <li>{@code userList} —— 企微全员列表（供弹窗选择）</li>
     *                <li>{@code agentNameMap} —— userid -> 姓名映射（供列表展示用）</li>
     *                <li>{@code contactUserids} —— 已添加联系人 userid 集合（供前端去重过滤）</li>
     *                <li>{@code rotateLogs} —— 最近 20 条轮换日志</li>
     *                <li>{@code loadError} —— 企微 API 调用失败时的错误信息</li>
     *              </ul>
     * @return 模板视图名 {@code "qrcode/detail"}
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        // ---- 1. 获取活码基本信息 ----
        QrCode qr = qrCodeService.getById(id);
        model.addAttribute("qr", qr);

        // ---- 2. 获取活码下所有联系人，按角色分类 ----
        List<QrAgent> agents = qrCodeService.getAgents(id);

        // 2a. 接待员：role 为 receptionist 或 dual（兼具接待+服务角色）
        model.addAttribute("receptionists",
            agents.stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                                   || a.getRole() == QrAgent.AgentRole.dual).toList());
        // 2b. 服务老师：role 为 service（仅服务角色）
        model.addAttribute("services",
            agents.stream().filter(a -> a.getRole() == QrAgent.AgentRole.service).toList());

        // ---- 3. 获取全局员工池（全部状态） ----
        List<GlobalAgentPool> backups = qrCodeService.getBackups(id);
        model.addAttribute("backups", backups);
        // 3a. 池状态统计
        model.addAttribute("poolStandby",
            backups.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.standby).count());
        model.addAttribute("poolFull",
            backups.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.full).count());
        model.addAttribute("poolBlocked",
            backups.stream().filter(p -> p.getStatus() == GlobalAgentPool.PoolStatus.blocked).count());

        // ---- 4. 加载企业微信全员列表（供前端"新增联系人"/"新增后备"弹窗使用） ----
        // agentNameMap: userid -> 姓名，用于详情页列表展示中文姓名
        Map<String, String> agentNameMap = new HashMap<>();
        // userList: 供前端下拉框渲染的列表数据
        List<Map<String, String>> userList = new ArrayList<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();

            // 检查企微 API 返回码
            if (result.has("errcode") && result.get("errcode").asInt() != 0) {
                model.addAttribute("loadError", "成员列表加载失败: " + result.get("errmsg").asText());
            } else {
                // 遍历 userlist 数组，提取 userid 和 name
                for (JsonNode u : result.get("userlist")) {
                    String userid = u.get("userid").asText();
                    String name = u.get("name").asText();
                    userList.add(Map.of("userid", userid, "name", name));
                    agentNameMap.put(userid, name);
                }
            }
        } catch (Exception e) {
            model.addAttribute("loadError", "成员列表加载失败: " + e.getMessage());
        }
        model.addAttribute("userList", userList);
        model.addAttribute("agentNameMap", agentNameMap);

        // ---- 5. 构建已添加联系人的 userid 列表（排除已移除的），供前端过滤已选用户 ----
        List<String> contactUserids = agents.stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .map(QrAgent::getAgentUserid)
            .distinct()
            .toList();
        model.addAttribute("contactUserids", contactUserids);

        // ---- 6. 加载最近 20 条轮换日志（按时间倒序） ----
        model.addAttribute("rotateLogs",
            rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(id,
                org.springframework.data.domain.PageRequest.of(0, 20)));

        return "qrcode/detail";
    }

    /**
     * 添加后备接待员到该活码的后备池。
     *
     * <p>POST /qrcodes/{id}/backups —— 将指定企微成员加入活码的后备接待员池，
     * 委托 {@link QrCodeService#addBackup(Long, String)} 处理。
     *
     * @param id          活码 ID
     * @param agentUserid 企业微信成员 userid
     * @param redirect    {@link RedirectAttributes}
     *                    <ul>
     *                      <li>{@code message} —— 成功提示</li>
     *                      <li>{@code error} —— 失败错误信息</li>
     *                    </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/backups")
    public String addBackup(@PathVariable Long id,
                            @RequestParam String agentUserid,
                            RedirectAttributes redirect) {
        try {
            qrCodeService.addBackup(id, agentUserid);
            redirect.addFlashAttribute("message", "后备接待员已添加: " + agentUserid);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 新增活码联系人（接待员/服务老师）。
     *
     * <p>POST /qrcodes/{id}/agents —— 将指定企微成员添加为该活码的联系人，
     * 委托 {@link QrCodeService#addAgent(Long, String)} 创建 {@link QrAgent} 记录。
     *
     * @param id          活码 ID
     * @param agentUserid 企业微信成员 userid
     * @param redirect    {@link RedirectAttributes}
     *                    <ul>
     *                      <li>{@code message} —— 成功提示</li>
     *                      <li>{@code error} —— 失败错误信息</li>
     *                    </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/agents")
    public String addAgent(@PathVariable Long id,
                           @RequestParam String agentUserid,
                           RedirectAttributes redirect) {
        try {
            qrCodeService.addAgent(id, agentUserid);
            redirect.addFlashAttribute("message", "联系人已添加: " + agentUserid);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 编辑活码联系人属性。
     *
     * <p>POST /qrcodes/{id}/agents/{agentId}/update —— 更新联系人的日接待上限、
     * 角色（{@link QrAgent.AgentRole}）和排序权重。
     * 委托 {@link QrCodeService#updateAgent} 处理。
     *
     * @param id        活码 ID
     * @param agentId   联系人记录 ID（{@link QrAgent#getId()}）
     * @param dailyMax  日接待上限，null 表示不修改
     * @param role      角色（receptionist / service / dual），null 表示不修改
     * @param sortOrder 排序权重（数值越小越靠前），null 表示不修改
     * @param redirect  {@link RedirectAttributes}
     *                  <ul>
     *                    <li>{@code message} —— 成功提示</li>
     *                    <li>{@code error} —— 失败错误信息</li>
     *                  </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/agents/{agentId}/update")
    public String updateAgent(@PathVariable Long id,
                              @PathVariable Long agentId,
                              @RequestParam(required = false) Integer dailyMax,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) Integer sortOrder,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.updateAgent(id, agentId, dailyMax, role, sortOrder);
            redirect.addFlashAttribute("message", "联系人已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 移除活码联系人（软删除，标记为 removed 状态）。
     *
     * <p>POST /qrcodes/{id}/agents/{agentId}/remove —— 委托
     * {@link QrCodeService#removeAgent(Long, Long)} 将联系人状态置为
     * {@link QrAgent.AgentStatus#removed}，并从企业微信活码中移除。
     *
     * @param id      活码 ID
     * @param agentId 联系人记录 ID
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/agents/{agentId}/remove")
    public String removeAgent(@PathVariable Long id,
                              @PathVariable Long agentId,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.removeAgent(id, agentId);
            redirect.addFlashAttribute("message", "联系人已移除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 移除后备接待员。
     *
     * <p>POST /qrcodes/{id}/backups/{backupId}/remove —— 从全局池中删除指定员工记录。
     * 委托 {@link QrCodeService#removeBackup(Long, Long)} 处理。
     *
     * @param id       活码 ID
     * @param backupId 全局池记录 ID（{@link GlobalAgentPool#getId()}）
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/backups/{backupId}/remove")
    public String removeBackup(@PathVariable Long id,
                               @PathVariable Long backupId,
                               RedirectAttributes redirect) {
        try {
            qrCodeService.removeBackup(id, backupId);
            redirect.addFlashAttribute("message", "后备接待员已移除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 调整后备接待员优先级（上移/下移）。
     *
     * <p>POST /qrcodes/{id}/backups/{backupId}/move —— 在活码后备池中调整接待员的排位顺序，
     * 委托 {@link QrCodeService#moveBackup(Long, Long, String)} 处理排序逻辑。
     *
     * @param id        活码 ID
     * @param backupId  后备记录 ID
     * @param direction 移动方向，{@code "up"} 上移一位，{@code "down"} 下移一位
     * @param redirect  {@link RedirectAttributes}
     *                  <ul>
     *                    <li>{@code error} —— 失败错误信息（成功时不设置消息）</li>
     *                  </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/backups/{backupId}/move")
    public String moveBackup(@PathVariable Long id,
                             @PathVariable Long backupId,
                             @RequestParam String direction,
                             RedirectAttributes redirect) {
        try {
            qrCodeService.moveBackup(id, backupId, direction);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 删除活码（软删除）。
     *
     * <p>POST /qrcodes/{id}/delete —— 将活码及其关联数据标记为删除状态，
     * 委托 {@link QrCodeService#delete(Long)} 处理。
     *
     * @param id       活码 ID
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码列表页 {@code "redirect:/qrcodes"}
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            qrCodeService.delete(id);
            redirect.addFlashAttribute("message", "活码已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /**
     * 切换单个活码的轮换模式。
     *
     * <p>POST /qrcodes/{id}/rotate-mode —— 修改活码的接待员轮换策略，
     * 支持 {@link QrCode.RotateMode} 枚举值。
     * 委托 {@link QrCodeService#updateRotateMode(Long, QrCode.RotateMode)} 处理。
     *
     * @param id       活码 ID
     * @param mode     轮换模式字符串，对应 {@link QrCode.RotateMode} 枚举名
     *                 （如 random / sequential / weighted）
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/rotate-mode")
    public String updateRotateMode(@PathVariable Long id,
                                    @RequestParam String mode,
                                    RedirectAttributes redirect) {
        try {
            qrCodeService.updateRotateMode(id, QrCode.RotateMode.valueOf(mode));
            redirect.addFlashAttribute("message", "轮换模式已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 批量切换活码轮换模式。
     *
     * <p>POST /qrcodes/batch-rotate-mode —— 对选中的一批活码统一设置轮换模式，
     * 委托 {@link QrCodeService#batchUpdateRotateMode(List, QrCode.RotateMode)} 批量处理。
     *
     * @param ids      活码 ID 列表
     * @param mode     轮换模式字符串，对应 {@link QrCode.RotateMode} 枚举名
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示（含更新数量）</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码列表页 {@code "redirect:/qrcodes"}
     */
    @PostMapping("/batch-rotate-mode")
    public String batchUpdateRotateMode(@RequestParam List<Long> ids,
                                        @RequestParam String mode,
                                        RedirectAttributes redirect) {
        try {
            QrCode.RotateMode rotateMode = QrCode.RotateMode.valueOf(mode);
            int count = qrCodeService.batchUpdateRotateMode(ids, rotateMode);
            redirect.addFlashAttribute("message", "已更新 " + count + " 个活码的轮换模式为 " + mode);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes";
    }

    /**
     * 更新活码预警阈值。
     *
     * <p>POST /qrcodes/{id}/thresholds —— 设置该活码的预警比例和紧急比例，
     * 用于接待员负载监控。
     * 委托 {@link QrCodeService#updateThresholds(Long, int, int)} 处理。
     *
     * @param id         活码 ID
     * @param warnRatio  预警比例（百分比整数，如 70 表示 70% 触发预警）
     * @param urgentRatio 紧急比例（百分比整数，如 90 表示 90% 触发紧急告警）
     * @param redirect   {@link RedirectAttributes}
     *                   <ul>
     *                     <li>{@code message} —— 成功提示</li>
     *                     <li>{@code error} —— 失败错误信息</li>
     *                   </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/thresholds")
    public String updateThresholds(@PathVariable Long id,
                                   @RequestParam int warnRatio,
                                   @RequestParam int urgentRatio,
                                   RedirectAttributes redirect) {
        try {
            qrCodeService.updateThresholds(id, warnRatio, urgentRatio);
            redirect.addFlashAttribute("message", "预警阈值已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 手动同步企微活码用户列表。
     *
     * <p>POST /qrcodes/{id}/sync —— 将该活码下当前 active 状态的员工同步到企业微信活码上，
     * 确保企微侧的联系人列表与服务端一致。
     * 委托 {@link QrCodeService#syncQrUsersToWechat(Long)} 处理。
     *
     * @param id       活码 ID
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— 成功提示</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/sync")
    public String syncQrUsers(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            qrCodeService.syncQrUsersToWechat(id);
            redirect.addFlashAttribute("message", "活码已同步 — 仅 active 员工在活码上");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 暂停/启用活码 —— 切换活码状态。
     *
     * <p>POST /qrcodes/{id}/toggle-status —— 在当前状态基础上取反：
     * {@link QrCode.QrCodeStatus#active} 切换为 {@link QrCode.QrCodeStatus#paused}，反之亦然。
     * 委托 {@link QrCodeService#updateStatus(Long, QrCode.QrCodeStatus)} 处理。
     *
     * @param id       活码 ID
     * @param redirect {@link RedirectAttributes}
     *                 <ul>
     *                   <li>{@code message} —— "活码已启用" 或 "活码已暂停"</li>
     *                   <li>{@code error} —— 失败错误信息</li>
     *                 </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            // 读取当前状态，计算相反状态
            QrCode qr = qrCodeService.getById(id);
            QrCode.QrCodeStatus newStatus = qr.getStatus() == QrCode.QrCodeStatus.active
                ? QrCode.QrCodeStatus.paused : QrCode.QrCodeStatus.active;
            qrCodeService.updateStatus(id, newStatus);
            redirect.addFlashAttribute("message",
                "活码已" + (newStatus == QrCode.QrCodeStatus.active ? "启用" : "暂停"));
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 更新活码外观样式。
     *
     * <p>POST /qrcodes/{id}/style —— 修改活码二维码的展示外观，包括主题配色、
     * 引导文案、是否显示学校名称。
     * 委托 {@link QrCodeService#updateStyle} 处理。
     *
     * @param id             活码 ID
     * @param theme          主题样式标识（可选，null 表示不修改）
     * @param guideText      引导文案，展示在二维码下方（可选）
     * @param showSchoolName 是否在二维码上显示学校名称，默认 true
     * @param redirect       {@link RedirectAttributes}
     *                       <ul>
     *                         <li>{@code message} —— 成功提示</li>
     *                         <li>{@code error} —— 失败错误信息</li>
     *                       </ul>
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/style")
    public String updateStyle(@PathVariable Long id,
                              @RequestParam(required = false) String theme,
                              @RequestParam(required = false) String guideText,
                              @RequestParam(required = false, defaultValue = "true") Boolean showSchoolName,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.updateStyle(id, theme, guideText, showSchoolName, null);
            redirect.addFlashAttribute("message", "样式已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 下载单个活码二维码 PNG 图片。
     *
     * <p>GET /qrcodes/{id}/download?dpi=72 —— 调用 {@link QrImageService#generateQrImage(Long, int)}
     * 生成指定 DPI 的二维码图片，以附件形式直接写入 HTTP 响应输出流。
     *
     * <p>响应头设置：
     * <ul>
     *   <li>{@code Content-Type}: {@code image/png}</li>
     *   <li>{@code Content-Disposition}: attachment（触发浏览器下载），
     *       文件名为 {@code 学校名称_dpi值dpi.png}</li>
     *   <li>{@code Content-Length}: 图片字节数</li>
     * </ul>
     *
     * @param id       活码 ID
     * @param dpi      图片分辨率（dots per inch），默认 72
     * @param response {@link HttpServletResponse}，图片字节流直接写入其输出流
     * @throws IOException 写入响应流时可能抛出
     */
    @GetMapping("/{id}/download")
    public void downloadSingle(@PathVariable Long id,
                               @RequestParam(defaultValue = "72") int dpi,
                               HttpServletResponse response) throws IOException {
        // 获取活码信息（主要用于构建文件名）
        QrCode qr = qrCodeService.getById(id);
        // 调用图片服务生成二维码字节数组
        byte[] imageBytes = qrImageService.generateQrImage(id, dpi);

        // 构建下载文件名：学校名称_分辨率dpi.png
        String filename = qr.getSchoolName() + "_" + dpi + "dpi.png";

        // 设置响应头：PNG 图片，附件下载
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        // Content-Disposition: attachment 触发浏览器下载对话框
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString());
        // 设置 Content-Length 以便浏览器显示下载进度
        response.setContentLength(imageBytes.length);

        // 写入字节流并刷新
        response.getOutputStream().write(imageBytes);
        response.getOutputStream().flush();
    }

    /**
     * 批量下载活码二维码，打包为 ZIP 文件。
     *
     * <p>POST /qrcodes/batch-download —— 对指定的活码 ID 列表逐一调用
     * {@link QrImageService#generateQrImage(Long, int)} 生成 PNG，
     * 将所有 PNG 打包到内存中的 ZIP 文件，以附件形式写入 HTTP 响应。
     *
     * <p>容错处理：单个活码生成失败时记录警告日志并跳过，不影响其余活码的打包。
     *
     * <p>响应头：
     * <ul>
     *   <li>{@code Content-Type}: {@code application/zip}</li>
     *   <li>{@code Content-Disposition}: attachment，
     *       文件名为 {@code qrcodes_dpi值dpi.zip}</li>
     *   <li>{@code Content-Length}: ZIP 文件总字节数</li>
     * </ul>
     *
     * <p>注意：ZIP 在内存中构建（{@link ByteArrayOutputStream}），
     * 不落盘，适合中小批量下载。若批量过大需考虑内存占用。
     *
     * @param ids      活码 ID 列表
     * @param dpi      图片分辨率，默认 72
     * @param response {@link HttpServletResponse}
     * @throws IOException 写入响应流时可能抛出
     */
    @PostMapping("/batch-download")
    public void downloadBatch(@RequestParam List<Long> ids,
                              @RequestParam(defaultValue = "72") int dpi,
                              HttpServletResponse response) throws IOException {
        // 使用 ByteArrayOutputStream 在内存中构建 ZIP，避免磁盘 I/O
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // try-with-resources 确保 ZipOutputStream 正确关闭（写入 ZIP 结束标记）
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Long id : ids) {
                try {
                    // 获取活码信息并生成二维码图片
                    QrCode qr = qrCodeService.getById(id);
                    byte[] imageBytes = qrImageService.generateQrImage(id, dpi);

                    // 每个活码在 ZIP 中作为一个独立条目，文件名含学校名
                    String entryName = qr.getSchoolName() + "_" + dpi + "dpi.png";
                    ZipEntry entry = new ZipEntry(entryName);

                    // 写入 ZIP 条目：先 putNextEntry，再 write 数据，最后 closeEntry
                    zos.putNextEntry(entry);
                    zos.write(imageBytes);
                    zos.closeEntry();
                } catch (Exception e) {
                    // 单个活码处理失败时记录警告并继续处理下一个
                    log.warn("批量下载跳过: id={}, error={}", id, e.getMessage());
                }
            }
            // ZipOutputStream.close() 由 try-with-resources 自动调用，
            // 此时 ZIP 中央目录写入 BAOS
        }

        // 从 BAOS 获取完整的 ZIP 字节数组
        byte[] zipBytes = baos.toByteArray();

        // 设置响应头：ZIP 格式，附件下载
        response.setContentType("application/zip");
        // Content-Disposition: attachment 触发浏览器下载，文件名为 qrcodes_分辨率dpi.zip
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("qrcodes_" + dpi + "dpi.zip").build().toString());
        response.setContentLength(zipBytes.length);

        // 写入响应流
        response.getOutputStream().write(zipBytes);
        response.getOutputStream().flush();
    }

}
