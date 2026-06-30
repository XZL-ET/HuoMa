package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.config.SceneConfigProperties;
import com.bookstore.qrcode.dto.BatchRecycleRequest;
import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.dto.QrCodeTreeDto;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.entity.Scene;
import com.bookstore.qrcode.entity.SystemConfig;
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
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.*;
import java.util.stream.Collectors;
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
    private final CustomerTransferRepository transferRepo;
    private final EmployeeSyncService employeeSyncService;
    private final FormTemplateRepository formTemplateRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SceneConfigProperties sceneConfig;

    @Value("${app.agent.daily-max-default:150}")
    private int dailyMaxDefault;

    @Value("${app.inheritance.day-start-hour:8}")
    private int dayStartHour;
    @Value("${app.inheritance.day-end-hour:21}")
    private int dayEndHour;

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
                       @RequestParam(required = false) String scope,
                       @RequestParam(required = false) Long groupId,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        // ---- 1. 空字符串 → null（前端表单空字段会传空串，破坏 IS NULL 判断） ----
        if (keyword != null && keyword.isEmpty()) keyword = null;
        if (city != null && city.isEmpty()) city = null;
        if (district != null && district.isEmpty()) district = null;

        // ---- 2. 解析状态枚举参数 ----
        QrCode.QrCodeStatus qrStatus = null;
        if (status != null && !status.isEmpty()) {
            try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        // ---- 2. 分页搜索（scope 筛选下推到 DB） ----
        Page<QrCode> qrCodes;
        Pageable pageable = PageRequest.of(page, size);
        if ("alliance".equals(scope)) {
            qrCodes = qrCodeRepo.searchAlliance(keyword, city, district, qrStatus, groupId, pageable);
        } else if ("school".equals(scope)) {
            qrCodes = qrCodeRepo.searchSchool(keyword, city, district, qrStatus, groupId, pageable);
        } else {
            qrCodes = qrCodeRepo.search(keyword, city, district, qrStatus, groupId, pageable);
        }

        // ---- 3. 城市/区县/分组下拉选项 ----
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();
        List<QrCodeGroup> groups = groupRepo.findAllByOrderByName();

        // ---- 4. 聚合查询：客户数（今日 + 累计） ----
        List<Long> pageIds = qrCodes.getContent().stream()
            .map(QrCode::getId).collect(Collectors.toList());

        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        Map<Long, Long> todayCountMap = new HashMap<>();
        Map<Long, Long> totalCountMap = new HashMap<>();

        if (!pageIds.isEmpty()) {
            List<Object[]> custStats = customerRepo.countTotalAndTodayByQrIds(pageIds, todayStart);
            for (Object[] row : custStats) {
                Long qrId = (Long) row[0];
                totalCountMap.put(qrId, (Long) row[1]);
                todayCountMap.put(qrId, (Long) row[2]);
            }
        }

        // ---- 5. 客服数统计 ----
        Map<Long, String> agentCountMap = new HashMap<>();
        for (QrCode qr : qrCodes.getContent()) {
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active).size();
            long poolStandby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
            agentCountMap.put(qr.getId(), activeCount + "/" + poolStandby);
        }

        // ---- 6. 填充 Model ----
        model.addAttribute("qrCodes", qrCodes);
        model.addAttribute("keyword", keyword);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("status", status);
        model.addAttribute("scope", scope);
        model.addAttribute("groupId", groupId);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        model.addAttribute("agentCountMap", agentCountMap);
        model.addAttribute("todayCountMap", todayCountMap);
        model.addAttribute("totalCountMap", totalCountMap);
        model.addAttribute("groups", groups);
        model.addAttribute("formTemplates", formTemplateRepo.findAllByOrderByName());

        return "qrcode/list";
    }

    /**
     * 活码分组树 JSON 接口 —— 返回 city → district → group → qrcode 层级结构。
     *
     * <p>GET /api/qrcodes/tree —— 用于列表页左侧边栏树形导航，
     * 聚合所有活码和分组，支持以下场景：
     * <ul>
     *   <li>已有分组的活码：正常嵌套在 city → district → group → qrcode 下</li>
     *   <li>未分组的活码：在对应 city/district 下归入"未分组"节点</li>
     *   <li>无活码的分组：作为空叶子节点展示（children 为空列表）</li>
     * </ul>
     *
     * <p>返回 JSON 结构：</p>
     * <pre>{@code
     * [
     *   {
     *     "type": "city",
     *     "name": "兰州市",
     *     "children": [
     *       {
     *         "type": "district",
     *         "name": "城关区",
     *         "children": [
     *           { "type": "group", "id": 1, "name": "城关联盟", "children": [
     *             { "type": "qrcode", "id": 1, "name": "某某中学" }
     *           ]},
     *           { "type": "group", "id": 2, "name": "城关二盟", "children": [] }
     *         ]
     *       }
     *     ]
     *   }
     * ]
     * }</pre>
     *
     * @return 树形结构列表，按城市→区县→分组→活码嵌套
     */
    /** 检查同城市下学校名称是否已存在（创建活码前的前端 AJAX 校验） */
    @GetMapping("/api/check-duplicate-school")
    @ResponseBody
    public Map<String, Object> checkDuplicateSchool(@RequestParam String schoolName,
                                                     @RequestParam String regionCity) {
        boolean exists = qrCodeRepo.existsBySchoolNameAndRegionCity(schoolName.trim(), regionCity.trim());
        return Map.of("exists", exists);
    }

    @GetMapping("/api/tree")
    @ResponseBody
    public List<Map<String, Object>> tree() {
        List<QrCodeTreeDto> qrs = qrCodeRepo.findAllTreeProjection();
        List<QrCodeGroup> groups = groupRepo.findAllByOrderByName();

        // 按 groupId 索引分组，用于 O(1) 查找分组名称
        Map<Long, QrCodeGroup> groupMap = new LinkedHashMap<>();
        for (QrCodeGroup g : groups) {
            groupMap.put(g.getId(), g);
        }
        Set<Long> coveredGroupIds = new LinkedHashSet<>();

        // 中间结构：city → district → (groupId: "g:123" 或 "u:ungrouped") → [qrcode...]
        // 使用 LinkedHashMap 保持插入顺序
        Map<String, Map<String, Map<String, List<QrCodeTreeDto>>>> mid = new LinkedHashMap<>();

        // ── 1. 遍历所有活码，归入对应的 city → district → group ──
        for (QrCodeTreeDto qr : qrs) {
            String city = qr.getRegionCity() != null ? qr.getRegionCity() : "未分类";
            String district = qr.getRegionDistrict() != null ? qr.getRegionDistrict() : "未分类";

            mid.putIfAbsent(city, new LinkedHashMap<>());
            mid.get(city).putIfAbsent(district, new LinkedHashMap<>());

            String bucketKey;
            if (qr.getGroupId() != null && groupMap.containsKey(qr.getGroupId())) {
                QrCodeGroup g = groupMap.get(qr.getGroupId());
                bucketKey = "g:" + g.getId() + "|" + g.getName();
                coveredGroupIds.add(g.getId());
            } else if (qr.getGroupId() != null) {
                // groupId 指向已删除的分组
                bucketKey = "g:" + qr.getGroupId() + "|已删除分组";
            } else {
                bucketKey = "u:未分组";
            }

            mid.get(city).get(district).putIfAbsent(bucketKey, new ArrayList<>());
            mid.get(city).get(district).get(bucketKey).add(qr);
        }

        // ── 2. 补充分组中没有活码的空分组节点 ──
        for (QrCodeGroup g : groups) {
            if (coveredGroupIds.contains(g.getId())) continue;
            String city = g.getRegionCity() != null ? g.getRegionCity() : "未分类";
            String district = g.getRegionDistrict() != null ? g.getRegionDistrict() : "未分类";
            mid.putIfAbsent(city, new LinkedHashMap<>());
            mid.get(city).putIfAbsent(district, new LinkedHashMap<>());
            String bucketKey = "g:" + g.getId() + "|" + g.getName();
            mid.get(city).get(district).putIfAbsent(bucketKey, new ArrayList<>());
        }

        // ── 3. 将中间 Map 结构转换为 JSON 友好的 List<Map> 树 ──
        List<Map<String, Object>> treeList = new ArrayList<>();
        for (var cityEntry : mid.entrySet()) {
            Map<String, Object> cityNode = new LinkedHashMap<>();
            cityNode.put("type", "city");
            cityNode.put("name", cityEntry.getKey());
            List<Map<String, Object>> districtNodes = new ArrayList<>();
            for (var districtEntry : cityEntry.getValue().entrySet()) {
                Map<String, Object> districtNode = new LinkedHashMap<>();
                districtNode.put("type", "district");
                districtNode.put("name", districtEntry.getKey());
                List<Map<String, Object>> childNodes = new ArrayList<>();
                for (var bucketEntry : districtEntry.getValue().entrySet()) {
                    String key = bucketEntry.getKey();
                    List<QrCodeTreeDto> bucketQrs = bucketEntry.getValue();
                    Map<String, Object> groupNode = new LinkedHashMap<>();
                    if (key.startsWith("g:")) {
                        String[] parts = key.substring(2).split("\\|", 2);
                        groupNode.put("type", "group");
                        groupNode.put("id", Long.valueOf(parts[0]));
                        groupNode.put("name", parts[1]);
                    } else {
                        groupNode.put("type", "ungrouped");
                        groupNode.put("name", "未分组");
                    }
                    List<Map<String, Object>> qrNodes = new ArrayList<>();
                    for (QrCodeTreeDto qr : bucketQrs) {
                        Map<String, Object> qrNode = new LinkedHashMap<>();
                        qrNode.put("type", "qrcode");
                        qrNode.put("id", qr.getId());
                        qrNode.put("name", qr.getSchoolName() != null ? qr.getSchoolName() : "");
                        qrNodes.add(qrNode);
                    }
                    groupNode.put("children", qrNodes);
                    childNodes.add(groupNode);
                }
                districtNode.put("children", childNodes);
                districtNodes.add(districtNode);
            }
            cityNode.put("children", districtNodes);
            treeList.add(cityNode);
        }
        return treeList;
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

        model.addAttribute("sceneConfig", sceneConfig);
        model.addAttribute("dailyMaxDefault", dailyMaxDefault);
        model.addAttribute("categories", categoryRepo.findAllByOrderBySortOrderAscName());

        // 加载企微部门列表
        try {
            JsonNode deptResp = wecomApiClient.listDepartments(null);
            List<Map<String, Object>> departments = new ArrayList<>();
            if (deptResp.has("department") && deptResp.get("department").isArray()) {
                for (JsonNode d : deptResp.get("department")) {
                    Map<String, Object> dept = new LinkedHashMap<>();
                    dept.put("id", d.get("id").asLong());
                    dept.put("name", d.get("name").asText());
                    departments.add(dept);
                }
            }
            model.addAttribute("departments", departments);

            // 部门名称列表（供前端 AutocompleteInput 搜索用）
            List<String> departmentNames = departments.stream()
                .map(d -> (String) d.get("name"))
                .collect(Collectors.toList());
            model.addAttribute("departmentNames", departmentNames);

            // 部门名称 → ID 映射（供前端选中时回填隐藏域）
            Map<String, Long> departmentMap = departments.stream()
                .collect(Collectors.toMap(d -> (String) d.get("name"),
                    d -> (Long) d.get("id"), (a, b) -> a));
            model.addAttribute("departmentMap", departmentMap);
        } catch (Exception e) {
            log.warn("加载企微部门列表失败", e);
            model.addAttribute("departments", List.of());
            model.addAttribute("departmentNames", List.of());
            model.addAttribute("departmentMap", Map.of());
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
            // parseAndCheck 保证 errcode=0
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
    public Object create(@ModelAttribute QrCodeCreateRequest req,
                          RedirectAttributes redirect,
                          HttpServletRequest request) {
        boolean isAjax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        try {
            // 如果学校ID为空（用户清掉了自动生成的值），自动补生成一个
            if (req.getSchoolId() == null || req.getSchoolId().isBlank()) {
                String autoId = "SCH" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                req.setSchoolId(autoId);
                log.info("学校ID为空，已自动生成: {}", autoId);
            }
            qrCodeService.create(req);
            if (isAjax) {
                return ResponseEntity.ok(Map.of("success", true, "message", "活码创建成功"));
            }
            redirect.addFlashAttribute("message", "活码创建成功");
            return "redirect:/qrcodes";
        } catch (Exception e) {
            log.error("创建活码失败", e);
            if (isAjax) {
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", e.getMessage()));
            }
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/qrcodes";
        }
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
     * 导入完成（status=done）时附带失败详情列表。
     *
     * @param taskId 异步任务 ID
     * @return Map 包含进度信息：total / success / fail / processed / status / failDetails（完成时）
     */
    @GetMapping("/batch-import/progress/{taskId}")
    @ResponseBody
    public Map<Object, Object> getImportProgress(@PathVariable String taskId) {
        Map<Object, Object> result = new LinkedHashMap<>();
        result.putAll(qrCodeService.getBatchImportProgress(taskId));
        // 导入完成后附加失败详情
        if ("done".equals(result.get("status")) || "error".equals(result.get("status"))) {
            result.put("failDetails", qrCodeService.getBatchImportFailDetails(taskId));
        }
        return result;
    }

    // ==================== 导出 ====================

    /** 导出活码列表为 Excel（SXSSFWorkbook 流式写入）。 */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String scope,
                       @RequestParam(required = false) Long groupId,
                       HttpServletResponse response) throws Exception {

        // 空字符串 → null（前端链接参数为空串，破坏 JPQL IS NULL 判断）
        if (keyword != null && keyword.isEmpty()) keyword = null;
        if (city != null && city.isEmpty()) city = null;
        if (district != null && district.isEmpty()) district = null;

        QrCode.QrCodeStatus qrStatus = null;
        if (status != null && !status.isEmpty()) {
            try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
            catch (IllegalArgumentException ignored) {}
        }

        Boolean allianceOnly = null;
        if ("alliance".equals(scope)) allianceOnly = true;
        else if ("school".equals(scope)) allianceOnly = false;

        List<QrCode> qrs = qrCodeRepo.findAllForExport(keyword, city, district, qrStatus, groupId, allianceOnly);

        List<Long> allIds = qrs.stream().map(QrCode::getId).collect(Collectors.toList());
        Map<Long, Long> totalMap = new HashMap<>();
        Map<Long, Long> todayMap = new HashMap<>();
        if (!allIds.isEmpty()) {
            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
            for (Object[] row : customerRepo.countTotalAndTodayByQrIds(allIds, todayStart)) {
                totalMap.put((Long) row[0], (Long) row[1]);
                todayMap.put((Long) row[0], (Long) row[2]);
            }
        }

        Map<Long, String> groupNameMap = new HashMap<>();
        for (QrCodeGroup g : groupRepo.findAllByOrderByName()) groupNameMap.put(g.getId(), g.getName());

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=qr_codes_" + java.time.LocalDate.now() + ".xlsx");

        var wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        var sheet = wb.createSheet("活码列表");
        var header = sheet.createRow(0);
        String[] headers = {"学校名称","学校ID","城市","区县","分组","状态","轮换模式","今日新增","累计客户","创建时间"};
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

        int rowIdx = 1;
        for (QrCode qr : qrs) {
            var row = sheet.createRow(rowIdx++);
            Long qid = qr.getId();
            row.createCell(0).setCellValue(qr.getSchoolName() != null ? qr.getSchoolName() : "");
            row.createCell(1).setCellValue(qr.getSchoolId() != null ? qr.getSchoolId() : "");
            row.createCell(2).setCellValue(qr.getRegionCity() != null ? qr.getRegionCity() : "");
            row.createCell(3).setCellValue(qr.getRegionDistrict() != null ? qr.getRegionDistrict() : "");
            row.createCell(4).setCellValue(qr.getGroupId() != null ? groupNameMap.getOrDefault(qr.getGroupId(), "") : "");
            row.createCell(5).setCellValue(qr.getStatus() != null ? qr.getStatus().name() : "");
            row.createCell(6).setCellValue(qr.getRotateMode() != null ? qr.getRotateMode().name() : "");
            row.createCell(7).setCellValue(todayMap.getOrDefault(qid, 0L));
            row.createCell(8).setCellValue(totalMap.getOrDefault(qid, 0L));
            row.createCell(9).setCellValue(qr.getCreatedAt() != null ? qr.getCreatedAt().toString() : "");
        }
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ==================== 批量操作 ====================

    @PostMapping("/batch/welcome")
    @ResponseBody
    public Map<String, Object> batchUpdateWelcome(@RequestParam List<Long> ids, @RequestParam String welcomeText) {
        int n = qrCodeService.batchUpdateWelcomeText(ids, welcomeText);
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/form-template")
    @ResponseBody
    public Map<String, Object> batchUpdateFormTemplate(@RequestParam List<Long> ids,
                                                       @RequestParam(required = false) Long formTemplateId) {
        int n = qrCodeService.batchUpdateFormTemplateId(ids, formTemplateId);
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/rotate-mode")
    @ResponseBody
    public Map<String, Object> batchUpdateRotateMode(@RequestParam List<Long> ids, @RequestParam String mode) {
        int n = qrCodeService.batchUpdateRotateMode(ids, QrCode.RotateMode.valueOf(mode));
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/group")
    @ResponseBody
    public Map<String, Object> batchUpdateGroup(@RequestParam List<Long> ids,
                                                 @RequestParam(required = false) Long groupId) {
        int n = qrCodeService.batchUpdateGroupId(ids, groupId);
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/thresholds")
    @ResponseBody
    public Map<String, Object> batchUpdateThresholds(@RequestParam List<Long> ids,
                                                      @RequestParam int warnRatio,
                                                      @RequestParam int urgentRatio) {
        int n = qrCodeService.batchUpdateThresholds(ids, warnRatio, urgentRatio);
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/status")
    @ResponseBody
    public Map<String, Object> batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam String status) {
        int n = qrCodeService.batchUpdateStatus(ids, QrCode.QrCodeStatus.valueOf(status));
        return Map.of("ok", true, "count", n);
    }

    @PostMapping("/batch/scene")
    @ResponseBody
    public Map<String, Object> batchUpdateScene(@RequestParam List<Long> ids, @RequestParam String scene) {
        int n = qrCodeService.batchUpdateScene(ids, Scene.valueOf(scene));
        return Map.of("ok", true, "count", n);
    }

    // ==================== 批量导入模板下载 ====================

    @GetMapping("/batch-import/template")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=qr_code_import_template.xlsx");

        var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var sheet = wb.createSheet("活码导入");
        var header = sheet.createRow(0);
        String[] headers = {"学校名称","学校ID","市","区","服务老师(userid)","学校人数",
                            "初始上码员工数","接待员(userid逗号分隔)","服务老师日上限","欢迎语","备注",
                            "场景","部门ID","分类名称"};
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
        var example = sheet.createRow(1);
        example.createCell(0).setCellValue("示例中学");
        example.createCell(1).setCellValue("SCH001");
        example.createCell(2).setCellValue("武汉");
        example.createCell(3).setCellValue("武昌区");
        example.createCell(4).setCellValue("zhangsan");
        example.createCell(5).setCellValue("500");
        example.createCell(6).setCellValue("1");
        example.createCell(7).setCellValue("lisi,wangwu");
        example.createCell(8).setCellValue("200");
        example.createCell(9).setCellValue("欢迎来到示例中学！");
        example.createCell(10).setCellValue("备注示例");
        example.createCell(11).setCellValue("daily_push");
        example.createCell(12).setCellValue("");
        example.createCell(13).setCellValue("初中");
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
        wb.write(response.getOutputStream());
        wb.close();
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
    public String detail(@PathVariable Long id,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
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

        // ---- 3. 获取全局员工池（DB 分页 + COUNT 统计） ----
        int pageSize = 20;
        Page<GlobalAgentPool> backupPage = qrCodeService.getBackups(id, page, pageSize);
        model.addAttribute("backups", backupPage.getContent());
        model.addAttribute("backupPage", backupPage.getNumber());
        model.addAttribute("backupTotalPages", backupPage.getTotalPages());
        model.addAttribute("backupTotalItems", backupPage.getTotalElements());
        model.addAttribute("backupPageSize", pageSize);
        // 3a. 池状态统计（三条 COUNT 查询）
        Map<String, Long> poolStats = qrCodeService.getPoolStats();
        model.addAttribute("poolStandby", poolStats.get("standby"));
        model.addAttribute("poolFull", poolStats.get("full"));
        model.addAttribute("poolBlocked", poolStats.get("blocked"));
        // 3b. 全量 userid 集合供弹窗去重用
        model.addAttribute("allPoolUserids", qrCodeService.getAllPoolUserids());

        // ---- 4. 加载企业微信全员列表（供前端"新增联系人"/"新增后备"弹窗使用） ----
        // agentNameMap: userid -> 姓名，用于详情页列表展示中文姓名
        Map<String, String> agentNameMap = new HashMap<>();
        // userList: 供前端下拉框渲染的列表数据
        List<Map<String, String>> userList = new ArrayList<>();
        try {
            JsonNode result = wecomApiClient.getUserSimplelist();
            // parseAndCheck 保证 errcode=0，直接遍历
            for (JsonNode u : result.get("userlist")) {
                String userid = u.get("userid").asText();
                String name = u.get("name").asText();
                userList.add(Map.of("userid", userid, "name", name));
                agentNameMap.put(userid, name);
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

        // ---- 7. 加载表单模板和分组列表（供客户侧配置和分组下拉选择） ----
        model.addAttribute("formTemplates", formTemplateRepo.findAllByOrderByName());
        model.addAttribute("groups", groupRepo.findAllByOrderByName());

        // ---- 8. 解析继承链生效值和来源（供前端显示） ----
        String effectiveWelcomeText = qr.getWelcomeText();
        String welcomeSource = null;
        if (effectiveWelcomeText == null && qr.getGroupId() != null) {
            QrCodeGroup grp = groupRepo.findById(qr.getGroupId()).orElse(null);
            if (grp != null && grp.getDefaultWelcomeText() != null) {
                effectiveWelcomeText = grp.getDefaultWelcomeText();
                welcomeSource = "分组「" + grp.getName() + "」";
            }
        }
        if (effectiveWelcomeText == null && qr.getSchoolId() != null) {
            School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
            if (school != null && school.getCategoryId() != null) {
                SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                if (cat != null && cat.getDefaultWelcomeText() != null) {
                    effectiveWelcomeText = cat.getDefaultWelcomeText();
                    welcomeSource = "分类「" + cat.getName() + "」";
                }
            }
        }
        if (effectiveWelcomeText == null) {
            effectiveWelcomeText = "{{school_name}}家长您好～欢迎加入XX书店家校服务！";
            welcomeSource = "系统默认";
        }
        model.addAttribute("effectiveWelcomeText", effectiveWelcomeText);
        model.addAttribute("welcomeSource", welcomeSource);

        // 表单模板生效值和来源
        Long effectiveFormTemplateId = qr.getFormTemplateId();
        String formTemplateSource = null;
        if (effectiveFormTemplateId == null && qr.getGroupId() != null) {
            QrCodeGroup grp = groupRepo.findById(qr.getGroupId()).orElse(null);
            if (grp != null && grp.getDefaultFormTemplateId() != null) {
                effectiveFormTemplateId = grp.getDefaultFormTemplateId();
                formTemplateSource = "分组「" + grp.getName() + "」";
            }
        }
        if (effectiveFormTemplateId == null && qr.getSchoolId() != null) {
            School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
            if (school != null && school.getCategoryId() != null) {
                SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                if (cat != null && cat.getDefaultFormTemplateId() != null) {
                    effectiveFormTemplateId = cat.getDefaultFormTemplateId();
                    formTemplateSource = "分类「" + cat.getName() + "」";
                }
            }
        }
        model.addAttribute("effectiveFormTemplateId", effectiveFormTemplateId);
        model.addAttribute("formTemplateSource", formTemplateSource);

        // ---- 在职继承问候语配置生效值和来源 ----
        // QrCode 新列非 NULL 表示活码自定义，NULL 表示使用默认（welcomeConfig JSON 或系统默认）
        boolean transferEnabledOverride = qr.getTransferGreetingEnabled() != null;
        model.addAttribute("transferEnabledOverride", transferEnabledOverride);
        model.addAttribute("transferFilledNoteOverride", qr.getTransferFilledNote() != null);
        model.addAttribute("transferFilledGreetingOverride", qr.getTransferFilledGreeting() != null);
        model.addAttribute("transferUnfilledGreetingOverride", qr.getTransferUnfilledGreeting() != null);

        // 解析生效值（非 NULL 用列值，否则读 SystemConfig 全局默认）
        model.addAttribute("effectiveTransferEnabled",
            qr.getTransferGreetingEnabled() != null ? qr.getTransferGreetingEnabled()
                : systemConfigRepo.findByConfigKey("transfer_greeting_enabled_default")
                    .map(c -> "true".equalsIgnoreCase(c.getConfigValue()) || "1".equals(c.getConfigValue()))
                    .orElse(true));
        model.addAttribute("effectiveTransferFilledNote",
            qr.getTransferFilledNote() != null ? qr.getTransferFilledNote()
                : systemConfigRepo.findByConfigKey("transfer_filled_note_default")
                    .map(SystemConfig::getConfigValue)
                    .orElse("{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}"));
        model.addAttribute("effectiveTransferFilledGreeting",
            qr.getTransferFilledGreeting() != null ? qr.getTransferFilledGreeting()
                : systemConfigRepo.findByConfigKey("transfer_filled_greeting_default")
                    .map(SystemConfig::getConfigValue)
                    .orElse("{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚"));
        model.addAttribute("effectiveTransferUnfilledGreeting",
            qr.getTransferUnfilledGreeting() != null ? qr.getTransferUnfilledGreeting()
                : systemConfigRepo.findByConfigKey("transfer_unfilled_greeting_default")
                    .map(SystemConfig::getConfigValue)
                    .orElse("{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}"));

        // 时间窗口说明
        model.addAttribute("dayStartHour", dayStartHour);
        model.addAttribute("dayEndHour", dayEndHour);

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
     * 切换单个活码的场景。
     *
     * <p>POST /qrcodes/{id}/scene —— 修改活码的场景类型（日常推送 / 家长会），
     * 同时自动更新紧急阈值以匹配新场景的预设。
     * 委托 {@link QrCodeService#updateScene(Long, Scene)} 处理。
     *
     * @param id       活码 ID
     * @param scene    场景枚举名（daily_push / parent_meeting）
     * @param redirect {@link RedirectAttributes}
     * @return 重定向到活码详情页 {@code "redirect:/qrcodes/{id}"}
     */
    @PostMapping("/{id}/scene")
    public String updateScene(@PathVariable Long id,
                              @RequestParam String scene,
                              RedirectAttributes redirect) {
        try {
            qrCodeService.updateScene(id, Scene.valueOf(scene));
            String label = Scene.valueOf(scene) == Scene.parent_meeting ? "家长会" : "日常推送";
            redirect.addFlashAttribute("message", "场景已切换为「" + label + "」");
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

    @PostMapping("/{id}/welcome")
    public String updateWelcome(@PathVariable Long id,
                                 @RequestParam(required = false) String welcomeText,
                                 @RequestParam(required = false) Long formTemplateId,
                                 RedirectAttributes redirect) {
        try {
            QrCode qr = qrCodeService.getById(id);
            // 空字符串 → null，确保清空后走继承链（Bug A 修复）
            if (welcomeText != null) {
                qr.setWelcomeText(welcomeText.isBlank() ? null : welcomeText);
            }
            qr.setFormTemplateId(formTemplateId);
            qrCodeRepo.save(qr);
            redirect.addFlashAttribute("message", "客户配置已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    /**
     * 保存在职继承问候语配置（独立端点，避免与客户配置表单的参数冲突）。
     */
    @PostMapping("/{id}/transfer-greeting")
    public String updateTransferGreeting(@PathVariable Long id,
                                          @RequestParam(defaultValue = "false") Boolean transferGreetingEnabled,
                                          @RequestParam(required = false) String transferFilledNote,
                                          @RequestParam(required = false) String transferFilledGreeting,
                                          @RequestParam(required = false) String transferUnfilledGreeting,
                                          RedirectAttributes redirect) {
        try {
            QrCode qr = qrCodeService.getById(id);
            qr.setTransferGreetingEnabled(transferGreetingEnabled);
            // 空字符串 → null，回退系统默认
            if (transferFilledNote != null) {
                qr.setTransferFilledNote(transferFilledNote.isBlank() ? null : transferFilledNote);
            }
            if (transferFilledGreeting != null) {
                qr.setTransferFilledGreeting(transferFilledGreeting.isBlank() ? null : transferFilledGreeting);
            }
            if (transferUnfilledGreeting != null) {
                qr.setTransferUnfilledGreeting(transferUnfilledGreeting.isBlank() ? null : transferUnfilledGreeting);
            }
            qrCodeRepo.save(qr);
            redirect.addFlashAttribute("message", "在职继承问候语配置已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/qrcodes/" + id;
    }

    @PostMapping("/batch-config")
    public String batchConfig(@RequestParam List<Long> ids,
                               @RequestParam(required = false) String welcomeText,
                               @RequestParam(required = false) Long formTemplateId,
                               @RequestParam(required = false) Long groupId,
                               RedirectAttributes redirect) {
        int count = 0;
        for (Long id : ids) {
            try {
                QrCode qr = qrCodeService.getById(id);
                if (welcomeText != null && !welcomeText.isBlank()) qr.setWelcomeText(welcomeText);
                if (formTemplateId != null) qr.setFormTemplateId(formTemplateId);
                if (groupId != null) qr.setGroupId(groupId);
                qrCodeRepo.save(qr);
                count++;
            } catch (Exception e) {
                log.warn("批量配置失败: id={}", id, e);
            }
        }
        redirect.addFlashAttribute("message", "已更新 " + count + " 个活码");
        return "redirect:/qrcodes";
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
     * 批量回收闲置接待员 — 标记 removed + 恢复池 standby。
     */
    @PostMapping("/{id}/agents/batch-recycle")
    @ResponseBody
    public Map<String, Object> batchRecycleAgents(
            @PathVariable Long id,
            @RequestBody BatchRecycleRequest request) {
        return qrCodeService.batchRecycleAgents(id, request.getAgentIds());
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
                               HttpServletResponse response) throws IOException {
        QrCode qr = qrCodeService.getById(id);
        if (qr.getQrUrl() == null || qr.getQrUrl().isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "该活码暂无二维码图片");
            return;
        }
        // 服务端代理抓取企微原图，设置 attachment 强制浏览器下载
        URL url = new URL(qr.getQrUrl());
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.connect();
        // 手动构建 Content-Disposition 头，用 RFC 5987 编码确保中文文件名不乱码
        String filename = qr.getRegionDistrict() + "-" + qr.getSchoolName() + "-" + qr.getRegionCity() + ".png";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.setContentLength(conn.getContentLength());
        try (InputStream in = conn.getInputStream()) {
            in.transferTo(response.getOutputStream());
        }
        response.getOutputStream().flush();
    }

    /**
     * 批量下载活码二维码（企微原图），打包为 ZIP 文件。
     *
     * <p>POST /qrcodes/batch-download —— 对指定的活码 ID 列表逐一从企微服务器
     * 代理下载原图（{@link QrCode#getQrUrl()}），将所有 PNG 打包到内存中的 ZIP 文件。
     * 不重新生成二维码，直接使用企微创建活码时返回的原图 URL。</p>
     *
     * <p>容错处理：单个活码下载失败时记录警告日志并跳过，不影响其余活码的打包。</p>
     *
     * <p>注意：ZIP 在内存中构建（{@link ByteArrayOutputStream}），
     * 不落盘，适合中小批量下载。若批量过大需考虑内存占用。</p>
     *
     * @param ids      活码 ID 列表
     * @param response {@link HttpServletResponse}
     * @throws IOException 写入响应流时可能抛出
     */
    @PostMapping("/batch-download")
    public void downloadBatch(@RequestParam List<Long> ids,
                              HttpServletResponse response) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Long id : ids) {
                try {
                    QrCode qr = qrCodeService.getById(id);
                    if (qr.getQrUrl() == null || qr.getQrUrl().isBlank()) {
                        log.warn("批量下载跳过（无原图URL）: id={}", id);
                        continue;
                    }

                    // 从企微服务器代理下载原图（与 downloadSingle 一致）
                    java.net.URL url = new java.net.URL(qr.getQrUrl());
                    java.net.URLConnection conn = url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.connect();

                    // 文件名：区县-学校名-城市.png
                    String entryName = (qr.getRegionDistrict() != null ? qr.getRegionDistrict() : "")
                        + "-" + (qr.getSchoolName() != null ? qr.getSchoolName() : qr.getId())
                        + "-" + (qr.getRegionCity() != null ? qr.getRegionCity() : "")
                        + ".png";
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);

                    try (InputStream in = conn.getInputStream()) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                } catch (Exception e) {
                    log.warn("批量下载跳过: id={}, error={}", id, e.getMessage());
                }
            }
        }

        byte[] zipBytes = baos.toByteArray();

        response.setContentType("application/zip");
        String zipFilename = "qrcodes_" + java.time.LocalDate.now() + ".zip";
        String encoded = URLEncoder.encode(zipFilename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setContentLength(zipBytes.length);
        response.getOutputStream().write(zipBytes);
        response.getOutputStream().flush();
    }

    /**
     * 获取场景预设配置，供前端创建活码时使用。
     *
     * <p>返回各场景的扫码率、预激活阈值以及全局默认日限，
     * 前端据此实时计算预估接待员数。</p>
     */
    @GetMapping("/api/config/scene")
    @ResponseBody
    public Map<String, Object> getSceneConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> scenes = new LinkedHashMap<>();

        for (Scene s : Scene.values()) {
            SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(s);
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("scanRatio", preset.getScanRatio());
            cfg.put("urgentRatio", preset.getUrgentRatio());
            scenes.put(s.name(), cfg);
        }
        result.put("scenes", scenes);
        result.put("dailyMax", dailyMaxDefault);
        return result;
    }

    /**
     * 在职继承预览 —— 查询指定活码的待转移客户数量。
     *
     * <p>GET /qrcodes/{id}/transfer/preview —— 统计该活码下所有接待员
     * 自今天 00:00 起添加的客户总数，以及接待员人数和服务老师是否已配置。
     * 用于在手动触发在职继承前预览影响范围。
     * </p>
     *
     * @param id 活码 ID
     * @return Map 包含：
     *         <ul>
     *           <li>{@code receptionistCount} —— 接待员人数</li>
     *           <li>{@code customerCount} —— 今天添加的待转移客户数</li>
     *           <li>{@code error} —— 错误信息（仅在出错或未配置服务老师时出现）</li>
     *         </ul>
     */
    @GetMapping("/{id}/transfer/preview")
    @ResponseBody
    public Map<String, Object> transferPreview(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QrCode qr = qrCodeService.getById(id);
            List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .toList();

            // 统计接待员人数（含 dual，仅排除 removed）
            long recCount = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                          || a.getRole() == QrAgent.AgentRole.dual)
                .count();

            // 检查是否有服务老师（含 dual）
            boolean hasService = agents.stream()
                .anyMatch(a -> a.getRole() == QrAgent.AgentRole.service
                           || a.getRole() == QrAgent.AgentRole.dual);

            if (!hasService) {
                result.put("error", "该活码未配置服务老师");
                return result;
            }

            // 统计所有接待员今天 00:00 起添加的客户总数
            LocalDateTime todayStart = LocalDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
            long customerCount = 0;
            for (QrAgent a : agents) {
                if (a.getRole() == QrAgent.AgentRole.receptionist
                        || a.getRole() == QrAgent.AgentRole.dual) {
                    customerCount += customerRepo
                        .countByAddedAgentAndAddTimeAfter(a.getAgentUserid(), todayStart);
                }
            }
            result.put("receptionistCount", recCount);
            result.put("customerCount", customerCount);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 手动触发生在职继承 —— 对指定活码立即执行客户转移。
     *
     * <p>POST /qrcodes/{id}/transfer/trigger —— 将该活码下所有接待员
     * 自今天 00:00 起添加的客户转移给服务老师。转移事件通过 XADD 写入 Redis Stream
     * {@value RedisConfig#TRANSFER_STREAM_KEY}，由 {@code TransferWorker} 异步消费执行。
     * </p>
     *
     * <p>与 {@link com.bookstore.qrcode.job.InheritanceJob#execute()} 逻辑一致，
     * 但作用域限定为单个活码，适用于管理员即时操作场景。
     * </p>
     *
     * @param id 活码 ID
     * @return Map 包含：
     *         <ul>
     *           <li>{@code transferred} —— 已发起的转移事件数</li>
     *           <li>{@code error} —— 错误信息（仅在出错或缺少必要角色时出现）</li>
     *         </ul>
     */
    @PostMapping("/{id}/transfer/trigger")
    @ResponseBody
    public Map<String, Object> transferTrigger(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QrCode qr = qrCodeService.getById(id);
            List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .toList();

            // 筛选接待员（含 dual，仅排除 removed）
            List<QrAgent> receptionists = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                          || a.getRole() == QrAgent.AgentRole.dual)
                .toList();

            // 查找服务老师（含 dual，仅活跃状态）
            QrAgent serviceTeacher = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.service
                          || a.getRole() == QrAgent.AgentRole.dual)
                .findFirst().orElse(null);

            if (receptionists.isEmpty()) {
                result.put("error", "该活码未配置接待员");
                return result;
            }
            if (serviceTeacher == null) {
                result.put("error", "该活码未配置服务老师");
                return result;
            }

            // 今天 00:00:00 作为时间下限（手动触发处理当日客户）
            LocalDateTime todayStart = LocalDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

            int totalTransfers = 0;
            for (QrAgent rec : receptionists) {
                // 跳过自己转自己（dual 同时是接待员和服务老师）
                if (rec.getAgentUserid().equals(serviceTeacher.getAgentUserid())) {
                    continue;
                }
                List<Customer> customers = customerRepo
                    .findByAddedAgentAndAddTimeAfter(rec.getAgentUserid(), todayStart);

                for (Customer c : customers) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("customer_id", c.getId().toString());
                    event.put("from_userid", rec.getAgentUserid());
                    event.put("to_userid", serviceTeacher.getAgentUserid());
                    event.put("external_userid", c.getExternalUserid());
                    event.put("state", qr.getSchoolId());

                    redisTemplate.opsForStream().add(
                        RedisConfig.TRANSFER_STREAM_KEY,
                        Map.of("event", objectMapper.writeValueAsString(event)));
                    totalTransfers++;
                }
            }
            result.put("transferred", totalTransfers);
            log.info("手动触发生在职继承: qrCodeId={}, 转移数={}", id, totalTransfers);
        } catch (Exception e) {
            log.error("手动触发生在职继承失败: qrCodeId={}", id, e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 全量补转：将该活码下所有历史客户（不限添加时间）XADD 到转移流。
     *
     * <p>POST /qrcodes/{id}/transfer/backfill —— 与 transferTrigger 不同，
     * 本接口不限今日，而是扫描接待员名下的所有客户。
     * {@link com.bookstore.qrcode.service.TransferService#initiate} 的去重逻辑
     * 会自动跳过已存在转移记录的客户。
     * </p>
     */
    @PostMapping("/{id}/transfer/backfill")
    @ResponseBody
    public Map<String, Object> transferBackfill(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QrCode qr = qrCodeService.getById(id);
            List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .toList();

            List<QrAgent> receptionists = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                          || a.getRole() == QrAgent.AgentRole.dual)
                .toList();

            QrAgent serviceTeacher = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.service
                          || a.getRole() == QrAgent.AgentRole.dual)
                .findFirst().orElse(null);

            if (receptionists.isEmpty()) {
                result.put("error", "该活码未配置接待员");
                return result;
            }
            if (serviceTeacher == null) {
                result.put("error", "该活码未配置服务老师");
                return result;
            }

            int totalTransfers = 0;
            for (QrAgent rec : receptionists) {
                if (rec.getAgentUserid().equals(serviceTeacher.getAgentUserid())) {
                    continue;
                }
                // 不限时间，查该接待员的所有客户
                List<Customer> customers = customerRepo.findByAddedAgent(rec.getAgentUserid());

                for (Customer c : customers) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("customer_id", c.getId().toString());
                    event.put("from_userid", rec.getAgentUserid());
                    event.put("to_userid", serviceTeacher.getAgentUserid());
                    event.put("external_userid", c.getExternalUserid());
                    event.put("state", qr.getSchoolId());

                    redisTemplate.opsForStream().add(
                        RedisConfig.TRANSFER_STREAM_KEY,
                        Map.of("event", objectMapper.writeValueAsString(event)));
                    totalTransfers++;
                }
            }
            result.put("transferred", totalTransfers);
            log.info("全量补发生在职继承: qrCodeId={}, 转移数={}", id, totalTransfers);
        } catch (Exception e) {
            log.error("全量补发生在职继承失败: qrCodeId={}", id, e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 在职继承记录页 —— 查看指定活码的客户转移历史。
     *
     * <p>GET /qrcodes/{id}/transfers —— 分页展示该活码下的所有客户转移记录，
     * 按转移时间倒序排列，每页 20 条。同时构建 userid 到姓名的映射表，
     * 用于在页面上将企微 userid 解析为中文姓名展示。
     * </p>
     *
     * @param id    活码 ID
     * @param page  页码，从 0 开始，默认 0
     * @param model Spring MVC {@link Model}
     *              <ul>
     *                <li>{@code qr} —— 活码实体</li>
     *                <li>{@code transfers} —— 转移记录分页结果</li>
     *                <li>{@code nameMap} —— userid 到姓名的映射</li>
     *              </ul>
     * @return 模板视图名 {@code "qrcode/transfers"}
     */
    @GetMapping("/{id}/transfers")
    public String transfers(@PathVariable Long id,
                             @RequestParam(defaultValue = "0") int page,
                             Model model) {
        QrCode qr = qrCodeService.getById(id);
        model.addAttribute("qr", qr);

        Page<CustomerTransfer> transfers = transferRepo
            .findByQrCodeIdOrderByTransferTimeDesc(id,
                PageRequest.of(page, 20));
        model.addAttribute("transfers", transfers);

        // Build name map from Employee table — single batch query instead of N+1
        Set<String> userids = new HashSet<>();
        for (CustomerTransfer t : transfers.getContent()) {
            userids.add(t.getFromUserid());
            userids.add(t.getToUserid());
        }
        Map<String, String> nameMap = new HashMap<>();
        if (!userids.isEmpty()) {
            employeeRepo.findByUseridIn(userids).forEach(e ->
                nameMap.put(e.getUserid(), e.getName()));
        }
        // Fallback to userid for any not found in Employee table
        for (CustomerTransfer t : transfers.getContent()) {
            nameMap.putIfAbsent(t.getFromUserid(), t.getFromUserid());
            nameMap.putIfAbsent(t.getToUserid(), t.getToUserid());
        }
        model.addAttribute("nameMap", nameMap);

        return "qrcode/transfers";
    }

    // ==================== 异常员工核验 ====================

    /**
     * 异常员工核验页 —— 展示单个活码下所有异常员工。
     *
     * <p>GET /qrcodes/{id}/verify-agents —— 调用 {@link QrCodeService#getAnomalousAgents(Long)}
     * 获取该活码下所有状态异常的 active 接待员，展示异常类型（已离职/未激活/已禁用/已熔断等），
     * 支持一键替换（移除异常员工并从全局池补入替补）。</p>
     *
     * @param id    活码 ID
     * @param model Spring MVC {@link Model}
     * @return 模板视图名 {@code "qrcode/verify-agents"}
     */
    @GetMapping("/{id}/verify-agents")
    public String verifyAgents(@PathVariable Long id, Model model) {
        QrCode qr = qrCodeService.getById(id);
        model.addAttribute("qr", qr);

        List<Map<String, Object>> anomalies = qrCodeService.getAnomalousAgents(id);
        model.addAttribute("anomalies", anomalies);
        model.addAttribute("anomalyCount", anomalies.size());

        // 全局池 standby 数量，供前端判断是否可替换
        model.addAttribute("poolStandby", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby));

        return "qrcode/verify-agents";
    }

    /**
     * 替换单个活码下所有异常员工。
     *
     * <p>POST /qrcodes/{id}/verify-agents/replace —— 调用
     * {@link QrCodeService#replaceAnomalyAgents(Long)} 执行替换，
     * 完成后重定向回 verify-agents 页面显示结果。</p>
     *
     * @param id       活码 ID
     * @param redirect {@link RedirectAttributes}
     * @return 重定向到 {@code "redirect:/qrcodes/{id}/verify-agents"}
     */
    @PostMapping("/{id}/verify-agents/replace")
    public String replaceAnomalyAgents(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            Map<String, Object> result = qrCodeService.replaceAnomalyAgents(id);
            int removed = (int) result.get("removed");
            int replaced = (int) result.get("replaced");
            int shortfall = (int) result.get("shortfall");
            if (removed == 0) {
                redirect.addFlashAttribute("message", "✅ 未发现异常员工，无需替换");
            } else if (shortfall == 0) {
                redirect.addFlashAttribute("message",
                    String.format("✅ 已移除 %d 人并补入 %d 人，全部替换完成", removed, replaced));
            } else {
                redirect.addFlashAttribute("error",
                    String.format("⚠️ 已移除 %d 人但仅补入 %d 人，缺口 %d 人（全局池不足）",
                        removed, replaced, shortfall));
            }
        } catch (Exception e) {
            log.error("替换异常员工失败: qrCodeId={}", id, e);
            redirect.addFlashAttribute("error", "替换失败: " + e.getMessage());
        }
        return "redirect:/qrcodes/" + id + "/verify-agents";
    }

    /**
     * 全部活码异常员工总览页。
     *
     * <p>GET /qrcodes/verify-all-agents —— 展示所有活跃活码中的异常员工汇总，
     * 按活码分组展示，每个活码显示异常员工数量和详情。</p>
     *
     * @param model Spring MVC {@link Model}
     * @return 模板视图名 {@code "qrcode/verify-all-agents"}
     */
    @GetMapping("/verify-all-agents")
    public String verifyAllAgents(Model model) {
        List<Map<String, Object>> allAnomalies = qrCodeService.getAllAnomalousAgents();
        model.addAttribute("allAnomalies", allAnomalies);

        int totalAgents = allAnomalies.stream()
            .mapToInt(g -> (int) g.get("count")).sum();
        model.addAttribute("totalAnomalyCount", totalAgents);
        model.addAttribute("totalQrCount", allAnomalies.size());

        // 全局池 standby 数量
        model.addAttribute("poolStandby", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby));

        return "qrcode/verify-all-agents";
    }

    /**
     * 批量替换全部活码的异常员工。
     *
     * <p>POST /qrcodes/verify-all-agents/replace —— 遍历所有活跃活码，
     * 逐个调用 {@link QrCodeService#replaceAnomalyAgents(Long)} 替换。</p>
     *
     * @param redirect {@link RedirectAttributes}
     * @return 重定向到 {@code "redirect:/qrcodes/verify-all-agents"}
     */
    @PostMapping("/verify-all-agents/replace")
    public String replaceAllAnomalyAgents(RedirectAttributes redirect) {
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        int totalRemoved = 0, totalReplaced = 0, totalShortfall = 0;
        for (QrCode qr : activeQrs) {
            try {
                Map<String, Object> r = qrCodeService.replaceAnomalyAgents(qr.getId());
                totalRemoved += (int) r.get("removed");
                totalReplaced += (int) r.get("replaced");
                totalShortfall += (int) r.get("shortfall");
            } catch (Exception e) {
                log.error("批量替换异常员工失败: qrCodeId={}", qr.getId(), e);
            }
        }
        if (totalRemoved == 0) {
            redirect.addFlashAttribute("message", "✅ 未发现异常员工");
        } else if (totalShortfall == 0) {
            redirect.addFlashAttribute("message",
                String.format("✅ 已移除 %d 人并补入 %d 人，全部替换完成", totalRemoved, totalReplaced));
        } else {
            redirect.addFlashAttribute("error",
                String.format("⚠️ 已移除 %d 人但仅补入 %d 人，缺口 %d 人（全局池不足）",
                    totalRemoved, totalReplaced, totalShortfall));
        }
        return "redirect:/qrcodes/verify-all-agents";
    }

    /**
     * 在职继承预览页 —— 展示待转移客户明细及转移路径。
     *
     * <p>GET /qrcodes/{id}/transfer-preview —— 对每个接待员列出今天待转移的客户，
     * 并标注转移路径（from → to）、各客户的转移状态（待转移 / 已发起 / 已完成）。
     * 支持分页（每页 3 个接待员）和选择性批量转移。
     * </p>
     */
    @GetMapping("/{id}/transfer-preview")
    public String transferPreviewPage(@PathVariable Long id,
                                       @RequestParam(defaultValue = "0") int page,
                                       Model model) {
        QrCode qr = qrCodeService.getById(id);
        model.addAttribute("qr", qr);

        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .toList();

        // 服务老师（含 dual，仅排除 removed）
        QrAgent serviceTeacher = agents.stream()
            .filter(a -> a.getRole() == QrAgent.AgentRole.service
                      || a.getRole() == QrAgent.AgentRole.dual)
            .findFirst().orElse(null);
        if (serviceTeacher != null) {
            model.addAttribute("serviceName",
                employeeRepo.findByUserid(serviceTeacher.getAgentUserid())
                    .map(Employee::getName).orElse(serviceTeacher.getAgentUserid()));
        }

        // 时间窗口说明
        LocalTime now = LocalTime.now();
        boolean isDaytime = !now.isBefore(LocalTime.of(dayStartHour, 0))
                         && now.isBefore(LocalTime.of(dayEndHour, 0));
        model.addAttribute("isDaytime", isDaytime);

        LocalDateTime todayStart = LocalDateTime.now()
            .withHour(0).withMinute(0).withSecond(0).withNano(0);

        List<Map<String, Object>> allPaths = new ArrayList<>();
        int totalWaiting = 0, totalPending = 0, totalConfirmed = 0;

        for (QrAgent agent : agents) {
            if (agent.getRole() != QrAgent.AgentRole.receptionist
                    && agent.getRole() != QrAgent.AgentRole.dual) continue;
            if (serviceTeacher != null
                    && agent.getAgentUserid().equals(serviceTeacher.getAgentUserid())) continue;

            String agentName = employeeRepo.findByUserid(agent.getAgentUserid())
                .map(Employee::getName).orElse(agent.getAgentUserid());

            List<Customer> todayCustomers = customerRepo
                .findByAddedAgentAndAddTimeAfter(agent.getAgentUserid(), todayStart);

            List<Map<String, Object>> customerRows = new ArrayList<>();
            for (Customer c : todayCustomers) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("customerId", c.getId());
                row.put("externalUserid", c.getExternalUserid());
                row.put("name", c.getName() != null ? c.getName() : "未知");
                row.put("addTime", c.getAddTime());
                List<CustomerTransfer> transfers = transferRepo.findByCustomerId(c.getId());
                CustomerTransfer active = transfers.stream()
                    .filter(t -> t.getStatus() == CustomerTransfer.TransferStatus.pending_confirm
                              || t.getStatus() == CustomerTransfer.TransferStatus.confirmed)
                    .findFirst().orElse(null);
                if (active != null) {
                    row.put("transferStatus", active.getStatus().name());
                    if (active.getStatus() == CustomerTransfer.TransferStatus.confirmed)
                        totalConfirmed++; else totalPending++;
                } else {
                    row.put("transferStatus", "waiting");
                    totalWaiting++;
                }
                customerRows.add(row);
            }

            if (!customerRows.isEmpty()) {
                Map<String, Object> path = new LinkedHashMap<>();
                path.put("fromName", agentName);
                path.put("fromUserid", agent.getAgentUserid());
                // 每路径最多展示 50 条，超出部分显示截断提示
                int maxShow = 50;
                path.put("customerRows", customerRows.size() <= maxShow
                    ? customerRows : customerRows.subList(0, maxShow));
                path.put("customerCount", customerRows.size());
                path.put("truncated", customerRows.size() > maxShow);
                allPaths.add(path);
            }
        }

        // 分页：每页 3 个接待员
        int pageSize = 3;
        int totalPages = Math.max(1, (allPaths.size() + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int fromIdx = safePage * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, allPaths.size());
        List<Map<String, Object>> paths = allPaths.isEmpty()
            ? List.of() : allPaths.subList(fromIdx, toIdx);

        model.addAttribute("paths", paths);
        model.addAttribute("totalWaiting", totalWaiting);
        model.addAttribute("totalPending", totalPending);
        model.addAttribute("totalConfirmed", totalConfirmed);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("dayStartHour", dayStartHour);
        model.addAttribute("dayEndHour", dayEndHour);

        return "qrcode/transfer-preview";
    }

    /**
     * 选择性批量转移 —— 只转移指定接待员的客户。
     *
     * <p>POST /qrcodes/{id}/transfer/trigger-selected —— 接收 fromUserid 列表，
     * 将每个接待员今天添加的客户 XADD 到 TRANSFER_STREAM。</p>
     *
     * @param id          活码 ID
     * @param fromUserids 要转移的接待员 userid 列表
     * @return Map 含 transferred 总数
     */
    @PostMapping("/{id}/transfer/trigger-selected")
    @ResponseBody
    public Map<String, Object> transferSelected(@PathVariable Long id,
                                                 @RequestParam List<String> fromUserids) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QrCode qr = qrCodeService.getById(id);
            List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .toList();
            QrAgent serviceTeacher = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.service
                          || a.getRole() == QrAgent.AgentRole.dual)
                .findFirst().orElse(null);
            if (serviceTeacher == null) {
                result.put("error", "该活码未配置服务老师");
                return result;
            }

            LocalDateTime todayStart = LocalDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
            int total = 0;
            for (String uid : fromUserids) {
                if (uid.equals(serviceTeacher.getAgentUserid())) continue;
                List<Customer> customers = customerRepo
                    .findByAddedAgentAndAddTimeAfter(uid, todayStart);
                for (Customer c : customers) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("customer_id", c.getId().toString());
                    event.put("from_userid", uid);
                    event.put("to_userid", serviceTeacher.getAgentUserid());
                    event.put("external_userid", c.getExternalUserid());
                    event.put("state", qr.getSchoolId());
                    redisTemplate.opsForStream().add(RedisConfig.TRANSFER_STREAM_KEY,
                        Map.of("event", objectMapper.writeValueAsString(event)));
                    total++;
                }
            }
            result.put("transferred", total);
            log.info("选择性在职继承: qrCodeId={}, from={}, 转移数={}", id, fromUserids, total);
        } catch (Exception e) {
            log.error("选择性在职继承失败: qrCodeId={}", id, e);
            result.put("error", e.getMessage());
        }
        return result;
    }

}
