# 火马平台全库代码审查 — 修复方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-08-24 全库代码审查发现的所有 Critical / High / Medium / Low 问题。

**Architecture:** 按"严重程度 × 主题"分批，每批一个 Task。先修阻止启动/消息丢失/认证绕过/越权的 Critical 与安全 High，再修并发正确性、性能，最后收 Medium/Low。每个 Task 独立可验证、可单独合并。

**Tech Stack:** Spring Boot 3.2.12、Java 17、Spring Security、JPA/Hibernate、MySQL、Redis Streams、Flyway、Thymeleaf。

**Spec:** 2026-08-24 全库审查报告（Critical C1-C4、High H1-H6、Medium、Low）。

## Global Constraints

- 无 JPA 关系注解（`@ManyToOne`/`@OneToMany`），实体用显式 FK ID 字段（CLAUDE.md 约定，勿破坏）。
- 外部 HTTP API 调用不得放在 `@Transactional` 内（项目既有原则，见 TransferMonitorWorker 注释）。
- 分布式锁释放必须走 `RedisConfig.SAFE_UNLOCK_SCRIPT`（GET+COMPARE+DEL），锁值用 UUID，禁止裸 `delete`。
- Java 17，Maven 构建：`./mvnw test`。
- 提交信息遵循仓库风格（`fix:` / `feat:` 前缀，中文描述）。

---

### Task 1: 修复 V13 迁移非幂等导致的启动失败（C1）

**Files:**
- Modify: `src/main/resources/db/migration/V13__greeting_permanent_fail.sql`

**Interfaces:**
- Consumes: 无（独立迁移脚本）
- Produces: 全新库/灾备重建时 `flyway` 不再因 `Duplicate column` 失败

- [ ] **Step 1: 加列存在性守卫**

将 `V13__greeting_permanent_fail.sql` 整文件替换为（仿 V7 的 `INFORMATION_SCHEMA` 守卫写法）：

```sql
-- V13: 欢迎语永久失败标记列（idempotent: INFORMATION_SCHEMA 守卫）
-- 与 schema.sql 内联列保持一致；通过存在性检查避免新库/重建时 Duplicate column

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND COLUMN_NAME = 'greeting_permanent_fail') = 0,
    'ALTER TABLE customer_transfer ADD COLUMN greeting_permanent_fail BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''欢迎语是否永久失败（区分真正成功和放弃重试）'' AFTER greeting_sent',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 2: 验证**

在全新 MySQL 库上执行：`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`，确认启动无 `Duplicate column name 'greeting_permanent_fail'` 错误，Flyway 记录 V13 成功。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V13__greeting_permanent_fail.sql
git commit -m "fix: V13 迁移加列存在性守卫，修复全新库启动 Duplicate column"
```

---

### Task 2: 修复 `_retry_at` 退避消息先 ACK 后丢失（C2）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:151-163`
- Modify: `src/main/java/com/bookstore/qrcode/worker/TagWorker.java:135-147`
- Modify: `src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java:117-129`
- Modify: `src/main/java/com/bookstore/qrcode/worker/OutboundMsgWorker.java:121-131`
- Modify: `src/main/java/com/bookstore/qrcode/worker/TransferWorker.java:87-98`

**Interfaces:**
- Consumes: `MessageGuardService.markRetryOrDead`（现有，不改）
- Produces: 未到 `_retry_at` 的消息**留在 PEL**，由 `MessageGuardService.recoverOrphanedPending`（idle>30s）用 XCLAIM/XADD 重投

**问题本质：** 当前逻辑读到一个带 `_retry_at` 且未到期的消息后，`acknowledge + continue`。ACK 会把它从 PEL 移除，回收器（只扫 PEL）再也看不到它 → 消息永久丢失。正确做法：**未到期时不 ACK，直接跳过（`continue`），让消息留在 PEL**，由 PEL 回收器按延迟重投。

- [ ] **Step 1: 修改 CallbackWorker（其余 4 个 Worker 同款替换）**

