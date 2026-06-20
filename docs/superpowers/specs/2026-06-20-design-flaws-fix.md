# 活码平台设计缺陷修复方案

> 状态：设计审批中 | 日期：2026-06-20 | 基于：项目设计评审（16 项缺陷）

---

## 总览

本次修复覆盖三大模块 16 项设计缺陷，目标是在**不改变系统架构、不引入性能退化**的前提下消除数据一致性风险、清理技术债务、优化关键路径。

### 架构影响（一句话）

**三层单体 + Redis Stream 架构不变。** 改动全部在 service 层内部：拆分大文件、删除死代码、统一异常处理、配置外部化。无新增依赖。

### 性能影响（量化）

| 维度 | 影响 | 方向 |
|------|------|------|
| DB 读写 | Dashboard 15 次查询→1 次，dailyReset 500 次 UPDATE→1 次 | ✅ 改善 |
| Redis ops | 基线 ~520/s → ~570/s（+10%），单实例上限 ~100K/s | → 持平 |
| 企微 API | 对账扫描 0~5 次/5min（正常运行时 0），限额 2000/min | → 持平 |
| 请求延迟 | 1.3 学校限流 +1ms/请求（Redis EVAL），有 200ms 超时 + 本地降级 | → 可忽略 |

---

## 🔴 模块一：严重问题（4 项）

### 1.1 企微 API 事务补偿机制

**问题：** `QrCodeService.create()` 中企微 API 调用在 `@Transactional` 内——API 成功后 DB 写入失败时，企微侧产生孤儿二维码，无清理机制。

**方案：不改事务边界，加巡检兜底。**

```
现状 happy path（保持不变）：
  @Transactional
  create():
    1. 企微 createContactWay → get configId
    2. DB save QrCode
    3. bindAgents

新增补偿（PatrolWorker 每 5 分钟）：
  reconcileOrphanQrCodes():
    查询 DB: status IN ('paused','no_agent') AND qr_config_id IS NOT NULL
    FOR EACH:
      企微 GET /externalcontact/get_contact_way?config_id=xxx
      IF 企微侧存在（errcode=0）:
        企微 DELETE /externalcontact/del_contact_way?config_id=xxx
        写 OperationLog(type=reconciliation, action=delete_orphan)
        更新 DB: qr_config_id = NULL
      IF 企微侧不存在（errcode!=0）:
        不操作（正常：已手动删除或 API 调用从未成功）
```

**涉及文件：**
- `PatrolWorker.java` — 新增 `reconcileOrphanQrCodes()` 方法
- `QrCodeRepository.java` — 新增查询方法
- `WecomApiClient.java` — 已有 `deleteContactWay()`，复用
- `OperationLogRepository.java` — 复用

**企微 API 调用量分析：**

| 场景 | 异常 QR 数 | 每次巡检 API 调用 | 占限额(2000/min) |
|------|-----------|-----------------|-----------------|
| 正常运行 | 0-5 | 0-5 | 0.25% |
| 一次 DB 故障后 | 30-50 | 30-50 | 2.5% |
| 极端灾难 | 200 | 200 | 10% |

**风险：** 极低。只扫异常状态，正常运行时几乎为零调用。

---

### 1.2 CustomerService upsert 竞态 → DLQ 回退

**问题：** `CustomerService.upsertFromCallback()` 中 Redis 锁竞争 10 次重试耗尽后，`return null` 静默丢弃消息。消息已从 Redis Stream ACK，不会重试。

**方案：** 重试耗尽后将消息发布到 DLQ Stream，由 PatrolWorker 每 30 分钟重放。

```java
// CustomerService.upsertFromCallback() L140-144

// 改前：
log.error("[ALERT] ... 重试10次后仍未查到记录", externalUserId);
return null;

// 改后：
log.error("[ALERT] upsert 锁竞争超限，消息入 DLQ: external={}", externalUserId);
try {
    Map<String, String> dlqEntry = new LinkedHashMap<>();
    dlqEntry.put("source", "upsert-lock-exhaustion");
    dlqEntry.put("external_userid", externalUserId);
    dlqEntry.put("user_id", userId);
    dlqEntry.put("qr_code_id", String.valueOf(qrCodeId));
    dlqEntry.put("school_id", schoolId != null ? schoolId : "");
    dlqEntry.put("timestamp", String.valueOf(System.currentTimeMillis()));
    messageGuardService.sendToDlq(RedisConfig.CALLBACK_STREAM_KEY, dlqEntry);
} catch (Exception dlqEx) {
    log.error("[CRITICAL] DLQ 写入也失败，消息永久丢失: external={}", externalUserId);
}
return null;
```

