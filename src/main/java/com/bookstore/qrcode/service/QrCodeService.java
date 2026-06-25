package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.SceneConfigProperties;
import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.bookstore.qrcode.wecom.WecomRateLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <h2>活码核心服务</h2>
 *
 * <p>活码（企业微信「联系我」二维码）的全生命周期管理服务，是系统中最核心的业务模块。
 * 活码将学校的服务老师和后备接待员绑定到一枚企微二维码上，客户扫码后由企微自动分配接待员工，
 * 从而实现客户分流与会话承接。</p>
 *
 * <h3>主要职责</h3>
 * <ul>
 *   <li><b>查询</b> — 分页搜索、按 ID 查询、获取绑定员工列表、获取后备池列表</li>
 *   <li><b>手动创建</b> — 调用企微 API 创建「联系我」二维码 → 写入 DB → 绑定服务老师与接待员</li>
 *   <li><b>批量导入</b> — 异步解析 Excel，逐行创建活码，进度通过 Redis Hash 实时跟踪</li>
 *   <li><b>删除</b> — 级联删除企微端活码、联系人关联、后备池、轮换日志</li>
 *   <li><b>企微同步</b> — 将本地联系人状态（active 的服务老师 + 接待员）推送到企微</li>
 *   <li><b>后备池管理</b> — 添加/移除/排序后备接待员，为轮换引擎提供候选池</li>
 *   <li><b>联系人管理</b> — 添加/移除/更新活码下的接待员与日接上限</li>
 *   <li><b>状态与配置</b> — 活码启用/停用、轮换模式切换、阈值配置、样式配置</li>
 * </ul>
 *
 * <h3>核心实体关系</h3>
 * <pre>
 *   QrCode (活码主表)
 *     ├── QrAgent (活码联系人：全部为 receptionist)
 *     ├── GlobalAgentPool (全局员工池：所有可用员工的统一池)
 *     └── QrRotateLog (轮换日志：记录上下线操作)
 *   Agent (全局员工表，独立于活码)
 * </pre>
 *
 * @author Bookstore Dev
 * @since 1.0
 * @see QrCode
 * @see QrAgent
 * @see GlobalAgentPool
 * @see QrRotateLog
 * @see Agent
 * @see WecomApiClient
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeService {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final AgentRepository agentRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final GlobalAgentPoolRepository poolRepo;
    private final GlobalAgentPoolService poolService;
    private final AlertService alertService;
    private final EmployeeRepository employeeRepo;
    private final WechatSyncHealingService healingService;
    private final SceneConfigProperties sceneConfig;

    @Qualifier("taskExecutor")
    private final Executor taskExecutor;

    // 自注入代理：解决 executeBatchImport → create() 的 @Transactional 自调用失效问题
    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private QrCodeService self;

    /** 默认日接待上限，可通过 app.agent.daily-max-default 配置 */
    @Value("${app.agent.daily-max-default:100}")
    private int dailyMaxDefault;

    /** 批量导入时日接待上限，可通过 app.agent.batch-import-daily-max 配置 */
    @Value("${app.agent.batch-import-daily-max:200}")
    private int batchImportDailyMax;

    // ==================== 查询 ====================

    /**
     * 分页搜索活码。
     *
     * <p>支持按学校名称/ID模糊匹配（keyword）、按城市/区县精确筛选、
     * 按活码状态过滤。底层委托给 {@link QrCodeRepository#search} 的 JPA Specification 查询。</p>
     *
     * @param keyword  搜索关键词（匹配学校名称或学校ID），可为 {@code null}
     * @param city     城市精确筛选，可为 {@code null}
     * @param district 区县精确筛选，可为 {@code null}
     * @param status   活码状态筛选（{@code active / inactive}），可为 {@code null} 表示全部
     * @param pageable 分页参数（页码、每页条数、排序）
     * @return 活码分页结果
     */
    public Page<QrCode> search(String keyword, String city, String district,
                                QrCode.QrCodeStatus status, Pageable pageable) {
        return qrCodeRepo.search(keyword, city, district, status, null, pageable);
    }

    /**
     * 根据主键 ID 获取活码。
     *
     * @param id 活码主键 ID
     * @return 活码实体
     * @throws RuntimeException 活码不存在时抛出
     */
    public QrCode getById(Long id) {
        return qrCodeRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("活码不存在: " + id));
    }

    /**
     * 获取活码下所有联系人（服务老师 + 接待员），按排序号升序排列。
     *
     * <p>返回结果包含所有状态的记录（active / inactive / removed），
     * 调用方按需过滤。排序号决定企微分配时的优先顺序。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @return 联系人列表，按 {@code sortOrder} 升序
     */
    public List<QrAgent> getAgents(Long qrCodeId) {
        return qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId);
    }

    /**
     * 获取全局员工池分页数据 — 状态优先排序，DB 侧分页。
     *
     * @param qrCodeId 活码主键 ID（保留参数兼容性）
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @return 全局池分页数据
     */
    public Page<GlobalAgentPool> getBackups(Long qrCodeId, int page, int size) {
        return poolRepo.findAllWithStatusPriority(PageRequest.of(page, size));
    }

    /**
     * 获取全局池各状态统计 — 改用三条 COUNT 查询替代全量加载。
     *
     * @return Map 包含 standby/full/blocked 计数
     */
    public Map<String, Long> getPoolStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("standby", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby));
        stats.put("full", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.full));
        stats.put("blocked", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.blocked));
        return stats;
    }

    /**
     * 获取全局池全部 userid 列表（轻量投影，只查 userid）。
     */
    public List<String> getAllPoolUserids() {
        return poolRepo.findAllAgentUserids();
    }

    // ==================== 手动创建 ====================

    /**
     * 手动创建活码（完整流程：企微 API → DB 写入 → 绑定员工）。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>校验学校 ID 唯一性：一个学校 ID 最多创建一个活码</li>
     *   <li>构造企微「联系我」二维码参数，调用 {@link WecomApiClient#createContactWay(String)}
     *       在企微端创建活码，获取 {@code config_id} 和 {@code qr_code} URL</li>
     *   <li>写入 {@link QrCode} 主表记录</li>
     *   <li>将员工写入 {@link QrAgent} 表并确保在 {@link GlobalAgentPool} 全局池中</li>
     * </ol>
     *
     * <p>步骤 1 的企微 API 调用在事务中执行：如果企微创建失败则抛出异常回滚事务，
     * 保证企微端与 DB 的一致性。如果企微调用成功但后续 DB 操作失败，
     * 则会产生一个孤儿活码在企微端——这是权衡后的设计，因为企微不提供事务性回滚。</p>
     *
     * @param req 创建请求 DTO，包含学校信息、服务老师、接待员、欢迎语等配置
     * @return 持久化后的 {@link QrCode} 实体
     * @throws RuntimeException 学校 ID 已存在、企微 API 调用失败、员工绑定失败时抛出
     * @see QrCodeCreateRequest
     */
    @Transactional
    public QrCode create(QrCodeCreateRequest req) {
        // 校验学校 ID 唯一性：活码与学校一一对应，防止重复创建
        if (qrCodeRepo.existsBySchoolId(req.getSchoolId())) {
            throw new RuntimeException("学校ID已存在: " + req.getSchoolId());
        }

        // 根据场景自动计算所需接待员总数
        // 公式：ceil(学生人数 × 场景扫码率 / 员工日限)，最少 1 人，最多 100 人
        // 用户手动指定 initialAgentCount 时跳过自动计算
        if (req.getStudentCount() != null && req.getStudentCount() > 0
            && req.getInitialAgentCount() == null) {
            Scene scene = req.getScene() != null ? req.getScene() : Scene.daily_push;
            SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(scene);
            int expectedScans = (int) Math.ceil(req.getStudentCount() * preset.getScanRatio());
            int need = Math.max(1, Math.min(100,
                (int) Math.ceil((double) expectedScans / dailyMaxDefault)));
            req.setInitialAgentCount(need);
            log.info("学校人数={}, 场景={}, 扫码率={}, 自动计算 initialAgentCount={}",
                req.getStudentCount(), scene.name(), preset.getScanRatio(), need);
        }

        // 1. 调用企微 API 创建「联系我」二维码（在 DB 写入之前，失败回滚事务）
        String qrRequestJson = buildContactWayJson(req);
        JsonNode result;
        try {
            result = wecomApi.createContactWay(qrRequestJson);
        } catch (WecomApiException e) {
            throw new RuntimeException("创建企微活码失败 [" + e.getErrcode() + "]: " + e.getErrmsg(), e);
        }
        // config_id 是企微端活码的唯一标识，后续更新/删除都依赖它
        JsonNode configIdNode = result.get("config_id");
        JsonNode qrCodeNode = result.get("qr_code");
        if (configIdNode == null || configIdNode.isNull()) {
            throw new IllegalStateException("企微 createContactWay 响应缺少 config_id");
        }
        if (qrCodeNode == null || qrCodeNode.isNull()) {
            throw new IllegalStateException("企微 createContactWay 响应缺少 qr_code");
        }
        String configId = configIdNode.asText();
        // qr_code 是活码图片的 URL，前端直接展示
        String qrUrl = qrCodeNode.asText();

        // 场景联动阈值
        Scene effectiveScene = req.getScene() != null ? req.getScene() : Scene.daily_push;
        SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(effectiveScene);

        // 2. 保存活码主表记录
        // createMode 标记为 manual 以区别于批量导入（batch）
        QrCode qr = QrCode.builder()
            .schoolName(req.getSchoolName())
            .schoolId(req.getSchoolId())
            .regionCity(req.getRegionCity())
            .regionDistrict(req.getRegionDistrict())
            .qrConfigId(configId)
            .qrUrl(qrUrl)
            .welcomeConfig(buildWelcomeConfig(req))
            .status(QrCode.QrCodeStatus.active)
            .rotateMode(QrCode.RotateMode.auto)
            .createMode(QrCode.CreateMode.manual)
            .remark(req.getRemark())
            .transferTargetUserid(req.getTransferTargetUserid())
            .initialAgentCount(req.getInitialAgentCount() != null
                ? req.getInitialAgentCount() : 1)
            .studentCount(req.getStudentCount())
            .customTags(req.getCustomTags())
            .scene(effectiveScene)
            .departmentId(req.getDepartmentId())
            .warnRatio(80)
            .urgentRatio(preset.getUrgentRatio())
            .build();
        qr = qrCodeRepo.save(qr);

        // 3. 绑定员工：从全局池取人写入 QrAgent
        bindAgents(qr.getId(), req);

        // 4. 同步企微活码（事务提交后执行，同步失败不回滚活码创建）
        final Long finalQrId = qr.getId();
        final String finalConfigId = qr.getQrConfigId();
        final String finalSchoolName = qr.getSchoolName();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncQrUsersToWechat(finalQrId);
                    } catch (Exception e) {
                        log.error("活码创建后同步企微失败（活码已保存，需手动重同步）: qrId={}, configId={}, error={}",
                            finalQrId, finalConfigId, e.getMessage());
                        try {
                            alertService.createAlert("system", "qr_sync_failed_on_create",
                                AgentAlert.AlertSeverity.high,
                                String.format("活码「%s」(config_id=%s) 创建后同步企微失败：%s",
                                    finalSchoolName, finalConfigId, e.getMessage()),
                                AgentAlert.AutoAction.none, finalQrId);
                        } catch (Exception inner) {
                            log.error("活码创建后同步企微失败告警发送异常: qrId={}", finalQrId, inner);
                        }
                    }
                }
            });

        return qr;
    }

    // ==================== 批量导入 ====================

    /**
     * 异步批量导入活码。
     *
     * <p>上传 Excel 文件后，由该方法启动异步导入任务。导入进度通过 Redis Hash 实时跟踪，
     * 前端可轮询 {@link #getBatchImportProgress(String)} 获取进度。</p>
     *
     * <h3>Redis 进度数据结构</h3>
     * <pre>
     *   Key: batch:import:{taskId}
     *   Fields: total, success, fail, processed, status
     *   Status: processing → done
     *   失败详情: batch:import:{taskId}:fail:{N} (每个失败行一条)
     * </pre>
     *
     * @param file 上传的 Excel 文件（MultipartFile）
     * @return taskId 任务标识，用于查询进度
     */
    public String asyncBatchImport(MultipartFile file) {
        // 同步解析 Excel 为内存列表（解析本身很快，无需异步）
        List<Map<String, String>> rawItems = parseExcel(file);
        // 生成 8 位短 taskId，兼顾唯一性和可读性
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String progressKey = "batch:import:" + taskId;

        // 初始化 Redis 进度 Hash
        Map<String, String> init = new LinkedHashMap<>();
        init.put("total", String.valueOf(rawItems.size()));
        init.put("success", "0");
        init.put("fail", "0");
        init.put("processed", "0");
        init.put("status", "processing");
        redisTemplate.opsForHash().putAll(progressKey, init);
        redisTemplate.expire(progressKey, 30, TimeUnit.MINUTES);  // 30 分钟自动过期，防止 Redis 内存泄漏

        // 通过 CompletableFuture + 线程池异步执行，避免 @Async 自调用失效问题
        // 使用 self（Spring 代理）确保 @Transactional 在 create() 上生效
        CompletableFuture.runAsync(() -> self.executeBatchImport(taskId, rawItems), taskExecutor)
            .exceptionally(ex -> {
                log.error("批量导入异步任务异常终止: taskId={}", taskId, ex);
                redisTemplate.opsForHash().put(progressKey, "status", "error");
                String errMsg = ex.getMessage() != null ? ex.getMessage() : "未知错误";
                redisTemplate.opsForHash().put(progressKey, "error",
                    errMsg.substring(0, Math.min(errMsg.length(), 500)));
                return null;
            });

        return taskId;
    }

    /**
     * 异步批量导入的实际执行方法。
     *
     * <p>通过 {@link Async @Async} 注解在独立线程池中执行，不阻塞 HTTP 请求线程。
     * 逐行调用 {@link #create(QrCodeCreateRequest)} 创建活码，
     * 每条创建失败不影响后续行，失败详情写入 Redis。</p>
     *
     * @param taskId   任务标识
     * @param rawItems Excel 解析后的行数据列表
     */
    public void executeBatchImport(String taskId, List<Map<String, String>> rawItems) {
        String progressKey = "batch:import:" + taskId;
        int success = 0, fail = 0;
        int total = rawItems.size();

        log.info("批量导入开始执行: taskId={}, total={}", taskId, total);

        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, String> item = rawItems.get(i);
            String schoolName = item.get("schoolName");
            boolean rowDone = false;

            // 最多尝试 2 次（首次 + 限频重试 1 次）
            for (int attempt = 0; attempt < 2 && !rowDone; attempt++) {
                try {
                    if (attempt > 0) {
                        log.info("批量导入 [{}]: 第 {}/{} 行重试 (attempt={})", taskId, i + 1, total, attempt);
                    }
                    log.debug("批量导入 [{}]: 正在处理第 {}/{} 行 — {}", taskId, i + 1, total, schoolName);
                    // 将 Excel 行数据映射为创建请求 DTO
                    QrCodeCreateRequest req = new QrCodeCreateRequest();
                    req.setSchoolName(schoolName);
                    String schoolId = item.get("schoolId");
                    // 学校ID为空时自动生成（与手动创建一致：SCH + 时间戳）
                    if (schoolId == null || schoolId.isBlank()) {
                        schoolId = "SCH" + java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                        log.debug("批量导入 [{}]: 第 {} 行学校ID为空，已自动生成: {}", taskId, i + 1, schoolId);
                    }
                    req.setSchoolId(schoolId);
                    req.setRegionCity(item.get("regionCity"));
                    req.setRegionDistrict(item.get("regionDistrict"));
                    req.setServiceTeacherUserid(item.get("serviceTeacherUserid"));
                    req.setRemark(item.getOrDefault("remark", ""));
                    String studentCountStr = item.get("studentCount");
                    if (studentCountStr != null && !studentCountStr.isEmpty()) {
                        try { req.setStudentCount(Integer.valueOf(studentCountStr)); }
                        catch (NumberFormatException ignored) {}
                    }
                    String initialAgentStr = item.get("initialAgentCount");
                    if (initialAgentStr != null && !initialAgentStr.isEmpty()) {
                        try { req.setInitialAgentCount(Integer.valueOf(initialAgentStr)); }
                        catch (NumberFormatException ignored) {}
                    }
                    req.setReceptionistUserid(item.get("receptionistUserid"));
                    String dailyMaxStr = item.get("serviceDailyMax");
                    if (dailyMaxStr != null && !dailyMaxStr.isEmpty()) {
                        try { req.setServiceDailyMax(Integer.valueOf(dailyMaxStr)); }
                        catch (NumberFormatException ignored) {}
                    }
                    req.setWelcomeText(item.get("welcomeText"));

                    String sceneStr = item.get("scene");
                    if (sceneStr != null && !sceneStr.isBlank()) {
                        try { req.setScene(Scene.valueOf(sceneStr.trim().toLowerCase())); }
                        catch (IllegalArgumentException e) {
                            req.setScene(Scene.daily_push);
                        }
                    }

                    String deptIdStr = item.get("departmentId");
                    if (deptIdStr != null && !deptIdStr.isBlank()) {
                        try { req.setDepartmentId(Long.valueOf(deptIdStr.trim())); }
                        catch (NumberFormatException ignored) { }
                    }

                    // 通过 self（Spring 代理）调用 create()，确保 @Transactional 生效
                    self.create(req);
                    success++;
                    rowDone = true;
                    log.debug("批量导入 [{}]: 第 {}/{} 行创建成功 — {}", taskId, i + 1, total, schoolName);

                } catch (WecomRateLimitException e) {
                    // 限频：等待企微要求的 retry-after 秒数后重试
                    int waitSec = Math.max(e.getRetryAfterSeconds(), 60);
                    log.warn("批量导入 [{}]: 第 {}/{} 行触发限频，等待 {}s 后重试", taskId, i + 1, total, waitSec);
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    // 如果重试次数用完，记录失败
                    if (attempt == 1) {
                        fail++;
                        log.warn("批量导入 [{}]: 第 {}/{} 行限频重试仍失败 — {}", taskId, i + 1, total, schoolName);
                        String detailKey = progressKey + ":fail:" + fail;
                        redisTemplate.opsForValue().set(detailKey,
                            item.get("row") + "|" + schoolName + "|限频重试失败: " + e.getMessage(), 30, TimeUnit.MINUTES);
                        rowDone = true;
                    }
                } catch (Exception e) {
                    fail++;
                    log.warn("批量导入 [{}]: 第 {}/{} 行创建失败 — {}: {}", taskId, i + 1, total, schoolName, e.getMessage());
                    String detailKey = progressKey + ":fail:" + fail;
                    redisTemplate.opsForValue().set(detailKey,
                        item.get("row") + "|" + schoolName + "|" + e.getMessage(), 30, TimeUnit.MINUTES);
                    rowDone = true;
                }
            }

            // 每处理完一行就更新进度 Hash
            Map<String, String> progress = new LinkedHashMap<>();
            progress.put("total", String.valueOf(total));
            progress.put("success", String.valueOf(success));
            progress.put("fail", String.valueOf(fail));
            progress.put("processed", String.valueOf(i + 1));
            progress.put("status", "processing");
            redisTemplate.opsForHash().putAll(progressKey, progress);

            // 行间延迟 1 秒，避免触发企微限频
            if (i < rawItems.size() - 1) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        // 全部处理完毕，标记状态为 done
        redisTemplate.opsForHash().put(progressKey, "status", "done");
        log.info("批量导入完成: taskId={}, total={}, success={}, fail={}", taskId, total, success, fail);
    }

    /**
     * 获取批量导入的实时进度。
     *
     * <p>从 Redis 读取进度 Hash 的所有字段，返回给前端用于进度条展示。
     * 返回的 Map 包含以下 key：total（总数）、success（成功数）、fail（失败数）、
     * processed（已处理数）、status（processing / done）。</p>
     *
     * @param taskId 任务标识（由 {@link #asyncBatchImport(MultipartFile)} 返回）
     * @return Redis Hash 的所有 entries，为空 Map 表示任务不存在或已过期
     */
    public Map<Object, Object> getBatchImportProgress(String taskId) {
        return redisTemplate.opsForHash().entries("batch:import:" + taskId);
    }

    // ==================== 删除 ====================

    /**
     * 删除活码及其所有关联数据（级联删除）。
     *
     * <h3>删除顺序（按依赖关系反向）</h3>
     * <ol>
     *   <li>调用企微 API 删除企微端的「联系我」二维码</li>
     *   <li>删除所有活码联系人（{@link QrAgent}）</li>
     *   <li>删除后备池记录（GlobalAgentPool）—— 全局池不按活码删除，员工保留在池中供其他活码使用</li>
     *   <li>删除轮换日志（{@link QrRotateLog}）</li>
     *   <li>删除活码主记录（{@link QrCode}）</li>
     * </ol>
     *
     * <p>只要活码有关联的 {@code config_id}，就会调用企微删除 API——即使该调用失败，
     * DB 侧的删除仍会继续（避免 DB 残留孤儿数据）。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @throws RuntimeException 活码不存在时抛出
     */
    @Transactional
    public void delete(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        String configId = qr.getQrConfigId();

        // 1. 先级联删除活码下的联系人关联
        qrAgentRepo.findByQrCodeId(qrCodeId).forEach(qa -> qrAgentRepo.delete(qa));

        // 2. 级联删除轮换日志
        rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(qrCodeId, Pageable.unpaged())
            .forEach(rl -> rotateLogRepo.delete(rl));

        // 3. 先删 DB 记录（事务内）
        qrCodeRepo.delete(qr);

        // 4. 事务提交后异步调 WeChat API 删除企微侧活码（失败由对账扫描补偿）
        if (configId != null && !configId.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            wecomApi.deleteContactWay(configId);
                        } catch (Exception ex) {
                            log.error("WeChat 侧活码删除失败（由对账扫描补偿）: configId={}", configId, ex);
                        }
                    }
                });
        }
    }

    // ==================== 同步企微活码 ====================

    /**
     * 手动将活码的联系人列表同步到企微端。
     *
     * <p>只上 {@link QrAgent.AgentStatus#active} 状态的员工，
     * 按 sortOrder 排序决定企微分配优先顺序。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @throws RuntimeException 活码不存在、未关联企微 config_id、企微 API 调用失败时抛出
     */
    public void syncQrUsersToWechat(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        if (qr.getQrConfigId() == null) {
            throw new RuntimeException("活码未关联企微 config_id");
        }

        List<QrAgent> allAgents = qrAgentRepo.findByQrCodeId(qrCodeId);
        List<String> userIds = new ArrayList<>();
        for (QrAgent a : allAgents) {
            if (a.getStatus() == QrAgent.AgentStatus.active) {
                userIds.add(a.getAgentUserid());
            }
        }

        // 委托给统一自愈服务
        WechatSyncHealingService.SyncResult result =
            healingService.syncWithHealing(qrCodeId, userIds, "qr-service");

        // 自愈移除不可用成员后，从全局池补充替补
        if (result.needReplacement) {
            healingService.supplementReplacement(qrCodeId);
        }

        if (!result.success) {
            log.error("同步企微活码失败: qrCodeId={}, reason={}", qrCodeId, result.reason);
            throw new RuntimeException("同步企微活码失败: " + result.reason);
        }
    }

    // ==================== 后备池管理（全局池版本） ====================

    /**
     * 向全局池添加新员工。
     *
     * <p>员工加入全局池后，任意活码需要扩容时均可从池中取用。
     * 如果员工已在池中则不重复添加。</p>
     *
     * @param qrCodeId    活码主键 ID（仅用于校验存在性）
     * @param agentUserid 企微员工 userid
     * @throws RuntimeException 活码不存在时抛出
     */
    @Transactional
    public void addBackup(Long qrCodeId, String agentUserid) {
        getById(qrCodeId); // 校验活码存在
        poolService.ensureInPool(agentUserid, 200);
        log.info("全局池员工已添加: userid={}", agentUserid);
    }

    // ==================== 活码联系人管理 ====================

    /**
     * 向活码添加接待员联系人。
     *
     * <p>与 {@link #addBackup(Long, String)} 不同，这里直接将接待员加入 {@link QrAgent} 表
     * （而非后备池），使接待员立即出现在活码的联系人列表中。
     * 适用于需要直接添加接待员而不经过后备池轮换的场景。</p>
     *
     * @param qrCodeId    活码主键 ID
     * @param agentUserid 企微员工 userid
     * @throws RuntimeException 活码不存在、员工已在联系人中时抛出
     */
    @Transactional
    public void addAgent(Long qrCodeId, String agentUserid) {
        getById(qrCodeId);

        // 检查是否已在联系人中（排除已移除的 status=removed）：
        // removed 状态的员工可以重新添加（相当于重新激活）
        List<QrAgent> existing = qrAgentRepo.findByQrCodeId(qrCodeId);
        boolean duplicate = existing.stream()
            .anyMatch(a -> a.getAgentUserid().equals(agentUserid)
                && a.getStatus() != QrAgent.AgentStatus.removed);
        if (duplicate) {
            throw new RuntimeException("该员工已在活码联系人中");
        }

        ensureAgent(agentUserid, "receptionist");

        int maxOrder = existing.stream()
            .mapToInt(QrAgent::getSortOrder)
            .max().orElse(-1);

        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(agentUserid)
            .role(QrAgent.AgentRole.receptionist)   // 手动添加的默认为接待员角色
            .dailyMax(dailyMaxDefault)                            // 默认日接待上限，可通过 app.agent.daily-max-default 配置
            .sortOrder(maxOrder + 1)
            .status(QrAgent.AgentStatus.active)       // 添加即启用
            .build());

        log.info("联系人已添加: qrCodeId={}, agentUserid={}", qrCodeId, agentUserid);

        // 同步企微侧联系人列表，确保企微配置与本地一致
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncQrUsersToWechat(qrCodeId);
                    } catch (Exception e) {
                        log.error("添加联系人后同步企微失败: qrCodeId={}, agentUserid={}",
                            qrCodeId, agentUserid, e);
                    }
                }
            });
    }

    /**
     * 移除活码联系人（软删除：标记为 removed 状态）。
     *
     * <p>不移除服务老师角色（{@link QrAgent.AgentRole#service}），
     * 因为服务老师是活码的核心配置，需要通过更换流程来替换。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @param agentId  联系人记录 ID（QrAgent 主键）
     * @throws RuntimeException 联系人不存在、不属于该活码、或尝试移除服务老师时抛出
     */
    @Transactional
    public void removeAgent(Long qrCodeId, Long agentId) {
        QrAgent agent = qrAgentRepo.findById(agentId)
            .orElseThrow(() -> new RuntimeException("联系人不存在"));
        if (!agent.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("联系人不属于该活码");
        }
        // 服务老师不允许直接移除：必须先指定新的服务老师，通过更换流程替换
        // 这是为了防止活码失去服务老师导致客户无人接待
        if (agent.getRole() == QrAgent.AgentRole.service) {
            throw new RuntimeException("服务老师不能移除，请先更换服务老师");
        }
        // 软删除：标记为 removed 而非物理删除，保留历史记录
        agent.setStatus(QrAgent.AgentStatus.removed);
        qrAgentRepo.save(agent);
        log.info("联系人已移除: qrCodeId={}, agentUserid={}", qrCodeId, agent.getAgentUserid());

        // 同步企微侧联系人列表，确保企微配置与本地一致
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncQrUsersToWechat(qrCodeId);
                    } catch (Exception e) {
                        log.error("移除联系人后同步企微失败: qrCodeId={}, agentUserid={}",
                            qrCodeId, agent.getAgentUserid(), e);
                    }
                }
            });
    }

    /**
     * 更新活码联系人的配置（日接上限、角色、排序号）。
     *
     * <p>参数为 {@code null} 的字段表示不更新，保持原值。
     * 角色字段如果传入无效值，会被静默忽略（{@code IllegalArgumentException} 被捕获）。</p>
     *
     * @param qrCodeId  活码主键 ID
     * @param agentId   联系人记录 ID
     * @param dailyMax  日接上限（人/天），为 {@code null} 或 ≤0 时不更新
     * @param role      角色（"service" 或 "receptionist"），为 {@code null} 或无效值时不变
     * @param sortOrder 排序号，为 {@code null} 时不更新
     * @throws RuntimeException 联系人不存在或不属于该活码时抛出
     */
    @Transactional
    public void updateAgent(Long qrCodeId, Long agentId,
                            Integer dailyMax, String role, Integer sortOrder) {
        QrAgent agent = qrAgentRepo.findById(agentId)
            .orElseThrow(() -> new RuntimeException("联系人不存在"));
        if (!agent.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("联系人不属于该活码");
        }
        // 逐字段判断 null，实现部分更新
        if (dailyMax != null && dailyMax > 0) agent.setDailyMax(dailyMax);
        if (role != null) {
            try {
                agent.setRole(QrAgent.AgentRole.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                // 无效角色值被静默忽略，避免前端误传导致 500
            }
        }
        if (sortOrder != null) agent.setSortOrder(sortOrder);
        qrAgentRepo.save(agent);
        log.info("联系人已更新: qrCodeId={}, agentId={}", qrCodeId, agentId);
    }

    // ==================== 后备池管理（续） ====================

    /**
     * 从全局池中移除员工（物理删除）。
     *
     * @param qrCodeId 活码主键 ID（仅用于权限校验）
     * @param backupId 全局池记录 ID（GlobalAgentPool 主键）
     * @throws RuntimeException 记录不存在时抛出
     */
    @Transactional
    public void removeBackup(Long qrCodeId, Long backupId) {
        // backupId 在全局池语境下是 GlobalAgentPool 的 ID
        poolRepo.findById(backupId).ifPresent(p -> {
            log.info("全局池员工已移除: userid={}", p.getAgentUserid());
            poolRepo.delete(p);
        });
    }

    /**
     * 调整全局池中员工的排序优先级。
     *
     * <p>通过交换两个相邻记录的 {@code sortOrder} 值来实现移动。
     * 仅在 standby 状态的员工之间调整顺序。</p>
     *
     * @param qrCodeId  活码主键 ID（全局池调整不依赖此参数，保留兼容）
     * @param backupId  全局池记录 ID
     * @param direction 移动方向："up" / "down"
     */
    @Transactional
    public void moveBackup(Long qrCodeId, Long backupId, String direction) {
        GlobalAgentPool target = poolRepo.findById(backupId).orElse(null);
        if (target == null) return;

        List<GlobalAgentPool> all = poolRepo
            .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(backupId)) { idx = i; break; }
        }
        if (idx < 0) return;

        if ("up".equals(direction) && idx > 0) {
            GlobalAgentPool other = all.get(idx - 1);
            int tmp = target.getSortOrder();
            target.setSortOrder(other.getSortOrder());
            other.setSortOrder(tmp);
            poolRepo.save(target);
            poolRepo.save(other);
        } else if ("down".equals(direction) && idx < all.size() - 1) {
            GlobalAgentPool other = all.get(idx + 1);
            int tmp = target.getSortOrder();
            target.setSortOrder(other.getSortOrder());
            other.setSortOrder(tmp);
            poolRepo.save(target);
            poolRepo.save(other);
        }
    }

    // ==================== 状态更新 ====================

    /**
     * 更新活码启用/停用状态。
     *
     * <p>将活码设为 inactive 后，客户扫码将不再分配员工（企微侧该活码等效于停用）。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @param status   目标状态（{@link QrCode.QrCodeStatus#active} 或 {@link QrCode.QrCodeStatus#inactive}）
     * @throws RuntimeException 活码不存在时抛出
     */
    @Transactional
    public void updateStatus(Long qrCodeId, QrCode.QrCodeStatus status) {
        QrCode qr = getById(qrCodeId);
        qr.setStatus(status);
        qrCodeRepo.save(qr);
    }

    /**
     * 更新活码轮换模式。
     *
     * <p>两种模式：
     * <ul>
     *   <li>{@link QrCode.RotateMode#auto} — 自动轮换：系统根据服务老师日接量自动上下线接待员</li>
     *   <li>{@link QrCode.RotateMode#manual} — 手动轮换：由管理员手动决定谁上线</li>
     * </ul>
     *
     * @param qrCodeId 活码主键 ID
     * @param mode     目标轮换模式
     * @throws RuntimeException 活码不存在时抛出
     */
    @Transactional
    public void updateRotateMode(Long qrCodeId, QrCode.RotateMode mode) {
        QrCode qr = getById(qrCodeId);
        qr.setRotateMode(mode);
        qrCodeRepo.save(qr);
    }

    /**
     * 更新活码的接待量阈值配置。
     *
     * <p>阈值用于轮换引擎判断何时触发接待员上下线：
     * <ul>
     *   <li><b>警告阈值（warnRatio）</b>：服务老师当前接待量 / 日接上限 ≥ 此百分比时，
     *       发出预警，准备从后备池上线接待员</li>
     *   <li><b>紧急阈值（urgentRatio）</b>：达到此百分比时，触发紧急轮换</li>
     * </ul>
     * 二者必须在 1-100 之间，且 warnRatio 必须小于 urgentRatio。</p>
     *
     * @param qrCodeId   活码主键 ID
     * @param warnRatio  警告阈值（百分比，如 70 表示 70%）
     * @param urgentRatio 紧急阈值（百分比，如 90 表示 90%）
     * @throws RuntimeException 活码不存在、阈值范围非法、或 warnRatio >= urgentRatio 时抛出
     */
    @Transactional
    public void updateThresholds(Long qrCodeId, int warnRatio, int urgentRatio) {
        // 校验阈值范围：百分比必须在 1-100 之间
        if (warnRatio < 1 || warnRatio > 100 || urgentRatio < 1 || urgentRatio > 100) {
            throw new RuntimeException("阈值必须在 1-100 之间");
        }
        // 警告阈值必须小于紧急阈值，确保两级预警的阶梯逻辑有效
        if (warnRatio >= urgentRatio) {
            throw new RuntimeException("预警阈值必须小于紧急阈值");
        }
        QrCode qr = getById(qrCodeId);
        qr.setWarnRatio(warnRatio);
        qr.setUrgentRatio(urgentRatio);
        qrCodeRepo.save(qr);
    }

    /**
     * 批量切换活码的轮换模式。
     *
     * <p>遍历 ID 列表逐个更新，单条失败不中断整体操作（仅记录 warn 日志）。
     * 返回成功更新的数量。</p>
     *
     * @param ids  活码主键 ID 列表
     * @param mode 目标轮换模式
     * @return 成功更新的活码数量
     */
    @Transactional
    public int batchUpdateRotateMode(List<Long> ids, QrCode.RotateMode mode) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateRotateMode(mode, ids);
    }

    /**
     * 批量更新活码欢迎语文本。
     */
    @Transactional
    public int batchUpdateWelcomeText(List<Long> ids, String welcomeText) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateWelcomeText(welcomeText, ids);
    }

    /**
     * 批量更新活码表单模板 ID（传 null 清空）。
     */
    @Transactional
    public int batchUpdateFormTemplateId(List<Long> ids, Long formTemplateId) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateFormTemplateId(formTemplateId, ids);
    }

    /**
     * 批量更新活码分组 ID（传 null 取消分组）。
     */
    @Transactional
    public int batchUpdateGroupId(List<Long> ids, Long groupId) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateGroupId(groupId, ids);
    }

    /**
     * 批量更新活码告警/紧急阈值。
     */
    @Transactional
    public int batchUpdateThresholds(List<Long> ids, int warnRatio, int urgentRatio) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateThresholds(warnRatio, urgentRatio, ids);
    }

    /**
     * 批量更新活码状态（暂停/启用）。
     */
    @Transactional
    public int batchUpdateStatus(List<Long> ids, QrCode.QrCodeStatus status) {
        if (ids == null || ids.isEmpty()) return 0;
        return qrCodeRepo.batchUpdateStatus(status, ids);
    }

    /**
     * 更新活码的样式配置（主题色、引导文案、是否显示校名、Logo 路径）。
     *
     * <p>样式配置以 JSON 字符串形式存储在 {@link QrCode#styleConfig} 字段中。
     * 传入参数为 {@code null} 的字段使用默认值：
     * <ul>
     *   <li>theme 默认 "blue"</li>
     *   <li>showSchoolName 默认 true</li>
     * </ul>
     *
     * @param qrCodeId      活码主键 ID
     * @param theme         主题色名称，为 {@code null} 时默认 "blue"
     * @param guideText     扫码引导文案，为 {@code null} 时不设置
     * @param showSchoolName 是否显示学校名称，为 {@code null} 时默认 true
     * @param logoPath      自定义 Logo 图片路径，为 {@code null} 时不设置
     * @throws RuntimeException 活码不存在或 JSON 序列化失败时抛出
     */
    @Transactional
    public void updateStyle(Long qrCodeId, String theme, String guideText,
                            Boolean showSchoolName, String logoPath) {
        QrCode qr = getById(qrCodeId);
        try {
            // 构造样式 JSON 对象，null 字段使用默认值
            Map<String, Object> style = new LinkedHashMap<>();
            if (logoPath != null) style.put("logo_path", logoPath);
            style.put("theme", theme != null ? theme : "blue");
            if (guideText != null) style.put("guide_text", guideText);
            style.put("show_school_name", showSchoolName != null ? showSchoolName : true);
            // 序列化为 JSON 字符串存入 DB
            qr.setStyleConfig(objectMapper.writeValueAsString(style));
            qrCodeRepo.save(qr);
        } catch (Exception e) {
            throw new RuntimeException("保存样式配置失败", e);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 构造企微「创建联系我」API 的请求体 JSON。
     *
     * <h3>参数来源优先级（从高到低）</h3>
     * <ol>
     *   <li>服务老师 JSON 数组（{@code serviceTeacherJson}）— 支持每人独立日限</li>
     *   <li>服务老师逗号分隔字符串（{@code serviceTeacherUserid}）— 兼容旧格式</li>
     *   <li>接待员 JSON 数组（{@code agentsJson}）— 当无服务老师时回退</li>
     *   <li>接待员逗号分隔字符串（{@code receptionistUserid}）— 旧格式回退</li>
     * </ol>
     *
     * <p>企微 API 参数说明：
     * <ul>
     *   <li>{@code type=2} — 多人模式（多个员工共享一个二维码）</li>
     *   <li>{@code scene=2} — 二维码场景</li>
     *   <li>{@code state} — 企业自定义参数，此处填入 schoolId 用于回调识别</li>
     * </ul>
     *
     * @param req 创建请求 DTO
     * @return 序列化后的 JSON 字符串
     * @throws RuntimeException 未提供任何员工账号、或 JSON 构造失败时抛出
     */
    private String buildContactWayJson(QrCodeCreateRequest req) {
        try {
            List<String> userIds = new ArrayList<>();

            // ① 服务老师（JSON 数组优先，回退逗号分隔）
            // JSON 格式优先：支持每人独立的 dailyMax 配置
            if (req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isBlank()) {
                JsonNode arr = objectMapper.readTree(req.getServiceTeacherJson());
                for (JsonNode svc : arr) {
                    if (svc.has("userid")) userIds.add(svc.get("userid").asText());
                }
            } else if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                // 逗号分隔字符串：兼容旧版前端（不支持每人独立日限）
                for (String uid : req.getServiceTeacherUserid().split(",")) {
                    String trimmed = uid.trim();
                    if (!trimmed.isEmpty()) userIds.add(trimmed);
                }
            }

            // ② 如果没有服务老师，退回到接待员：
            // 企微联系我二维码至少要有一个 user，否则无法创建
            if (userIds.isEmpty()) {
                if (req.getAgentsJson() != null && !req.getAgentsJson().isBlank()) {
                    JsonNode arr = objectMapper.readTree(req.getAgentsJson());
                    for (JsonNode a : arr) {
                        if (a.has("userid")) userIds.add(a.get("userid").asText());
                    }
                } else if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                    for (String uid : req.getReceptionistUserid().split(",")) {
                        String trimmed = uid.trim();
                        if (!trimmed.isEmpty()) userIds.add(trimmed);
                    }
                }
            }

            if (userIds.isEmpty()) {
                throw new RuntimeException("请填写服务老师或接待员企微账号");
            }

            // 构造企微 API 请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", 2);    // 多人模式
            body.put("scene", 2);   // 二维码场景
            body.put("style", 1);   // 样式（1=默认）
            body.put("state", req.getSchoolId()); // 回调识别参数：通过 schoolId 区分不同活码
            body.put("user", userIds);
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw e;  // 业务异常直接透传，不包装
        } catch (Exception e) {
            throw new RuntimeException("构造活码参数失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造活码的欢迎语/接待配置 JSON。
     *
     * <p>欢迎语配置包含以下要素：
     * <ul>
     *   <li><b>text</b> — 默认欢迎语文本</li>
     *   <li><b>collect_form</b> — 收集表单配置（年级、班级、孩子姓名），
     *       支持自定义或使用系统默认表单</li>
     *   <li><b>form_callback_tag</b> — 表单回传标签，客户填表后自动打上对应标签</li>
     *   <li><b>transfer_greeting</b> — 转接问候语模板，支持变量替换
     *       （如 {{school_name}}、{{teacher_name}}、{{parent_name}}）</li>
     * </ul>
     *
     * <p>收集表单默认包含三个字段：年级（下拉选择，含小学+初中+高中共12个年级）、
     * 班级（下拉选择，1-20班）、孩子姓名（文本输入，非必填）。</p>
     *
     * @param req 创建请求 DTO
     * @return 序列化后的 JSON 字符串，异常时返回空 JSON "{}"（降级处理，不影响活码创建）
     */
    private String buildWelcomeConfig(QrCodeCreateRequest req) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            // 欢迎语文本：优先使用自定义文案，否则使用默认文案
            config.put("text", req.getWelcomeText() != null ? req.getWelcomeText()
                : "欢迎来到XX书店家校服务！");
            // 收集表单：优先使用自定义表单 JSON，否则使用系统默认表单
            if (req.getCollectFormJson() != null && !req.getCollectFormJson().isEmpty()) {
                config.put("collect_form", objectMapper.readTree(req.getCollectFormJson()));
            } else {
                // 默认收集表单：年级（12个学段可选）+ 班级（1-20班）+ 孩子姓名（非必填）
                config.put("collect_form", List.of(
                    Map.of("name", "grade", "label", "孩子年级", "type", "select",
                        "options", List.of("一年级","二年级","三年级","四年级","五年级","六年级",
                            "初一","初二","初三","高一","高二","高三")),
                    Map.of("name", "class", "label", "孩子班级", "type", "select",
                        "options", List.of("1班","2班","3班","4班","5班","6班","7班","8班","9班","10班",
                            "11班","12班","13班","14班","15班","16班","17班","18班","19班","20班")),
                    Map.of("name", "child_name", "label", "孩子姓名", "type", "text", "required", false)
                ));
            }
            // 表单回传：客户填写收集表单后，自动将表单信息以标签形式回传给接待员工
            config.put("form_callback_tag", true);
            // 转接问候：启用后客户添加员工时会自动发送个性化问候语
            config.put("transfer_greeting_enabled", true);
            // 转接附注：客户信息摘要，使用 {{}} 模板变量在服务端替换
            config.put("transfer_filled_note",
                "{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}");
            // 已填表客户的问候语模板
            config.put("transfer_filled_greeting",
                "{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚");
            // 未填表客户的问候语模板（引导客户先填写信息）
            config.put("transfer_unfilled_greeting",
                "{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}");
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            // 欢迎语配置构造失败不阻断活码创建，返回空 JSON
            return "{}";
        }
    }

    /**
     * 将请求中的员工信息绑定到活码 — 支持多员工初始上码。
     *
     * <h3>绑定策略</h3>
     * <ul>
     *   <li>优先使用 {@code initialAgentUserids}（逗号分隔的 userid 列表）</li>
     *   <li>若无，则兼容旧格式 serviceTeacherUserid + receptionistUserid</li>
     *   <li>仍不足 initialAgentCount 时，从全局池自动取 standby 员工补齐</li>
     *   <li>在职继承目标员工同步确保在全局池中</li>
     *   <li>所有员工角色统一为 receptionist</li>
     * </ul>
     *
     * @param qrCodeId 活码主键 ID
     * @param req      创建请求 DTO
     * @throws RuntimeException 员工绑定过程中任何异常均抛出（事务回滚）
     */
    private void bindAgents(Long qrCodeId, QrCodeCreateRequest req) {
        try {
            int sortOrder = 0;

            // ① 确保在职继承目标在全局池中
            if (req.getTransferTargetUserid() != null && !req.getTransferTargetUserid().isBlank()) {
                poolService.ensureInPool(req.getTransferTargetUserid().trim(), 100);
            }

            // ② 初始上码员工：优先使用 initialAgentUserids 列表
            //    其次从 serviceTeacherUserid / receptionistUserid 兼容旧格式
            //    最后从全局池自动取人
            List<String> initialUserids = new ArrayList<>();
            if (req.getInitialAgentUserids() != null && !req.getInitialAgentUserids().isBlank()) {
                for (String uid : req.getInitialAgentUserids().split(",")) {
                    String trimmed = uid.trim();
                    if (!trimmed.isEmpty()) initialUserids.add(trimmed);
                }
            } else {
                // 兼容旧格式：服务老师 + 接待员都作为初始上码员工
                if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                    for (String uid : req.getServiceTeacherUserid().split(",")) {
                        String t = uid.trim();
                        if (!t.isEmpty()) initialUserids.add(t);
                    }
                }
                if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                    for (String uid : req.getReceptionistUserid().split(",")) {
                        String t = uid.trim();
                        if (!t.isEmpty() && !initialUserids.contains(t)) initialUserids.add(t);
                    }
                }
            }

            int needCount = req.getInitialAgentCount() != null
                ? req.getInitialAgentCount() : 1;

            // 确保指定员工在全局池中，并写入 QrAgent
            int defaultDailyMax = req.getServiceDailyMax() != null
                ? req.getServiceDailyMax() : 30;
            // 手动指定的初始员工作为「服务老师」，其余从全局池补的作为「接待员」
            for (String uid : initialUserids) {
                poolService.ensureInPool(uid, defaultDailyMax);
                qrAgentRepo.save(QrAgent.builder()
                    .qrCodeId(qrCodeId).agentUserid(uid)
                    .role(QrAgent.AgentRole.service)
                    .dailyMax(defaultDailyMax)
                    .sortOrder(sortOrder++)
                    .status(QrAgent.AgentStatus.active).build());
                needCount--;
            }

            // 不够数，从全局池自动补（排除已绑定员工，避免重复）
            Set<String> boundUserids = new HashSet<>(initialUserids);
            while (needCount > 0) {
                GlobalAgentPool next = poolService.takeStandby(boundUserids);
                if (next == null) break;
                boundUserids.add(next.getAgentUserid());
                qrAgentRepo.save(QrAgent.builder()
                    .qrCodeId(qrCodeId).agentUserid(next.getAgentUserid())
                    .role(QrAgent.AgentRole.receptionist)
                    .dailyMax(next.getDailyMax())
                    .sortOrder(sortOrder++)
                    .status(QrAgent.AgentStatus.active).build());
                needCount--;
            }

            // 全局池不足，无法满足 initialAgentCount 要求时告警
            if (needCount > 0) {
                log.warn("活码 {} 创建时全局池不足：需要 {} 人，实际绑定 {} 人（缺 {} 人）",
                    qrCodeId,
                    req.getInitialAgentCount() != null ? req.getInitialAgentCount() : 1,
                    sortOrder, needCount);
                alertService.alertEmptyBackup(qrCodeId,
                    String.format("活码 %s 创建时全局池不足：需 %d 人，实绑 %d 人",
                        req.getSchoolName(),
                        req.getInitialAgentCount() != null ? req.getInitialAgentCount() : 1,
                        sortOrder));
            }

        } catch (Exception e) {
            log.error("绑定员工失败", e);
            throw new RuntimeException("绑定员工失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保指定 userid 的员工存在于 {@link Agent} 全局表中，不存在则自动创建。
     *
     * <p>这是员工账户的「懒初始化」机制：
     * 当用户将某个企微 userid 添加为活码服务老师或接待员时，
     * 系统自动检查 Agent 表，若不存在则尝试从企微 API 获取真实姓名后创建。
     * 如果企微 API 调用失败，使用 userid 作为 name 的降级值。</p>
     *
     * @param userid 企微员工 userid
     * @param role   员工角色（"service" 或 "receptionist"），用于初始化 {@link Agent.AgentRole}
     */
    private void ensureAgent(String userid, String role) {
        if (!agentRepo.existsById(userid)) {
            // 尝试从企微 API 获取真实姓名，失败时用 userid 作为降级方案
            String name = userid; // fallback：企微 userid 通常比空字符串更有辨识度
            try {
                JsonNode result = wecomApi.getUserSimplelist();
                // parseAndCheck 保证 errcode=0
                for (JsonNode u : result.get("userlist")) {
                    if (userid.equals(u.get("userid").asText())) {
                        name = u.get("name").asText();
                        break;
                    }
                }
            } catch (Exception e) {
                // 获取员工姓名失败不阻断流程，使用 userid 作为 name 降级
                log.warn("获取员工姓名失败, 使用 userid 作为 name: userid={}", userid);
            }

            // 创建 Agent 记录，默认日总接待上限 500（适用于未单独配置的员工）
            agentRepo.save(Agent.builder()
                .userid(userid)
                .name(name)
                .role(Agent.AgentRole.valueOf(role))
                .dailyTotalCap(500)
                .build());
        }
    }

    /**
     * 解析上传的 Excel 文件，提取活码导入数据。
     *
     * <h3>Excel 列映射（第 0 行为表头，从第 1 行开始读取）</h3>
     * <table>
     *   <tr><th>列号</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>A (0)</td><td>schoolName</td><td>学校名称</td></tr>
     *   <tr><td>B (1)</td><td>schoolId</td><td>学校 ID（唯一标识）</td></tr>
     *   <tr><td>C (2)</td><td>regionCity</td><td>所在城市</td></tr>
     *   <tr><td>D (3)</td><td>regionDistrict</td><td>所在区县</td></tr>
     *   <tr><td>E (4)</td><td>serviceTeacherUserid</td><td>服务老师企微 userid</td></tr>
     *   <tr><td>F (5)</td><td>studentCount</td><td>学校人数</td></tr>
     *   <tr><td>G (6)</td><td>initialAgentCount</td><td>初始上码员工数</td></tr>
     *   <tr><td>H (7)</td><td>receptionistUserid</td><td>接待员 userid</td></tr>
     *   <tr><td>I (8)</td><td>serviceDailyMax</td><td>服务老师日上限</td></tr>
     *   <tr><td>J (9)</td><td>welcomeText</td><td>欢迎语</td></tr>
     *   <tr><td>K (10)</td><td>remark</td><td>备注</td></tr>
     * </table>
     *
     * <p>仅当 schoolName 和 schoolId 均非空时才将该行加入结果列表，
     * 跳过空行和不完整的行。</p>
     *
     * @param file 上传的 Excel 文件（.xlsx 格式）
     * @return 行数据列表，每行为一个 Map，包含 row（Excel 行号）、schoolName、schoolId 等字段
     * @throws RuntimeException Excel 解析失败时抛出
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseExcel(MultipartFile file) {
        List<Map<String, String>> items = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, String> item = new LinkedHashMap<>();
                item.put("row", String.valueOf(i + 1));
                // 列索引: 0学校名称 1学校ID 2市 3区 4服务老师 5学校人数
                //          6初始上码员工数 7接待员 8服务老师日上限 9欢迎语 10备注
                //          11场景 12部门ID
                item.put("schoolName", getCellString(row, 0));
                item.put("schoolId", getCellString(row, 1));
                item.put("regionCity", getCellString(row, 2));
                item.put("regionDistrict", getCellString(row, 3));
                item.put("serviceTeacherUserid", getCellString(row, 4));
                item.put("studentCount", getCellString(row, 5));
                item.put("initialAgentCount", getCellString(row, 6));
                item.put("receptionistUserid", getCellString(row, 7));
                item.put("serviceDailyMax", getCellString(row, 8));
                item.put("welcomeText", getCellString(row, 9));
                item.put("remark", getCellString(row, 10));
                item.put("scene", getCellString(row, 11));
                item.put("departmentId", getCellString(row, 12));
                // 学校名称和学校ID必填
                if (!item.get("schoolName").isEmpty() && !item.get("schoolId").isEmpty()) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 Excel 失败: " + e.getMessage(), e);
        }
        return items;
    }

    /**
     * 安全读取 Excel 单元格的字符串值。
     *
     * <p>处理 null 单元格，并强制将单元格类型设为 STRING 以避免数字单元格
     * （如学校 ID "1001"）被 POI 解析为数值类型导致精度丢失或格式异常。</p>
     *
     * @param row Excel 行对象
     * @param col 列索引（从 0 开始）
     * @return 单元格字符串值（trim 后），单元格为 null 时返回空字符串 ""
     */
    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        // 强制设为 STRING 类型：防止纯数字的学校 ID 被当作数值解析
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue().trim();
    }

    /**
     * 替换活码下所有异常员工。
     *
     * <p>逐个判定活码下的 active 接待员是否异常，异常则移除并从全局池取替补。
     * 替补必须通过 {@link #getAnomalyLabel} 二次校验，防止回灌异常员工。
     * 事务提交后异步同步企微。</p>
     *
     * @param qrCodeId 活码主键 ID
     * @return 替换结果 Map：qrCodeId / schoolName / removed / replaced / shortfall / details
     */
    @Transactional
    public Map<String, Object> replaceAnomalyAgents(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);

        // 1. 获取活码下全部 active 接待员
        List<QrAgent> activeAgents = qrAgentRepo
            .findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active);

        if (activeAgents.isEmpty()) {
            return Map.of("qrCodeId", qrCodeId, "schoolName", qr.getSchoolName(),
                "removed", 0, "replaced", 0, "shortfall", 0, "details", List.of());
        }

        // 2. 批量加载 Employee + Agent 快照
        Set<String> userids = activeAgents.stream()
            .map(QrAgent::getAgentUserid).collect(Collectors.toSet());

        Map<String, Employee> empMap = employeeRepo.findByUseridIn(userids).stream()
            .collect(Collectors.toMap(Employee::getUserid, e -> e, (a, b) -> a));
        Map<String, Agent> agentMap = agentRepo.findAllById(userids).stream()
            .collect(Collectors.toMap(Agent::getUserid, a -> a, (a, b) -> a));

        // 3. 找出异常员工
        List<QrAgent> anomalous = new ArrayList<>();
        for (QrAgent qa : activeAgents) {
            Agent agent = agentMap.get(qa.getAgentUserid());
            Employee emp = empMap.get(qa.getAgentUserid());
            if (getAnomalyLabel(agent, emp) != null) {
                anomalous.add(qa);
            }
        }

        if (anomalous.isEmpty()) {
            return Map.of("qrCodeId", qrCodeId, "schoolName", qr.getSchoolName(),
                "removed", 0, "replaced", 0, "shortfall", 0, "details", List.of());
        }

        // 3.5 安全阈值：全局池无 standby 时跳过移除，防止活码变空码
        long poolStandby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
        if (poolStandby == 0) {
            log.warn("活码 {} 有 {} 个异常员工但全局池无 standby，跳过移除以防活码变空",
                qrCodeId, anomalous.size());
            alertService.createAlert("system", "anomaly_replace_blocked",
                AgentAlert.AlertSeverity.high,
                String.format("活码「%s」(id=%d) 有 %d 个异常员工但全局池无 standby，跳过移除以防活码变空",
                    qr.getSchoolName(), qrCodeId, anomalous.size()),
                AgentAlert.AutoAction.none, qrCodeId);
            List<Map<String, String>> blockedDetails = anomalous.stream()
                .map(qa -> Map.of("userid", qa.getAgentUserid(),
                    "anomaly", getAnomalyLabel(
                        agentMap.get(qa.getAgentUserid()), empMap.get(qa.getAgentUserid())),
                    "skipped", "全局池无 standby"))
                .collect(Collectors.toList());
            Map<String, Object> blockedResult = new LinkedHashMap<>();
            blockedResult.put("qrCodeId", qrCodeId);
            blockedResult.put("schoolName", qr.getSchoolName());
            blockedResult.put("removed", 0);
            blockedResult.put("replaced", 0);
            blockedResult.put("shortfall", anomalous.size());
            blockedResult.put("details", blockedDetails);
            blockedResult.put("skipped", true);
            blockedResult.put("reason", "全局池无 standby，跳过移除以防活码变空");
            return blockedResult;
        }

        // 4. 构建排除集合（已在活码上的非 removed 员工）
        Set<String> excludeUserids = activeAgents.stream()
            .map(QrAgent::getAgentUserid).collect(Collectors.toSet());

        // 5. 移除异常 + 补人（替补二次校验）
        List<Map<String, String>> details = new ArrayList<>();
        int replaced = 0;
        int maxSortOrder = activeAgents.stream()
            .mapToInt(QrAgent::getSortOrder).max().orElse(0);

        for (QrAgent qa : anomalous) {
            String label = getAnomalyLabel(
                agentMap.get(qa.getAgentUserid()), empMap.get(qa.getAgentUserid()));

            // 移除
            qa.setStatus(QrAgent.AgentStatus.removed);
            qa.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(qa);

            // 从全局池找替补，最多尝试 20 次
            String replacementUserid = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                GlobalAgentPool next = poolService.takeStandby(excludeUserids);
                if (next == null) break;

                // 二次校验：替补也必须是正常状态
                Agent repAgent = agentRepo.findById(next.getAgentUserid()).orElse(null);
                Employee repEmp = employeeRepo.findByUserid(next.getAgentUserid()).orElse(null);
                String repLabel = getAnomalyLabel(repAgent, repEmp);

                if (repLabel == null) {
                    // 合格
                    replacementUserid = next.getAgentUserid();
                    break;
                }
                // 不合格 — 加入排除列表，继续取下一个
                excludeUserids.add(next.getAgentUserid());
                log.warn("替换补人跳过异常员工: qrCodeId={}, userid={}, anomaly={}",
                    qrCodeId, next.getAgentUserid(), repLabel);
            }

            if (replacementUserid != null) {
                excludeUserids.add(replacementUserid);
                maxSortOrder++;
                qrAgentRepo.save(QrAgent.builder()
                    .qrCodeId(qrCodeId).agentUserid(replacementUserid)
                    .role(qa.getRole())
                    .dailyMax(dailyMaxDefault)
                    .sortOrder(maxSortOrder)
                    .status(QrAgent.AgentStatus.active).build());
                replaced++;
                details.add(Map.of("removed", qa.getAgentUserid(),
                    "anomaly", label, "replacedBy", replacementUserid));
            } else {
                details.add(Map.of("removed", qa.getAgentUserid(),
                    "anomaly", label, "replacedBy", ""));
            }
        }

        // 6. 异步同步企微
        final Long fQrId = qrCodeId;
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncQrUsersToWechat(fQrId);
                    } catch (Exception e) {
                        log.error("替换异常员工后同步企微失败: qrCodeId={}", fQrId, e);
                    }
                }
            });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qrCodeId", qrCodeId);
        result.put("schoolName", qr.getSchoolName());
        result.put("removed", anomalous.size());
        result.put("replaced", replaced);
        result.put("shortfall", anomalous.size() - replaced);
        result.put("details", details);

        int shortfall = anomalous.size() - replaced;
        if (shortfall > 0) {
            alertService.createAlert("system", "anomaly_replace_shortfall",
                AgentAlert.AlertSeverity.medium,
                String.format("活码「%s」(id=%d) 替换异常员工：移除 %d 人，仅补入 %d 人，缺口 %d 人",
                    qr.getSchoolName(), qrCodeId, anomalous.size(), replaced, shortfall),
                AgentAlert.AutoAction.none, qrCodeId);
        }

        log.info("活码 {} 异常员工替换完成: 移除 {} 人, 补入 {} 人, 缺口 {} 人",
            qrCodeId, anomalous.size(), replaced, anomalous.size() - replaced);

        return result;
    }

    /**
     * 批量回收闲置接待员回全局池。
     *
     * <h3>校验规则</h3>
     * <ol>
     *   <li>服务老师（role=service）不可回收</li>
     *   <li>回收后活码上至少保留 1 个 active 接待员</li>
     * </ol>
     *
     * @param qrCodeId 活码 ID
     * @param agentIds 要回收的 QrAgent ID 列表
     * @return 回收结果，包含成功数/拒绝数及拒绝原因
     */
    @Transactional
    public Map<String, Object> batchRecycleAgents(Long qrCodeId, List<Long> agentIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> rejectReasons = new LinkedHashMap<>();
        int recycled = 0;

        // 统计当前 active 接待员总数
        List<QrAgent> activeAgents = qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() == QrAgent.AgentStatus.active)
            .toList();
        long activeReceptionistCount = activeAgents.stream()
            .filter(a -> a.getRole() != QrAgent.AgentRole.service
                       && a.getRole() != QrAgent.AgentRole.dual)
            .count();

        for (Long agentId : agentIds) {
            QrAgent agent = qrAgentRepo.findById(agentId).orElse(null);
            if (agent == null || !agent.getQrCodeId().equals(qrCodeId)) {
                rejectReasons.put(String.valueOf(agentId), "不存在或不属于此活码");
                continue;
            }
            // 服务老师不可回收
            if (agent.getRole() == QrAgent.AgentRole.service) {
                rejectReasons.put(String.valueOf(agentId), "服务老师不可回收");
                continue;
            }
            // 至少保留 1 个 active 接待员
            if (agent.getRole() == QrAgent.AgentRole.receptionist
                && activeReceptionistCount - recycled <= 1) {
                rejectReasons.put(String.valueOf(agentId), "至少保留1个接待员");
                continue;
            }

            agent.setStatus(QrAgent.AgentStatus.removed);
            agent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(agent);

            // 恢复全局池状态
            GlobalAgentPool pool = poolRepo.findByAgentUserid(agent.getAgentUserid()).orElse(null);
            if (pool != null && pool.getStatus() != GlobalAgentPool.PoolStatus.standby) {
                pool.setStatus(GlobalAgentPool.PoolStatus.standby);
                poolRepo.save(pool);
            }
            recycled++;
        }

        result.put("recycled", recycled);
        result.put("rejected", agentIds.size() - recycled);
        result.put("rejectReasons", rejectReasons);

        // 异步同步企微
        if (recycled > 0) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        syncQrUsersToWechat(qrCodeId);
                    }
                });
        }

        log.info("批量回收完成: qrCodeId={}, recycled={}, rejected={}",
            qrCodeId, recycled, agentIds.size() - recycled);
        return result;
    }

    /**
     * 判定员工的异常状态标签（统一工具方法，供全系统复用）。
     *
     * <p>判定优先级：Agent blocked → Agent melted → Agent warning
     * → Employee wechatStatus 异常。blocked 状态会解析 statusReason 中的 errcode
     * 来区分具体原因。正常员工返回 {@code null}。</p>
     *
     * @param agent Agent 记录（可为 {@code null}）
     * @param emp   Employee 记录（可为 {@code null}）
     * @return 异常标签，正常返回 {@code null}
     */
    public static String getAnomalyLabel(Agent agent, Employee emp) {
        if (agent != null) {
            if (agent.getOverallStatus() == Agent.OverallStatus.blocked) {
                String reason = agent.getStatusReason();
                if (reason != null && reason.contains("40098")) return "未实名";
                if (reason != null && reason.contains("41054")) return "未加入组织";
                return "已停用";
            }
            if (agent.getOverallStatus() == Agent.OverallStatus.melted) {
                return "已熔断";
            }
            if (agent.getOverallStatus() == Agent.OverallStatus.warning) {
                return "预警";
            }
        }
        if (emp != null && emp.getWechatStatus() != null) {
            int ws = emp.getWechatStatus();
            if (ws == 5) return "已离职";
            if (ws == 4) return "未激活";
            if (ws == 2) return "已禁用";
        }
        return null; // 正常
    }
}