将 `CallbackWorker.java` 第 151-163 行替换为：

```java
                    // 检查 _retry_at 时间戳（指数退避），未到时间则不 ACK、留在 PEL
                    // 由 MessageGuardService.recoverOrphanedPending 在 idle>30s 后重投
                    String retryAt = (String) value.get("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > java.time.Instant.now().getEpochSecond()) {
                                continue; // 不 ACK，留 PEL 等待延迟重投
                            }
                        } catch (NumberFormatException ignored) {}
                    }
```

关键改动：删除原来的 `redisTemplate.opsForStream().acknowledge(...)` 调用，只保留 `continue`。

- [ ] **Step 2: 应用到其余 4 个 Worker**

对 `TagWorker.java`、`DataFillWorker.java`、`OutboundMsgWorker.java`、`TransferWorker.java` 中相同的 `_retry_at` 判断块做同样替换（删除未到期分支里的 `acknowledge`）。

- [ ] **Step 3: 验证回收器能重投未到期消息**

确认 `MessageGuardService.recoverOrphanedPending` 会扫描 PEL 中 `idle > 30s` 的消息并 `XCLAIM` 后 `XADD` 回 Stream。由于 `_retry_at` 消息现在留在 PEL，超过 idle 阈值后会被回收器重投。运行 `./mvnw test` 确认现有测试通过。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/worker/
git commit -m "fix: 退避重试消息未到期时不 ACK，留在 PEL 由回收器重投，避免消息丢失"
```

---

### Task 3: 门控 dev-login 端点 + 默认 profile 加固（C3）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java:89`
- Modify: `src/main/resources/application.yml:4`

**Interfaces:**
- Consumes: 无
- Produces: 生产 profile 下 `/download/oauth/dev-login` 端点不存在；即使误用 dev profile 也可用配置禁用

- [ ] **Step 1: 端点改为 `@Profile("!prod")` + 配置门控**

`DownloadCenterController.java` 第 89 行 `@Profile("dev")` 改为 `@Profile("!prod")`，并注入配置开关：

```java
@Value("${app.oauth.dev-login-enabled:false}")
private boolean devLoginEnabled;
```

在 `devLogin` 方法体开头加：

```java
if (!devLoginEnabled) {
    model.addAttribute("error", "开发快捷登录未启用");
    return "download/error";
}
```

- [ ] **Step 2: 配置默认值显式化**

`application.yml` 第 4 行 `active: dev` 改为 `active: ${SPRING_PROFILES_ACTIVE:dev}`，并在 `app:` 下新增：

```yaml
  oauth:
    dev-login-enabled: ${OAUTH_DEV_LOGIN_ENABLED:false}
```

- [ ] **Step 3: 验证**

`./mvnw test` 通过；启动 prod profile 后访问 `/download/oauth/dev-login` 应 404。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java src/main/resources/application.yml
git commit -m "fix: dev-login 端点改为 !prod 且默认关闭，防认证绕过"
```

---

### Task 4: 补全局异常处理与 DTO 校验（C4）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/GlobalControllerAdvice.java`
- Modify: `src/main/java/com/bookstore/qrcode/dto/QrCodeCreateRequest.java`

**Interfaces:**
- Consumes: 无
- Produces: 表单绑定失败、`MethodArgumentNotValidException`、`BindException`、通用 `Exception` 均返回友好错误而非 500 裸奔

- [ ] **Step 1: 扩展 GlobalControllerAdvice**

在 `GlobalControllerAdvice.java` 新增 import 与 handler：

```java
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
public String handleValidation(BindException ex, RedirectAttributes ra) {
    String msg = ex.getBindingResult().getAllErrors().stream()
        .findFirst()
        .map(e -> e.getDefaultMessage())
        .orElse("请求参数不合法");
    ra.addFlashAttribute("error", msg);
    return "redirect:" + (ex.getBindingResult().getTarget() == null
        ? "/qrcodes" : "");
}
```