**涉及文件：**
- `CustomerService.java` — 注入 `MessageGuardService`，修改 L140-144
- `MessageGuardService.java` — 已有 `sendToDlq()`，复用

**风险：** 零。把"丢消息"变成"延迟处理"（延迟 ≤30 分钟）。

---

### 1.3 SchoolRateLimitFilter → Redis + Caffeine 降级

**问题：** 当前 Caffeine 本地缓存做 IP 限流，多实例部署时各 Pod 独立计数——3 Pod = 实际限制 90 次/分钟/IP。

**方案：** Redis Lua 做主限流，Caffeine 本地缓存在 Redis 故障时降级兜底。

```
请求 /s/** → SchoolRateLimitFilter.doFilter()
  │
  ├─ try: Redis EVAL (Lua 原子滑动窗口)
  │      ├─ ZADD key now member
  │      ├─ ZREMRANGEBYSCORE key 0 now-window
  │      ├─ ZCARD key
  │      └─ EXPIRE key ttl
  │      return count
  │
  └─ catch (RedisException | TimeoutException):
       Caffeine 本地滑动窗口（逻辑同当前实现）
       log.warn("Redis 限流不可用，降级为本地计数: ip={}", ip)
```

**独立 RedisTemplate 配置：**

```java
// RedisConfig.java 新增
@Bean
public StringRedisTemplate rateLimitRedisTemplate(
        LettuceConnectionFactory factory) {
    // 共享连接池，但命令超时独立（200ms）
    LettuceClientConfiguration config = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofMillis(200))
        .build();
    LettuceConnectionFactory shortTimeoutFactory =
        new LettuceConnectionFactory(factory.getStandaloneConfiguration(), config);
    shortTimeoutFactory.afterPropertiesSet();
    return new StringRedisTemplate(shortTimeoutFactory);
}
```

**新增配置项（application.yml）：**

```yaml
app:
  school-rate-limit:
    max-per-minute: ${SCHOOL_RATE_LIMIT_MAX:30}
    window-seconds: 60
```

**涉及文件：**
- `SchoolRateLimitFilter.java` — 重写，注入新 RedisTemplate
- `RedisConfig.java` — 新增 `rateLimitRedisTemplate` bean
- `application.yml` — 新增配置段

**性能影响：** +1 Redis EVAL/请求。学校查询 QPS 极低（限流本身就是 30/min/IP）。独立 RedisTemplate 保证阻塞 ≤200ms。

**风险：** 低。Redis 故障时自动降级为本地缓存（fail-open，保证可用性）。降级时 WARN 日志可被监控捕获。

---

### 1.4 分布式锁 GET+DEL → Lua 原子化

**问题：** `AgentBindService`（重构后 `AgentRotationService`）中锁释放存在 TOCTOU 窗口：

```java
String current = redisTemplate.opsForValue().get(lockKey);  // 时刻 T1
if (lockValue.equals(current)) {
    redisTemplate.delete(lockKey);  // 时刻 T2，锁可能在 T1→T2 间因 TTL 过期被他人获取
}
```

**方案：** 替换为 Lua 原子脚本。

```java
// RedisConfig.java 中注册
private static final String SAFE_UNLOCK_LUA =
    "if redis.call('GET', KEYS[1]) == ARGV[1] then "
    + "return redis.call('DEL', KEYS[1]) "
    + "else return 0 end";

public static final DefaultRedisScript<Long> SAFE_UNLOCK_SCRIPT;

static {
    SAFE_UNLOCK_SCRIPT = new DefaultRedisScript<>();
    SAFE_UNLOCK_SCRIPT.setScriptText(SAFE_UNLOCK_LUA);
    SAFE_UNLOCK_SCRIPT.setResultType(Long.class);
}
```