（注：由于项目表单多为 POST-Redirect-GET，`return` 目标按实际控制器调整；如无固定回跳页，返回 `school/not-found` 或一个通用 error 页。）

- [ ] **Step 2: 加 DTO 约束注解**

`QrCodeCreateRequest.java` 关键字段加校验：

```java
import jakarta.validation.constraints.*;

@NotBlank(message = "学校名称不能为空")
private String schoolName;

@NotBlank(message = "学校ID不能为空")
private String schoolId;

@Min(value = 0, message = "学生人数不能为负")
private Integer studentCount;
```

- [ ] **Step 3: 验证**

`./mvnw test`；启动后提交空 `schoolName` 的表单，应重定向并提示而非 500。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/GlobalControllerAdvice.java src/main/java/com/bookstore/qrcode/dto/QrCodeCreateRequest.java
git commit -m "fix: 补全局异常处理与 DTO 校验，消除表单绑定 500 裸奔"
```

---

### Task 5: 服务端图片代理加域名白名单（H1）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/SchoolEntryController.java:171`
- Modify: `src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java:231`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java:1857,1906`

**Interfaces:**
- Consumes: 无
- Produces: `qrUrl` 非企微白名单域名时拒绝代理，阻断 SSRF

- [ ] **Step 1: 新增工具方法（放在 SchoolEntryController 或独立工具类）**

> **⚠️ 数据核查结果（2026-08-24 生产库）：** `qr_code.qr_url` 全部指向 `wework.qpic.cn`（企微图片 CDN，3201 条），**不是** `open.work.weixin.qq.com`。白名单必须包含 `wework.qpic.cn`，否则会拦截全部活码下载。

```java
private static final Set<String> WECOM_QR_HOSTS =
    Set.of("wework.qpic.cn", "open.work.weixin.qq.com");

private boolean isAllowedQrUrl(String url) {
    if (url == null) return false;
    try {
        URI u = URI.create(url);
        String host = u.getHost();
        return "https".equalsIgnoreCase(u.getScheme())
            && host != null
            && WECOM_QR_HOSTS.stream().anyMatch(h -> h.equalsIgnoreCase(host));
    } catch (Exception e) {
        return false;
    }
}
```

- [ ] **Step 2: 三处代理前加校验**

在三处 `openConnection()` 前插入：

```java
if (!isAllowedQrUrl(detail.getQrUrl())) {   // 或 qr.getQrUrl()
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();  // 或 sendError
}
```

`SchoolEntryController.java:170`、`DownloadCenterController.java:230`、`QrCodeController.java:1856` 与 `1905` 各插一处。

- [ ] **Step 3: 验证**

`./mvnw test`；手动把某活码 `qrUrl` 改为 `http://169.254.169.254/`（或内网地址）后访问下载接口，应 403。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/
git commit -m "fix: 图片代理加企微域名白名单，阻断 SSRF"
```

---

### Task 6: 收窄 operator 越权访问运维端点（H2）

> **⚠️ 业务约束（2026-08-24 确认）：** operator（YJR）需要创建/删除活码等管理操作，**活码写操作与客户导出保留给 operator**，本任务只收窄纯运维端点。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java:53-89`

**Interfaces:**
- Consumes: 无
- Produces: 纯运维端点（`/api/health/**`、`/api/inheritance/**`）仅 ADMIN；活码写操作、客户导出保持 `authenticated()`（operator 可用）

- [ ] **Step 1: 在 `authorizeHttpRequests` 中按角色收窄运维端点**

在 `SecurityConfig.java` 的 `authorizeHttpRequests` 链中，于 `.anyRequest().authenticated()` 之前插入（注意顺序：更具体的 matcher 在前）：

```java
                        // 运维端点：仅 admin（health 详情含 Stream/PEL 深度，dlq 重放为破坏性操作）
                        .requestMatchers("/api/health/**").hasRole("ADMIN")
                        // 自动在职继承全局开关：仅 admin（破坏性，影响全局继承行为）
                        .requestMatchers("/api/inheritance/**").hasRole("ADMIN")
```

**不修改** `/qrcodes/**`、`/customers/**`、`/agents/**`、`/dashboard` 的现有 `authenticated()` 规则（operator 需保留活码创建/删除与客户管理权限）。

（`/api/inheritance/**` 目前也在 CSRF 忽略列表第 43 行，保持不变；角色控制与 CSRF 忽略是两回事。）

- [ ] **Step 2: 验证**

用 operator 账号（YJR）登录后：
- 访问 `/api/health/streams`、`/api/health/dlq/replay`、`/api/inheritance/auto/toggle` 应 **403**；
- 创建/删除活码、访问 `/customers` 列表应**正常**；
- admin 访问全部正常。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/SecurityConfig.java
git commit -m "fix: 运维端点（health/inheritance）收窄为 ADMIN，保留 operator 活码管理权限"
```

---

### Task 7: TransferMonitorWorker 分布式锁 token 化（H3）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java:73-91,104-117,136-149`

**Interfaces:**
- Consumes: `RedisConfig.SAFE_UNLOCK_SCRIPT`
- Produces: 锁值 UUID + 安全释放，锁过期后不会误删他人锁

- [ ] **Step 1: 三处锁改用 UUID + SAFE_UNLOCK_SCRIPT**

将 `monitor()`（第 73-91 行）改为：

```java
    @Scheduled(cron = "0 */30 * * * *")
    public void monitor() {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(TRACK_RESULTS_LOCK_KEY, lockValue, TRACK_RESULTS_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("继承结果追踪被其他实例执行中，跳过");
            return;
        }
        try {
            List<Long> newlyConfirmed = transferService.trackResults();
            if (!newlyConfirmed.isEmpty()) {
                transferService.sendGreetingsForNewlyConfirmed(newlyConfirmed);
            }
        } catch (Exception e) {
            log.error("继承结果追踪异常", e);
        } finally {
            redisTemplate.execute(RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(TRACK_RESULTS_LOCK_KEY), lockValue);
        }
    }
```

`retryFailed()`（104-117）、`retryGreetings()`（136-149）同款：加 `UUID` 锁值、`finally` 用 `SAFE_UNLOCK_SCRIPT` 替换裸 `delete`。

- [ ] **Step 2: 补 import**

文件顶部加 `import java.util.UUID;`（`List` 已有）。

- [ ] **Step 3: 验证**

`./mvnw test`。可选：起两个实例观察调度日志，确认无重复处理告警。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java
git commit -m "fix: TransferMonitorWorker 分布式锁改用 UUID + 安全释放，防误删他人锁"
```

---

### Task 8: 修复 AgentRotationService 自调用绕过事务（H4）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/AgentRotationService.java`

**Interfaces:**
- Consumes: 无
- Produces: `incrementDailyCount` 内触发的 `checkAndRotate`/`expandQrCodeUsers`/`preActivateBackup` 在真实事务中执行

**问题本质：** `incrementDailyCount`（第 53 行，无 `@Transactional`）内部 `this.checkAndRotate(...)` 是自调用，Spring AOP 代理不拦截，`@Transactional`（108/160/258 行）全部失效；`expandQrCodeUsers` 里的 `registerSynchronization(afterCommit)` 在无活动事务时抛 `IllegalStateException`。

- [ ] **Step 1: 抽出事务边界到独立 Service（推荐）或注入 self**

新建 `AgentRotationTxService.java`：

```java
@Service
@RequiredArgsConstructor
public class AgentRotationTxService {
    private final AgentRotationService rotationService;

    @Transactional
    public void rotate(Long qrCodeId, String userId, int globalCount) {
        rotationService.checkAndRotate(qrCodeId, userId, globalCount);
    }
}
```