调用方：
```java
Long result = redisTemplate.execute(
    RedisConfig.SAFE_UNLOCK_SCRIPT,
    List.of(lockKey), lockValue);
if (result != null && result == 1) {
    log.debug("锁释放成功: {}", lockKey);
} else {
    log.warn("锁释放失败（已过期或被他人持有）: {}", lockKey);
}
```

**涉及文件：**
- `RedisConfig.java` — 新增 Lua 脚本常量
- `AgentRotationService.java`（新文件）— 使用新脚本

**风险：** 零。纯正确性修复，行为等价，消除 TOCTOU 窗口。

---

## 🟡 模块二：中等问题（5 项）

> 注：2.1 合并了原"自愈逻辑去重"和"AgentBindService 拆分"两项。

### 2.1 AgentBindService 拆分 + 自愈逻辑去重

**问题：**
- `AgentBindService`（~500行）通过 `@Lazy @Autowired self` 注入自己，职责过重
- `QrCodeService.syncQrUsersToWechatWithHealing`（递归版）和 `AgentBindService.syncQrCodeToWechatWithHealing`（while版）实现相同逻辑
- `findFailingUser`（二分查找不可用成员）在两边完全重复

**方案：** 拆分为 3 个精专服务，统一自愈入口。

```
删除：
  service/AgentBindService.java

新增：
  service/AgentDailyCountService.java     — Redis 计数器 + Lua 脚本
  service/AgentRotationService.java       — 轮换/扩容逻辑
  service/WechatSyncHealingService.java   — 企微同步 + 自愈（合并两处重复）

修改：
  service/QrCodeService.java              — 自愈调用改为 WechatSyncHealingService
```

**WechatSyncHealingService 统一方法签名：**

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class WechatSyncHealingService {

    private final WecomApiClient wecomApi;
    private final QrAgentRepository qrAgentRepo;

    /**
     * @param qrCodeId       QR 码 ID
     * @param targetUserIds  目标企微 userid 列表（有序）
     * @param source         调用来源标识（"qr-create" / "agent-rotation"）
     */
    @Async
    public CompletableFuture<SyncResult> syncWithHealing(
            Long qrCodeId, List<String> targetUserIds, String source) {
        // 统一 while 循环实现（原递归和 while 两版合并）
    }

    /**
     * 二分查找定位不可用成员。
     * 原在 QrCodeService 和 AgentBindService 各一份，现在统一。
     */
    private String findFailingUser(List<String> userIds) {
        // 二分查找实现
    }
}
```

**消除自注入：** `AgentRotationService` 需要 `@Async` 的方法通过注入 `WechatSyncHealingService` 间接调用，不再注入自身。

**涉及文件：**
- 新增 3 个 service 文件
- 删除 1 个 service 文件
- 修改 `QrCodeService.java`（删除自愈方法，改为通过 `WechatSyncHealingService` 调用）

**风险：** 重构范围较大。但逻辑完全搬运，编译期可检测所有调用链断裂。

---

### 2.2 删除 QrBackupPool 遗留

**问题：** `QrBackupPool`（旧：每个二维码独立后备池）和 `GlobalAgentPool`（新：全局统一后备池）两套体系共存。`GlobalAgentPool` 已是主逻辑，`QrBackupPool` 成为死代码。

**删除清单：**

| 动作 | 文件 |
|------|------|
| 删除 | `entity/QrBackupPool.java` |
| 删除 | `repository/QrBackupPoolRepository.java` |
| 修改 | `QrCodeService.java` — 删除 `backupPool` 相关方法调用 |
| 修改 | `schema.sql` — 删除 `qr_backup_pool` 表定义 |
| 新增 | `docs/migration-drop-qr-backup-pool.sql` — 生产迁移脚本 |

**生产迁移脚本：**

```sql
-- 1. 迁移仍激活的后备代理到全局池
INSERT INTO global_agent_pool (agent_userid, daily_max, daily_current, sort_order, status, created_at, updated_at)
SELECT qbp.agent_userid, qbp.daily_max, 0,
       (SELECT COALESCE(MAX(sort_order), 0) FROM global_agent_pool) + ROW_NUMBER() OVER (ORDER BY qbp.sort_order),
       'standby', NOW(), NOW()
FROM qr_backup_pool qbp
WHERE qbp.status = 'activated'
  AND NOT EXISTS (SELECT 1 FROM global_agent_pool gap WHERE gap.agent_userid = qbp.agent_userid);

-- 2. 清理后删除表（经 DBA 审批后执行）
-- DROP TABLE IF EXISTS qr_backup_pool;
```

**风险：** 编译期全部可检测。迁移脚本独立执行，不影响发布。

---

### 2.3 TransferService 超时判断：重试次数 → 时间戳

**问题：** `TransferService.trackResults()` 用 `retryCount > 144` 判断 24 小时超时（144 × 10分钟 cron 周期），cron 周期变化即失效。

**方案：**

```java
// 新增常量
private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(24);

// 改前：
if (transfer.getRetryCount() > 144) {
    transfer.setStatus(TransferStatus.timeout);
}

// 改后：
if (transfer.getTransferTime() != null
    && transfer.getTransferTime().plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
    transfer.setStatus(TransferStatus.timeout);
}
```

同时删除 `TransferMonitorWorker.cleanupTimedOutTransfers()` 空方法。

**涉及文件：**
- `TransferService.java` — 修改超时判断
- `TransferMonitorWorker.java` — 删除空方法

**风险：** 零。等价语义替换。时间戳比计数器更准确。

---

### 2.4 硬编码值迁移到 application.yml

**新增配置段（dev 环境）：**

```yaml
app:
  admin:
    default-username: ${ADMIN_DEFAULT_USERNAME:admin}
    default-password: ${ADMIN_DEFAULT_PASSWORD:}   # 生产必设，空则跳过自建

  qr-image:
    font-name: ${QR_FONT_NAME:SansSerif}            # 跨平台安全，原 Microsoft YaHei

  worker:
    callback:
      threads: ${CALLBACK_THREADS:4}
    tag:
      threads: ${TAG_THREADS:8}
      delay-ms: ${TAG_DELAY_MS:50}
    datafill:
      threads: ${DATAFILL_THREADS:4}

  redis-stream:
    callback-maxlen: ${CALLBACK_STREAM_MAXLEN:10000}
    tag-maxlen: ${TAG_STREAM_MAXLEN:50000}
    datafill-maxlen: ${DATAFILL_STREAM_MAXLEN:50000}
    dlq-maxlen: ${DLQ_STREAM_MAXLEN:10000}

  wecom:
    root-department-id: ${WECOM_ROOT_DEPT_ID:1}

  agent:
    daily-max-default: ${AGENT_DAILY_MAX:100}
    batch-import-daily-max: ${AGENT_BATCH_IMPORT_DAILY_MAX:200}
  transfer:
    form-url: ${TRANSFER_FORM_URL:}
```

**涉及文件（每个硬编码位置改为 `@Value` 注入）：**

| 硬编码值 | 文件 | 新注入点 |
|---------|------|---------|
| `admin123` | `DataInitializer.java` | `@Value("${app.admin.default-password}")` |
| `"Microsoft YaHei"` | `QrImageService.java` | `@Value("${app.qr-image.font-name}")` |
| 线程数 `4/8/4` | `CallbackWorker.java`, `TagWorker.java`, `DataFillWorker.java` | `@Value("${app.worker.*.threads}")` |
| Stream MAXLEN | `RedisConfig.java`, 各 Worker | `@Value("${app.redis-stream.*-maxlen}")` |
| `department_id=1` | `WecomApiClient.java` | `@Value("${app.wecom.root-department-id}")` |
| `dailyMax=100/200` | `QrCodeService.java`, `AgentRotationService.java` | `@Value("${app.agent.*-max}")` |
| `"[表单链接]"` | `TransferService.java` | `@Value("${app.transfer.form-url}")` |

**关键安全改动：** `DataInitializer` 中如果 `default-password` 为空 → 跳过自动创建 admin，打 WARN 日志，不输出密码。

```java
if (adminPassword == null || adminPassword.isBlank()) {
    log.warn("未配置默认管理员密码，跳过自建。请手动创建管理员账户或设置 ADMIN_DEFAULT_PASSWORD 环境变量");
    return;
}
```

**风险：** 零。`@Value` 默认值与原来硬编码值完全一致，不配置即保持原行为。

---

### 2.5 异常层次结构

**问题：** 所有 catch 块 `catch (Exception e)` 不区分瞬时/永久故障，`MessageGuardService` 的统一 3 次重试对所有异常类型同等执行，浪费重试机会。

**新异常类：**

```
wecom/
├── WecomApiException (extends RuntimeException)
│   ├── errcode: int
│   ├── errmsg: String
│   ├── responseBody: String
│
├── WecomTransientException     — errcode=-1, >=50000, 网络/超时
│   └── retryable: true, maxRetries: 3, backoff: 指数
│
├── WecomTokenExpiredException  — errcode=42001, 40014
│   └── retryable: true, maxRetries: 1, 重试前 refreshToken()
│
├── WecomRateLimitException     — errcode=45009
│   └── retryable: true, maxRetries: 1, 等待 Retry-After
│
└── WecomPermanentException     — errcode=40003, 60011, 其他非零
    └── retryable: false, 直接进 DLQ