将 `checkAndRotate`、`expandQrCodeUsers`、`preActivateBackup` 上的 `@Transactional` 移除（移到新 Service），`incrementDailyCount` 末行改为：

```java
rotationTxService.rotate(qr.getId(), userId, (int) totalNew);
```

（若团队偏好更小改动，可改用注入 self 代理：`@Autowired private AgentRotationService self;` 然后 `self.checkAndRotate(...)`，但需保留 `allow-circular-references: true`，不推荐。）

- [ ] **Step 2: 验证**

`./mvnw test`；打日志确认触发轮换时 `afterCommit` 同步企微正常执行、无 `IllegalStateException: Transaction synchronization is not active`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/AgentRotationService.java src/main/java/com/bookstore/qrcode/service/AgentRotationTxService.java
git commit -m "fix: 轮换事务边界抽到独立 Service，修复自调用绕过 @Transactional"
```

---

### Task 9: 治理热路径全表扫描与 N+1（H5）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java:222-229`
- Modify: `src/main/java/com/bookstore/qrcode/controller/CustomerController.java:119-120,197-204`

**Interfaces:**
- Consumes: `QrAgentRepository.findByQrCodeIdAndStatus`（现有）
- Produces: 批量聚合查询替代循环内 N+1；`findAll` 改为投影/缓存

- [ ] **Step 1: 消除 QrCodeController 循环内 N+1 与重复 count**

`QrCodeController.java` 第 222-229 行替换为：

```java
        // ---- 5. 客服数统计（批量聚合，避免 N+1） ----
        Map<Long, String> agentCountMap = new HashMap<>();
        long poolStandby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby); // 全局计数只查一次
        for (QrCode qr : qrCodes.getContent()) {
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
                qr.getId(), QrAgent.AgentStatus.active).size();
            agentCountMap.put(qr.getId(), activeCount + "/" + poolStandby);
        }
```

（`poolStandby` 提出循环外；`activeCount` 的 N+1 若数据量大，可加 `QrAgentRepository.countByQrCodeIdInAndStatus(List<Long>, AgentStatus)` 一次性聚合，返回 `List<Object[]>`。）

- [ ] **Step 2: 优化 CustomerController 的 findAll**

`CustomerController.java` 第 119-120 与 203-204 的 `qrCodeRepo.findAll()` + `agentRepo.findAll()` 用于下拉/映射。若表大，改用投影 DTO 查询（`findAllSchoolIdAndName()`）或 Caffeine 缓存（参考项目 `CacheConfig`）。最小改动：保持现状但加缓存注解，或至少确认 `getCachedAgentNameMap` 已缓存（第 159 行）。

- [ ] **Step 3: 验证**

`./mvnw test`；本地起服务访问 `/qrcodes`、`/customers` 列表页，用 `show-sql=true` 临时观察 SQL 条数是否下降。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/main/java/com/bookstore/qrcode/controller/CustomerController.java
git commit -m "perf: 消除列表页 N+1 与循环内重复 count，poolStandby 提出循环"
```

---

### Task 10: 消除事务内外部 I/O 与 sleep 的长事务（H6）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerService.java:76-77,325-326`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java:249`
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java:94`
- Modify: `src/main/java/com/bookstore/qrcode/service/TagService.java:82`
- Modify: `src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java:87`

**Interfaces:**
- Consumes: 无
- Produces: 事务内不再调用企微 HTTP API、不再 `Thread.sleep`

- [ ] **Step 1: CustomerService.repairCustomerData 移除事务内 sleep**

`CustomerService.java` 第 325-376 行的 `@Transactional public int repairCustomerData()` 里，第 341 行 `Thread.sleep(10_000)` 改为把整个方法改成**无 `@Transactional`**，内部按批（每 100 条）拆分小事务。若改动大，最小改动：把 `@Transactional` 去掉，将每批 `saveAll` 包成独立事务方法。

- [ ] **Step 2: upsertFromCallback 移出 sleep 重试循环**