```

**WecomApiClient.parseOrThrow() 改造：**

```java
// 改前：方法名"orThrow"误导，实际不抛
private JsonNode parseOrThrow(ResponseEntity<String> response) {
    JsonNode node = objectMapper.readTree(response.getBody());
    // errcode 检查留给调用方
    return node;
}

// 改后：直接抛对应异常
private JsonNode parseAndCheck(ResponseEntity<String> response) {
    JsonNode node = objectMapper.readTree(response.getBody());
    int errcode = node.path("errcode").asInt(0);
    if (errcode != 0) {
        String errmsg = node.path("errmsg").asText("未知错误");
        throwWecomException(errcode, errmsg, response.getBody());
    }
    return node;
}

private void throwWecomException(int errcode, String errmsg, String body) {
    // 按规则映射
    if (errcode == 42001 || errcode == 40014) {
        throw new WecomTokenExpiredException(errcode, errmsg, body);
    }
    if (errcode == 45009) {
        throw new WecomRateLimitException(errcode, errmsg, body);
    }
    if (errcode == -1 || errcode >= 50000) {
        throw new WecomTransientException(errcode, errmsg, body);
    }
    throw new WecomPermanentException(errcode, errmsg, body);
}
```

**MessageGuardService 按异常类型决策：**

```java
catch (WecomPermanentException e) {
    log.error("企微永久错误，消息入 DLQ: errcode={}, errmsg={}", e.getErrcode(), e.getErrmsg());
    sendToDlq(streamKey, message);
    return true; // ACK
}
catch (WecomTokenExpiredException e) {
    wecomApi.refreshToken();
    // 单次重试
}
catch (WecomRateLimitException e) {
    long waitMs = e.getRetryAfterSeconds() * 1000L;
    Thread.sleep(waitMs);
    // 单次重试
}
catch (WecomTransientException e) {
    // 现有 3 次重试逻辑
}
```

**涉及文件：**
- 新增 5 个异常类文件
- 修改 `WecomApiClient.java` — parseOrThrow 改为真抛异常
- 修改 `MessageGuardService.java` — catch 分支按类型决策
- 修改所有 `WecomApiClient` 调用方 — `if (errcode != 0)` → try-catch

**风险：** 中。`parseOrThrow` 签名变化影响所有调用方（~20 处），但改动是机械的。编译期全部可检测。

---

## 🟢 模块三：轻微问题（6 项）

### 3.1 Dashboard 查询合并

**问题：** `DashboardService.getDashboardStats()` 执行 15+ 次独立 `countBy*` 查询，每次 DB 往返 ~1ms。

**方案：** 新增聚合 DTO + 单次 JPQL。

```java
// 新增
@Data
public class DashboardStatsDTO {
    private long totalQrCodes;
    private long activeQrCodes;
    private long fullQrCodes;
    private long totalAgents;
    private long standbyPoolCount;
    private long todayAdds;
    private long todayTransfers;
    // ...
}

// QrCodeRepository 新增
@Query("SELECT new com.bookstore.qrcode.dto.DashboardStatsDTO("
    + "COUNT(q), "
    + "SUM(CASE WHEN q.status = 'active' THEN 1 ELSE 0 END), "
    + "SUM(CASE WHEN q.status = 'full' THEN 1 ELSE 0 END), "
    + "... ) FROM QrCode q")