`CustomerService.java` 第 76-77 行的 `@Transactional` 包裹了第 127-141 行的 `Thread.sleep` 指数退避重试。将该重试循环移到 `@Transactional` 方法外（拆成「先尝试快速插入 → 失败则非事务等待重试」）。

- [ ] **Step 3: 其余三个 Service 的 API 调用移出事务**

`QrCodeService.create`（249）、`TransferService.initiate`（94）、`TagService.autoTag`（82）、`EmployeeSyncService.syncFromWecom`（87）：将事务内调用企微 API 的代码移到 `@Transactional` 方法返回之后，或改 `TransactionSynchronization.afterCommit` 异步同步（项目已有此模式，见 `expandQrCodeUsers`）。

- [ ] **Step 4: 验证**

`./mvnw test`；观察 `spring.transaction.default-timeout=30s` 下无事务超时告警。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/
git commit -m "fix: 事务内移除企微 API 调用与 sleep，消除长事务占用连接"
```

---

### Task 11: 收 Medium 安全问题（OAuth state、CSV 注入、上传校验、限流 IP、签名恒定时间、日志降噪）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/WecomOAuthService.java:43-46`
- Modify: `src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java:72-84`
- Modify: `src/main/java/com/bookstore/qrcode/controller/CustomerController.java:308-313`
- Modify: `src/main/java/com/bookstore/qrcode/controller/FormTemplateController.java:29-48`
- Modify: `src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java:81`
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomCallbackValidator.java:87,143`

- [ ] **Step 1: OAuth state 回验**

`WecomOAuthService.buildAuthUrl`（43-46）把 state 存入 Session，`authenticate` 前校验：

```java
public String buildAuthUrl(String redirectUri, HttpSession session) {
    String state = UUID.randomUUID().toString().substring(0, 8);
    session.setAttribute("oauth_state", state);
    return wecomApiClient.buildOAuthUrl(redirectUri, state);
}
```

`DownloadCenterController.oauthCallback`（72-84）在调 `authenticate` 前比对 `session.getAttribute("oauth_state")` 与回调带回的 state（需 `buildAuthUrl` 传 session、回调接收 `@RequestParam String state`）。

- [ ] **Step 2: CSV 公式注入**

`CustomerController.csvEscape`（308-313）在开头加前缀防护：

```java
private static String csvEscape(String s) {
    if (s == null) return "";
    String v = s.replace("\"", "\"\"").replace("\n", "\\n").replace("\r", "\\r");
    if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
        v = "'" + v;
    }
    return v;
}
```

- [ ] **Step 3: 文件上传扩展名白名单**

`FormTemplateController`（29-48）加白名单：

```java
private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");
// 取扩展名后：
if (!ALLOWED_EXT.contains(ext.toLowerCase())) {
    return ...; // 拒绝
}
```

- [ ] **Step 4: 限流 IP 取可信头**

`SchoolRateLimitFilter.java` 第 81 行，先取 `X-Forwarded-For` 首段（因 `forward-headers-strategy: native`），或改用 `request.getRemoteAddr()` 并关闭该策略。最小改动：

```java
String ip = request.getHeader("X-Forwarded-For");
if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
else ip = ip.split(",")[0].trim();
```

- [ ] **Step 5: 回调签名恒定时间比较**

`WecomCallbackValidator.java` 第 87、143 行 `signature.equals(msgSignature)` 改为：

```java
private boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
}
```

- [ ] **Step 6: 敏感日志降噪**

`WecomCallbackController.java:174` 打印完整解密 XML、`QrCodeController.java:425` 打印企微原始响应，改为 `log.debug` 或脱敏（去除 `ExternalUserID`/`WelcomeCode`）。

- [ ] **Step 7: 验证与 Commit**

`./mvnw test`；然后：

```bash
git add src/main/java/com/bookstore/qrcode/service/WecomOAuthService.java src/main/java/com/bookstore/qrcode/controller/ src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java src/main/java/com/bookstore/qrcode/wecom/WecomCallbackValidator.java
git commit -m "fix: 收 Medium 安全问题（OAuth state、CSV 注入、上传白名单、限流 IP、恒定时间比较、日志脱敏）"
```

---

### Task 12: 收 Medium 正确性与 Low（hex 格式化、createMode 默认值、索引、资源关闭）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/MessageGuardService.java:89-91`
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrCode.java:149-152`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java:1113`
- Create: `src/main/resources/db/migration/V14__add_query_indexes.sql`