DashboardStatsDTO getAggregatedStats();
```

Dashboard 页缓存到 Redis（TTL 60s），不每次实时算。

```java
@Cacheable(value = "dashboard-stats", key = "'current'")
public DashboardStatsDTO getDashboardStats() { ... }
```

**涉及文件：**
- 新增 `dto/DashboardStatsDTO.java`
- 修改 `DashboardService.java`
- 修改 `QrCodeRepository.java`, `AgentRepository.java`, `CustomerRepository.java`

**风险：** 低。JPQL 聚合较长但只读，正确性可在集成测试中验证。

---

### 3.2 GlobalAgentPoolService dailyReset 批量 UPDATE

**问题：** `dailyReset()` 对每个池记录逐条 save，500 人 = 500 次 UPDATE。

**方案：**

```java
// GlobalAgentPoolRepository 新增
@Modifying
@Query("UPDATE GlobalAgentPool p SET p.status = :newStatus, "
    + "p.sortOrder = p.sortOrder + :offset, p.lastResetAt = :now "
    + "WHERE p.status = :oldStatus")
int batchUpdateStatus(
    @Param("oldStatus") PoolStatus oldStatus,
    @Param("newStatus") PoolStatus newStatus,
    @Param("offset") int offset,
    @Param("now") LocalDateTime now);
```

调用后清理一级缓存：

```java
@Transactional
public void dailyReset() {
    LocalDateTime now = LocalDateTime.now();
    int updated = poolRepo.batchUpdateStatus(
        PoolStatus.full, PoolStatus.standby, 10000, now);
    log.info("dailyReset 批量更新: {} 人 full→standby", updated);
    entityManager.clear(); // @Modifying 绕过一级缓存
}
```

**涉及文件：**
- `GlobalAgentPoolRepository.java` — 新增 batch 方法
- `GlobalAgentPoolService.java` — 替换逐条 save

**风险：** 低。`@Modifying` 的已知行为，项目已有 `entityManager.clear()` 模式。

---

### 3.3 DataInitializer 密码日志脱敏

**问题：** 明文密码输出到日志。

**方案：**

```java
// 改前：
log.info("默认管理员已创建: admin / {}", rawPassword);

// 改后：
log.info("默认管理员已创建: admin（密码请查看环境变量或配置文件）");
```

配合 `default-password` 改为空默认值，日志中也不会出现明文。

**涉及文件：** `DataInitializer.java`

**风险：** 零。

---

### 3.4 WecomApiClient RestTemplate → RestTemplateBuilder

**问题：** `new RestTemplate()` 绕过 Spring Boot 自动配置。

**方案：**

```java
// 改前：
private final RestTemplate restTemplate = new RestTemplate();

// 改后：
private final RestTemplate restTemplate;

public WecomApiClient(
        @Value("${app.wecom.connect-timeout:3s}") Duration connectTimeout,
        @Value("${app.wecom.read-timeout:10s}") Duration readTimeout,
        RestTemplateBuilder builder) {
    this.restTemplate = builder
        .connectTimeout(connectTimeout)
        .readTimeout(readTimeout)
        .build();
}
```

**涉及文件：** `WecomApiClient.java`

**风险：** 零。Spring Boot 标准方式，行为等价。

---

### 3.5 关键路径补充集成测试

**新增测试文件：**

```
src/test/java/com/bookstore/qrcode/integration/
├── CallbackProcessingIntegrationTest.java
├── AgentRotationIntegrationTest.java
└── TransferLifecycleIntegrationTest.java
```

**测试覆盖：**

| 测试 | 覆盖路径 | 依赖 |
|------|---------|------|
| CallbackProcessing | 企微回调 → 客户 upsert → DataFill 事件 → 计数递增 | H2 + Redis Testcontainer |
| AgentRotation | 计数达上限 → 触发轮换 → 企微同步 → 自愈 | H2 + Redis Testcontainer + Mock WecomApi |
| TransferLifecycle | 发起转移 → 轮询确认 → 欢迎分支 → 超时标记 | H2 |

**风险：** 仅影响 CI 时间（+~30s）。Redis Testcontainer 需要 Docker。

---

### 3.6 JPA 关系映射 — 保持不动

不做改动。项目选择通过原始 ID 字段（`Long qrCodeId`、`String agentUserid`）管理关联，而非 JPA `@ManyToOne`/`@OneToMany` 注解。这是有意为之：

- **优点：** 避免懒加载陷阱、实体轻量、跨系统 ID 对齐企微原生 ID
- **代价：** 丢失级联查询能力，需手动 JOIN

在当前规模下合理。在 `CLAUDE.md` 中记录此决策。

---

## 实施顺序

```
Phase 1（1-2天）：低风险基础改动
  ├── 2.4 硬编码→配置
  ├── 2.3 超时判断改为时间戳
  ├── 3.3 密码日志脱敏
  ├── 3.4 RestTemplateBuilder
  └── 1.4 Lua 原子锁