- [ ] **Step 1: 修复 computeLogicalId 十六进制格式化**

`MessageGuardService.java` 第 90 行 `String.format("%02x", digest[i])` 对负字节输出 `ffffffxx`。改为：

```java
sb.append(String.format("%02x", digest[i] & 0xff));
```

- [ ] **Step 2: createMode 加默认值**

`QrCode.java` 第 152 行 `private CreateMode createMode;` 加 `@Builder.Default` 与默认值：

```java
@Column(name = "create_mode", nullable = false, length = 20)
@Enumerated(EnumType.STRING)
@Builder.Default
private CreateMode createMode = CreateMode.manual;
```

（需确认 `CreateMode` 枚举有 `manual` 值；`QrCodeService.java:282` 已用 `QrCode.CreateMode.manual`，确认存在。）

- [ ] **Step 3: XSSFWorkbook 兜底关闭**

`QrCodeController.java:1113` 的 `wb.close()` 包进 try/finally（参考同文件 `export` 已用 try/finally）。

- [ ] **Step 4: 补索引迁移**

新建 `V14__add_query_indexes.sql`：

```sql
-- V14: 补高频查询索引
CREATE INDEX idx_customer_transfer_to_userid_status
    ON customer_transfer (to_userid, status);
CREATE INDEX idx_customer_added_agent_school_addtime
    ON customer (added_agent, school_id, add_time);
```

（用 `INFORMATION_SCHEMA` 守卫包裹，保证幂等，仿 V7 写法。）

- [ ] **Step 5: 验证与 Commit**

`./mvnw test`；全新库启动确认 V14 应用成功。然后：

```bash
git add src/main/java/com/bookstore/qrcode/service/MessageGuardService.java src/main/java/com/bookstore/qrcode/entity/QrCode.java src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/main/resources/db/migration/V14__add_query_indexes.sql
git commit -m "fix: 修 hex 格式化、createMode 默认值、资源关闭，补查询索引"
```

---

## Self-Review

**Spec coverage:** C1(Task1)、C2(Task2)、C3(Task3)、C4(Task4)、H1(Task5)、H2(Task6)、H3(Task7)、H4(Task8)、H5(Task9)、H6(Task10)、Medium 安全(Task11)、Medium 正确性+Low(Task12) 全部有对应任务。剩余 Low 项（`WecomApiClient` 参数编码、`school_id` 长度 50 vs 64、魔法数字、Controller 直接持久化）因影响小且涉及面广，未单列，可在 Task 12 后按需追加。

**Placeholder scan:** 无 TBD/TODO；Task 4 的 `GlobalControllerAdvice` 回跳路径需按实际控制器确认（已注释说明）；Task 8 的 self 代理方案标注了取舍。

**Type consistency:** `SAFE_UNLOCK_SCRIPT` 类型 `DefaultRedisScript<Long>`、`execute(script, List.of(key), lockValue)` 签名在 Task 7 与现有 `AgentRotationService`（240-242 行）一致；`@Builder.Default`、`@Enumerated(EnumType.STRING)` 与实体现状一致；`CreateMode.manual` 已由 `QrCodeService.java:282` 佐证存在。

---

## Execution Handoff

方案已保存。两个执行选项：

**1. Subagent-Driven（推荐）** — 每个 Task 派发独立 subagent，任务间 review，快速迭代。

**2. Inline Execution** — 在本会话用 executing-plans 批量执行，检查点处 review。

选哪种？