Phase 2（2-3天）：结构重构
  ├── 2.1 AgentBindService 拆分 + 自愈去重
  ├── 2.2 删除 QrBackupPool
  └── 2.5 异常层次结构

Phase 3（1-2天）：补偿与限流
  ├── 1.1 企微对账巡检
  ├── 1.2 upsert DLQ 回退
  └── 1.3 Redis 学校限流

Phase 4（1天）：性能优化 + 测试
  ├── 3.1 Dashboard 查询合并
  ├── 3.2 批量 UPDATE
  └── 3.5 集成测试
```

## 回滚方案

每 Phase 独立 PR，合并后验证通过再进下一 Phase。Phase 2（最大改动范围）在合并前需要：

1. 全量 `mvn test` 通过
2. 手动验证：创建 QR 码、代理轮换、回调处理
3. `git revert` 可干净回滚（无跨 Phase 耦合）

---

## 附录：改动文件索引

| 文件 | 模块一 | 模块二 | 模块三 | 动作 |
|------|--------|--------|--------|------|
| `service/AgentBindService.java` | | 2.1 | | **删除** |
| `service/AgentDailyCountService.java` | | 2.1 | | **新增** |
| `service/AgentRotationService.java` | 1.4 | 2.1 | | **新增** |
| `service/WechatSyncHealingService.java` | | 2.1 | | **新增** |
| `entity/QrBackupPool.java` | | 2.2 | | **删除** |
| `repository/QrBackupPoolRepository.java` | | 2.2 | | **删除** |
| `wecom/WecomApiException.java` | | 2.5 | | **新增** |
| `wecom/WecomTransientException.java` | | 2.5 | | **新增** |
| `wecom/WecomTokenExpiredException.java` | | 2.5 | | **新增** |
| `wecom/WecomRateLimitException.java` | | 2.5 | | **新增** |
| `wecom/WecomPermanentException.java` | | 2.5 | | **新增** |
| `dto/DashboardStatsDTO.java` | | | 3.1 | **新增** |
| `service/QrCodeService.java` | 1.1 | 2.1, 2.2, 2.4 | | 修改 |
| `service/CustomerService.java` | 1.2 | | | 修改 |
| `config/SchoolRateLimitFilter.java` | 1.3 | | | 重写 |
| `config/RedisConfig.java` | 1.3, 1.4 | | | 修改 |
| `worker/PatrolWorker.java` | 1.1 | | | 修改 |
| `service/TransferService.java` | | 2.3, 2.4 | | 修改 |
| `worker/TransferMonitorWorker.java` | | 2.3 | | 修改 |
| `service/GlobalAgentPoolService.java` | | | 3.2 | 修改 |
| `repository/GlobalAgentPoolRepository.java` | | | 3.2 | 修改 |
| `service/DashboardService.java` | | | 3.1 | 修改 |
| `config/DataInitializer.java` | | 2.4 | 3.3 | 修改 |
| `wecom/WecomApiClient.java` | | 2.4, 2.5 | 3.4 | 修改 |
| `service/QrImageService.java` | | 2.4 | | 修改 |
| `worker/CallbackWorker.java` | | 2.4 | | 修改 |
| `worker/TagWorker.java` | | 2.4 | | 修改 |
| `worker/DataFillWorker.java` | | 2.4 | | 修改 |
| `service/MessageGuardService.java` | | 2.5 | | 修改 |
| `resources/application.yml` | 1.3 | 2.4 | | 修改 |
| `resources/schema.sql` | | 2.2 | | 修改 |
| `CLAUDE.md` | | | 3.6 | 修改 |
| `docs/migration-drop-qr-backup-pool.sql` | | 2.2 | | **新增** |
| `*IntegrationTest.java` (3个) | | | 3.5 | **新增** |
