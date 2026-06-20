# 活码平台设计缺陷修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 16 项设计缺陷——数据一致性风险、技术债务、性能瓶颈——不改变系统架构。

**Architecture:** 四阶段渐进式修复。Phase 1 打基础（配置外置化、原子锁、超时修正），Phase 2 重构核心（异常层次、服务拆分、删除死代码），Phase 3 加补偿（企微对账、DLQ 回退、Redis 限流），Phase 4 优化性能（查询合并、批量 UPDATE、集成测试）。

**Tech Stack:** Spring Boot 3.2.5, JPA/Hibernate, Redis Stream, Caffeine, ZXing, Maven, JUnit 5, H2

## Global Constraints

- 不改变三层单体 + Redis Stream 架构
- 不新增 Maven 依赖
- 所有配置项提供默认值（与原硬编码值一致）
- Redis 新增操作 ≤200ms 超时保护
- 每 Phase 独立 PR，可干净 `git revert`
- 全量 `mvn test` 通过后才合并

---

### Task 1: 配置外部化 — 硬编码值迁移到 application.yml

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/bookstore/qrcode/config/DataInitializer.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrImageService.java`
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/worker/TagWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java`
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`

**Interfaces:**
- Consumes: nothing (foundational)
- Produces: `@Value`-injectable config properties for all downstream tasks

- [ ] **Step 1: Add new config section to application.yml**

Insert before the `---` profile separator in both `dev` and `prod` profiles:

```yaml
# ===== 应用业务配置 =====
app:
  admin:
    default-username: ${ADMIN_DEFAULT_USERNAME:admin}
    default-password: ${ADMIN_DEFAULT_PASSWORD:}   # 生产必设，空则跳过自建
  qr-image:
    font-name: ${QR_FONT_NAME:SansSerif}            # Linux 兼容默认字体
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

- [ ] **Step 2: Run app to verify config loads without errors**

```bash
mvn spring-boot:run 2>&1 | head -20
```
Expected: Application starts normally, no "Could not resolve placeholder" errors.

- [ ] **Step 3: Fix DataInitializer — password from config + no log leak**

Read the current file first, then edit. In `DataInitializer.java`:

```java
// Add @Value fields alongside existing ones:
@Value("${app.admin.default-username:admin}")
private String defaultUsername;

@Value("${app.admin.default-password:}")
private String defaultPassword;

// In the run() method, replace the hardcoded check:
// BEFORE:
// if (userRepo.countByEnabledTrue() == 0) {
//     String rawPassword = "admin123";
//     ...

// AFTER:
@Override
public void run(ApplicationArguments args) {
    if (userRepo.countByEnabledTrue() > 0) {
        return;
    }
    if (defaultPassword == null || defaultPassword.isBlank()) {
        log.warn("未配置默认管理员密码(app.admin.default-password)，跳过自建。"
            + "请手动创建管理员账户或设置 ADMIN_DEFAULT_PASSWORD 环境变量");
        return;
    }
    User admin = new User();
    admin.setUsername(defaultUsername);
    admin.setPasswordHash(passwordEncoder.encode(defaultPassword));
    admin.setDisplayName("系统管理员");
    admin.setRole(User.UserRole.admin);
    admin.setEnabled(true);
    userRepo.save(admin);
    log.info("默认管理员已创建: {}（密码请查看环境变量或配置文件）", defaultUsername);
}
```

- [ ] **Step 4: Fix QrImageService — font from config**

```java
// Add field:
@Value("${app.qr-image.font-name:SansSerif}")
private String fontName;

// In generateQrImage(), replace hardcoded "Microsoft YaHei":
// BEFORE: Font font = new Font("Microsoft YaHei", Font.PLAIN, fontSize);
// AFTER:
Font font = new Font(fontName, Font.PLAIN, fontSize);
```

- [ ] **Step 5: Fix CallbackWorker — thread count from config**

```java
// Replace:
// private static final int CONSUMER_THREADS = 4;
// With:
@Value("${app.worker.callback.threads:4}")
private int consumerThreads;
```

- [ ] **Step 6: Fix TagWorker — thread count and delay from config**

```java
// Replace:
// private static final int CONSUMER_THREADS = 8;
// With:
@Value("${app.worker.tag.threads:8}")
private int consumerThreads;

// Replace:
// @Value("${worker.tag.delay-ms:50}")
// With:
@Value("${app.worker.tag.delay-ms:50}")
private long tagDelayMs;
```

- [ ] **Step 7: Fix DataFillWorker — thread count from config**

```java
// Replace:
// private static final int CONSUMER_THREADS = 4;
// With:
@Value("${app.worker.datafill.threads:4}")
private int consumerThreads;
```

- [ ] **Step 8: Fix RedisConfig — stream MAXLEN from config**

```java
// Replace the static final constants:
// public static final long STREAM_MAXLEN = 10000L;
// With non-static fields injected by Spring:
// Since these are referenced by static methods/constants in other classes,
// keep them as static but load from config. Use @Value on a setter:

private static long streamMaxlen = 10000;
private static long tagStreamMaxlen = 50000;
private static long datafillStreamMaxlen = 50000;
private static long dlqStreamMaxlen = 10000;

@Value("${app.redis-stream.callback-maxlen:10000}")
public void setStreamMaxlen(long val) { RedisConfig.streamMaxlen = val; }

@Value("${app.redis-stream.tag-maxlen:50000}")
public void setTagStreamMaxlen(long val) { RedisConfig.tagStreamMaxlen = val; }

@Value("${app.redis-stream.datafill-maxlen:50000}")
public void setDatafillStreamMaxlen(long val) { RedisConfig.datafillStreamMaxlen = val; }

@Value("${app.redis-stream.dlq-maxlen:10000}")
public void setDlqStreamMaxlen(long val) { RedisConfig.dlqStreamMaxlen = val; }

// Keep existing public static field declarations but remove the = initializer:
public static long STREAM_MAXLEN;
public static long TAG_STREAM_MAXLEN;
public static long DATAFILL_STREAM_MAXLEN;
public static long DLQ_STREAM_MAXLEN;
```

- [ ] **Step 9: Fix WecomApiClient — root department from config**

```java
// Add field:
@Value("${app.wecom.root-department-id:1}")
private int rootDepartmentId;

// In getUserSimplelist(), replace hardcoded department_id=1:
// BEFORE: params.add("department_id", "1");
// AFTER:
params.add("department_id", String.valueOf(rootDepartmentId));
```

- [ ] **Step 10: Fix TransferService — form URL from config**

```java
// Add field:
@Value("${app.transfer.form-url:}")
private String formUrl;

// In fillTemplate(), replace hardcoded "[表单链接]":
// BEFORE: content = content.replace("${formLink}", "[表单链接]");
// AFTER:
String resolvedFormUrl = (formUrl != null && !formUrl.isBlank())
    ? formUrl : "[表单链接]";
content = content.replace("${formLink}", resolvedFormUrl);
```

- [ ] **Step 11: Fix QrCodeService — dailyMax from config**

```java
// Add fields:
@Value("${app.agent.daily-max-default:100}")
private int dailyMaxDefault;

@Value("${app.agent.batch-import-daily-max:200}")
private int batchImportDailyMax;

// Replace hardcoded 100 in related methods:
// BEFORE: agent.setDailyMax(100);
// AFTER: agent.setDailyMax(dailyMaxDefault);

// Replace hardcoded 200 in batch import:
// BEFORE: agent.setDailyMax(200);
// AFTER: agent.setDailyMax(batchImportDailyMax);
```

- [ ] **Step 12: Run full test suite**

```bash
mvn test
```
Expected: All tests pass (existing 3 tests).

- [ ] **Step 13: Commit**

```bash
git add src/main/resources/application.yml \
  src/main/java/com/bookstore/qrcode/config/DataInitializer.java \
  src/main/java/com/bookstore/qrcode/service/QrImageService.java \
  src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java \
  src/main/java/com/bookstore/qrcode/worker/TagWorker.java \
  src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java \
  src/main/java/com/bookstore/qrcode/config/RedisConfig.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java \
  src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "refactor: 硬编码值迁移到 application.yml 配置项

- 管理员密码从环境变量注入，空值跳过自建，移除日志明文
- 字体名从 SansSerif（Linux 兼容）
- Worker 线程数、Stream MAXLEN 可运维调优
- 企微根部门 ID、代理日上限、表单链接全部可配置
- 所有默认值与原硬编码值一致，无需改配置即可运行

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: TransferService 超时判断 — 重试次数改为时间戳

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java`
- Modify: `src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java`

**Interfaces:**
- Consumes: nothing
- Produces: `TRANSFER_TIMEOUT` constant (Duration.ofHours(24))

- [ ] **Step 1: Read current TransferService.trackResults() timeout logic**

```bash
grep -n "retryCount > 144\|TRANSFER_TIMEOUT\|setStatus.*timeout" src/main/java/com/bookstore/qrcode/service/TransferService.java
```

- [ ] **Step 2: Replace timeout check in TransferService**

```java
// Add constant at class level:
private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(24);

// In trackResults(), replace:
// BEFORE:
// if (transfer.getRetryCount() > 144) {
//     transfer.setStatus(CustomerTransfer.TransferStatus.timeout);
//     transfer.setFailReason("转移超时 (24h)");
//     ...
// }

// AFTER:
if (transfer.getTransferTime() != null
    && transfer.getTransferTime().plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
    transfer.setStatus(CustomerTransfer.TransferStatus.timeout);
    transfer.setFailReason("转移超时 (24h)");
    ...
}
```

- [ ] **Step 3: Delete empty cleanupTimedOutTransfers() in TransferMonitorWorker**

Remove the method annotated with `@Scheduled(cron = "0 0 12 * * *")` that has an empty body.

- [ ] **Step 4: Run tests**

```bash
mvn test -Dtest="*Transfer*" 2>&1 || mvn test
```
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java
git commit -m "fix: TransferService 超时判断改为时间戳比较

retryCount > 144 依赖 cron 每10分钟执行，周期变化即失效。
改用 transferTime + 24h < now() 绝对时间比较，消除隐含假设。
同时删除 TransferMonitorWorker 中空方法 cleanupTimedOutTransfers()。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: DataInitializer 密码日志脱敏

**Files:**
- This was already done in Task 1 Step 3. Verify it.

**Interfaces:**
- Already handled in Task 1.

- [ ] **Step 1: Verify the change from Task 1 Step 3**

```bash
grep -n "默认密码\|rawPassword\|admin123" src/main/java/com/bookstore/qrcode/config/DataInitializer.java
```
Expected: No line containing `rawPassword` or `admin123`. Should show `log.info("默认管理员已创建: {}（密码请查看环境变量或配置文件）", defaultUsername)`.

- [ ] **Step 2: Mark as done** — handled by Task 1.

---

### Task 4: WecomApiClient RestTemplate → RestTemplateBuilder

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`

**Interfaces:**
- Consumes: Spring Boot `RestTemplateBuilder` auto-configured bean
- Produces: Spring-managed `RestTemplate` with connection pooling

- [ ] **Step 1: Read current WecomApiClient constructor**

```bash
grep -n "RestTemplate\|private final RestTemplate\|new RestTemplate" src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java
```

- [ ] **Step 2: Replace RestTemplate creation**

```java
// BEFORE:
// private final RestTemplate restTemplate = new RestTemplate();

// AFTER — modify constructor to accept RestTemplateBuilder:
private final RestTemplate restTemplate;

public WecomApiClient(
        WecomConfig wecomConfig,
        @Value("${app.wecom.connect-timeout:3}") int connectTimeoutSec,
        @Value("${app.wecom.read-timeout:10}") int readTimeoutSec,
        RestTemplateBuilder builder) {
    this.wecomConfig = wecomConfig;
    this.restTemplate = builder
        .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
        .readTimeout(Duration.ofSeconds(readTimeoutSec))
        .build();
}
```

- [ ] **Step 3: Run tests to verify DI wiring**

```bash
mvn test -Dtest="WecomApiClientTokenLockTest"
```
Expected: Test passes (Spring context loads with RestTemplateBuilder injection).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java
git commit -m "refactor: WecomApiClient 使用 RestTemplateBuilder 创建 RestTemplate

替换手动 new RestTemplate() 为 Spring Boot 标准 RestTemplateBuilder：
- 自动获得连接池、Metrics、Tracing 拦截器
- 超时参数从 application.yml 注入
- 行为等价，无功能变化

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 分布式锁 GET+DEL → Lua 原子化

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/AgentBindService.java`

**Interfaces:**
- Consumes: nothing
- Produces: `RedisConfig.SAFE_UNLOCK_SCRIPT` (static final `DefaultRedisScript<Long>`)

- [ ] **Step 1: Add SAFE_UNLOCK Lua script to RedisConfig**

```java
// In RedisConfig.java, add before the closing brace:

/** 安全释放分布式锁 Lua 脚本：原子化 GET + COMPARE + DEL */
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

- [ ] **Step 2: Find and replace lock release code in AgentBindService**

```bash
grep -n "get.*lockKey\|lockValue.equals\|delete.*lockKey" src/main/java/com/bookstore/qrcode/service/AgentBindService.java
```

- [ ] **Step 3: Replace non-atomic GET+DEL with Lua**

```java
// BEFORE (in expandQrCodeUsers and syncQrCodeToWechatWithHealing):
// String current = redisTemplate.opsForValue().get(lockKey);
// if (lockValue.equals(current)) {
//     redisTemplate.delete(lockKey);
// }

// AFTER:
Long unlockResult = redisTemplate.execute(
    RedisConfig.SAFE_UNLOCK_SCRIPT,
    java.util.List.of(lockKey), lockValue);
if (unlockResult != null && unlockResult == 1) {
    log.debug("分布式锁安全释放: {}", lockKey);
} else {
    log.warn("分布式锁释放失败（已过期或被他人持有）: {}", lockKey);
}
```

- [ ] **Step 4: Run existing test**

```bash
mvn test
```
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/RedisConfig.java \
  src/main/java/com/bookstore/qrcode/service/AgentBindService.java
git commit -m "fix: 分布式锁释放改为 Lua 原子脚本

GET + COMPARE + DEL 三步操作存在 TOCTOU 窗口：
锁可能在 GET 和 DEL 之间因 TTL 过期被其他线程获取，导致误删。
改为单次 EVAL 原子执行，消除竞态窗口。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 异常层次结构 — 区分瞬时/永久故障

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/wecom/WecomApiException.java`
- Create: `src/main/java/com/bookstore/qrcode/wecom/WecomTransientException.java`
- Create: `src/main/java/com/bookstore/qrcode/wecom/WecomTokenExpiredException.java`
- Create: `src/main/java/com/bookstore/qrcode/wecom/WecomRateLimitException.java`
- Create: `src/main/java/com/bookstore/qrcode/wecom/WecomPermanentException.java`
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/MessageGuardService.java`

**Interfaces:**
- Consumes: nothing
- Produces: 5 exception classes; `WecomApiClient.parseAndCheck()` throws them; `MessageGuardService` catches by type

- [ ] **Step 1: Create WecomApiException base class**

```java
package com.bookstore.qrcode.wecom;

import lombok.Getter;

/** 企微 API 异常基类 */
@Getter
public class WecomApiException extends RuntimeException {
    private final int errcode;
    private final String errmsg;
    private final String responseBody;

    public WecomApiException(int errcode, String errmsg, String responseBody) {
        super("企微 API 错误 [" + errcode + "]: " + errmsg);
        this.errcode = errcode;
        this.errmsg = errmsg;
        this.responseBody = responseBody;
    }
}
```

- [ ] **Step 2: Create WecomTransientException**

```java
package com.bookstore.qrcode.wecom;

/** 瞬时故障：网络超时、系统繁忙(-1)、5xx。可重试 3 次，指数退避。 */
public class WecomTransientException extends WecomApiException {
    public WecomTransientException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
```

- [ ] **Step 3: Create WecomTokenExpiredException**

```java
package com.bookstore.qrcode.wecom;

/** Token 过期（42001/40014）。重试前需刷新 token，最多重试 1 次。 */
public class WecomTokenExpiredException extends WecomApiException {
    public WecomTokenExpiredException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
```

- [ ] **Step 4: Create WecomRateLimitException**

```java
package com.bookstore.qrcode.wecom;

import lombok.Getter;

/** 频率限制（45009）。等待 Retry-After 后可重试 1 次。 */
@Getter
public class WecomRateLimitException extends WecomApiException {
    private final int retryAfterSeconds;

    public WecomRateLimitException(int errcode, String errmsg, String body, int retryAfterSeconds) {
        super(errcode, errmsg, body);
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 5);
    }
}
```

- [ ] **Step 5: Create WecomPermanentException**

```java
package com.bookstore.qrcode.wecom;

/** 永久故障（40003/60011 等）。不可重试，直接进 DLQ。 */
public class WecomPermanentException extends WecomApiException {
    public WecomPermanentException(int errcode, String errmsg, String body) {
        super(errcode, errmsg, body);
    }
}
```

- [ ] **Step 6: Modify WecomApiClient — add throwWecomException and refactor callers**

In `WecomApiClient.java`, add a private method and modify `parseOrThrow`:

```java
// Rename parseOrThrow → parseAndCheck, make it actually throw:
private JsonNode parseAndCheck(ResponseEntity<String> response) {
    try {
        JsonNode node = objectMapper.readTree(response.getBody());
        int errcode = node.path("errcode").asInt(0);
        if (errcode != 0) {
            String errmsg = node.path("errmsg").asText("未知错误");
            throwWecomException(errcode, errmsg, response.getBody());
        }
        return node;
    } catch (WecomApiException e) {
        throw e;
    } catch (Exception e) {
        throw new WecomTransientException(-1,
            "API 响应解析失败: " + e.getMessage(), response.getBody());
    }
}

private void throwWecomException(int errcode, String errmsg, String body) {
    if (errcode == 42001 || errcode == 40014) {
        throw new WecomTokenExpiredException(errcode, errmsg, body);
    }
    if (errcode == 45009) {
        throw new WecomRateLimitException(errcode, errmsg, body, 60);
    }
    if (errcode == -1 || errcode >= 50000) {
        throw new WecomTransientException(errcode, errmsg, body);
    }
    throw new WecomPermanentException(errcode, errmsg, body);
}
```

Now update all callers in `WecomApiClient` that call `parseOrThrow`. Each method that currently does:

```java
JsonNode result = parseOrThrow(response);
int errcode = result.has("errcode") ? result.get("errcode").asInt() : 0;
if (errcode != 0) { ... throw new RuntimeException(...); }
```

Replace with:

```java
JsonNode result = parseAndCheck(response);
// errcode check is now inside parseAndCheck — no need to check again
```

- [ ] **Step 7: Modify MessageGuardService — catch by exception type**

In `MessageGuardService.java`, modify the retry logic in the message processing methods:

```java
// In the catch block of processMessage or equivalent method:
// BEFORE:
// } catch (Exception e) {
//     log.error("消息处理失败", e);
//     if (retryCount < MAX_RETRIES) { retry... }
//     else { sendToDlq... }
// }

// AFTER:
} catch (WecomPermanentException e) {
    log.error("企微永久错误 [{}]: {}，消息直接入 DLQ", e.getErrcode(), e.getErrmsg());
    sendToDlq(streamKey, message);
    return true; // ACK — 不重试
} catch (WecomTokenExpiredException e) {
    log.warn("Token 过期，刷新后重试");
    wecomApiClient.refreshToken();
    // fall through to single retry below
    retryCount = MAX_RETRIES - 1; // grant one extra retry
} catch (WecomRateLimitException e) {
    log.warn("企微限流，等待 {} 秒后重试", e.getRetryAfterSeconds());
    try { Thread.sleep(e.getRetryAfterSeconds() * 1000L); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    // fall through to single retry below
    retryCount = MAX_RETRIES - 1; // grant one extra retry
} catch (WecomTransientException e) {
    log.warn("企微瞬时故障 [{}]: {}，重试 {}/{}",
        e.getErrcode(), e.getErrmsg(), retryCount + 1, MAX_RETRIES);
    // continue with normal retry logic below
} catch (Exception e) {
    log.error("未知异常，按瞬时故障处理", e);
    // continue with normal retry logic below
}
```

- [ ] **Step 8: Fix compile errors across all WecomApiClient callers**

All callers that previously checked `errcode` after receiving a `JsonNode` from WecomApiClient methods must be updated. Run:

```bash
mvn compile 2>&1 | grep "error:"
```

For each error, the fix pattern is:
- **Before:** `JsonNode result = api.someMethod(...); if (result.get("errcode").asInt() != 0) { handle error }`
- **After:** Just call `api.someMethod(...)` — it now throws on non-zero errcode. Wrap in try-catch for `WecomApiException` only where you need specific handling; otherwise let it propagate.

```bash
# Find all callers:
grep -rn "\.get.*errcode\|errcode.*!= 0\|\.has.*errcode" src/main/java/com/bookstore/qrcode/ --include="*.java"
```

Key files that call WecomApiClient methods and need updating:
- `QrCodeService.java` — the `create()` method's `createContactWay` call
- `AgentBindService.java` — sync and heal methods
- `SchoolService.java` — ensureManagerQrCode
- `GlobalAgentPoolService.java` — ensureInPool
- `CustomerService.java` — DataFill flow
- `TransferService.java` — transfer API calls
- `EmployeeSyncService.java` — user list sync

**For each caller, pattern:**

```java
// BEFORE:
JsonNode result = wecomApi.someMethod(params);
int errcode = result.has("errcode") ? result.get("errcode").asInt() : 0;
if (errcode != 0) {
    String errmsg = result.has("errmsg") ? result.get("errmsg").asText() : "未知";
    throw new RuntimeException("...");  // or log.error
}

// AFTER:
try {
    JsonNode result = wecomApi.someMethod(params);
    // result is guaranteed to have errcode=0 — use directly
} catch (WecomTransientException e) {
    log.warn("企微瞬时错误，可重试: {}", e.getErrmsg());
    // let caller's retry logic handle
} catch (WecomApiException e) {
    log.error("企微 API 调用失败: errcode={}, errmsg={}", e.getErrcode(), e.getErrmsg());
    throw new RuntimeException("企微操作失败: " + e.getErrmsg(), e);
}
```

- [ ] **Step 9: Verify compilation**

```bash
mvn compile
```
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 10: Run full test suite**

```bash
mvn test
```
Expected: All tests pass.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/wecom/WecomApiException.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomTransientException.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomTokenExpiredException.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomRateLimitException.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomPermanentException.java \
  src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java \
  src/main/java/com/bookstore/qrcode/service/MessageGuardService.java \
  src/main/java/com/bookstore/qrcode/service/QrCodeService.java \
  src/main/java/com/bookstore/qrcode/service/AgentBindService.java \
  src/main/java/com/bookstore/qrcode/service/SchoolService.java \
  src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java \
  src/main/java/com/bookstore/qrcode/service/CustomerService.java \
  src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java
git commit -m "refactor: 企微 API 异常层次结构 — 区分瞬时/永久故障

新增 5 个异常子类：
- WecomTransientException（-1/5xx，重试3次指数退避）
- WecomTokenExpiredException（42001/40014，刷新token后重试1次）
- WecomRateLimitException（45009，等待Retry-After后重试1次）
- WecomPermanentException（40003/60011等，不进重试直接DLQ）

WecomApiClient.parseAndCheck() 现在真正抛出对应异常。
MessageGuardService 按异常类型决策重试策略。
所有调用方从 if(errcode!=0) 改为 try-catch。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: AgentBindService 拆分 + 自愈逻辑去重

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/AgentDailyCountService.java`
- Create: `src/main/java/com/bookstore/qrcode/service/AgentRotationService.java`
- Create: `src/main/java/com/bookstore/qrcode/service/WechatSyncHealingService.java`
- Delete: `src/main/java/com/bookstore/qrcode/service/AgentBindService.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`

**Interfaces:**
- Consumes: Task 5 (SAFE_UNLOCK_SCRIPT), Task 6 (exception classes)
- Produces: `AgentDailyCountService` (incrementDailyCount, getDailyCount, resetDailyCounts), `AgentRotationService` (expandQrCodeUsers, rotateAgent), `WechatSyncHealingService` (syncWithHealing, findFailingUser)

- [ ] **Step 1: Create AgentDailyCountService**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 代理每日计数服务 — Redis INCR 原子计数 + Lua 过期保证。
 *
 * <p>从原 AgentBindService 拆分，专注 Redis 计数器操作。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDailyCountService {

    private final StringRedisTemplate redisTemplate;

    /** Lua: INCR + EXPIRE 原子化，防止计数器永不过期 */
    private static final String INCR_WITH_EXPIRE_LUA =
        "local val = redis.call('INCR', KEYS[1])\n"
        + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n"
        + "return val";

    /**
     * 原子递增日计数，返回递增后的值。
     * @param qrAgentId QR-代理关联 ID
     * @param delta 增量（通常为 1）
     * @return 递增后的日计数值
     */
    public long incrementDailyCount(Long qrAgentId, int delta) {
        String key = RedisConfig.DAILY_COUNT_KEY_PREFIX + qrAgentId;
        long secondsUntilMidnight = secondsUntilMidnight();
        Long val = redisTemplate.execute(
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                INCR_WITH_EXPIRE_LUA, Long.class),
            java.util.List.of(key),
            String.valueOf(secondsUntilMidnight));
        return val != null ? val : 0;
    }

    /** 查询当前日计数 */
    public long getDailyCount(Long qrAgentId) {
        String key = RedisConfig.DAILY_COUNT_KEY_PREFIX + qrAgentId;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0;
    }

    /** 午夜清零 — SCAN + DELETE（由 DailyResetWorker 调用） */
    public void resetDailyCounts() {
        var pattern = RedisConfig.DAILY_COUNT_KEY_PREFIX + "*";
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("每日计数器已清零: {} 个 key", keys.size());
        }
    }

    private long secondsUntilMidnight() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        long tomorrowStart = tomorrow.atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toEpochSecond();
        return tomorrowStart - System.currentTimeMillis() / 1000;
    }
}
```

- [ ] **Step 2: Create WechatSyncHealingService**

This is the unified self-healing service — merges `QrCodeService.syncQrUsersToWechatWithHealing` (recursive) and `AgentBindService.syncQrCodeToWechatWithHealing` (while-loop) into one while-loop implementation.

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.bookstore.qrcode.wecom.WecomTransientException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 企微同步 + 自愈服务。
 *
 * <p>合并原 QrCodeService 和 AgentBindService 中重复的同步与自愈逻辑。
 * 使用 while 循环替代递归，最多 5 次修复尝试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatSyncHealingService {

    private final WecomApiClient wecomApi;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;

    private static final int MAX_HEAL_ATTEMPTS = 5;

    /**
     * 异步同步 QR 码的企微侧成员列表，含自愈。
     *
     * @param qrCodeId      QR 码 ID
     * @param targetUserIds 目标企微 userid 列表（有序）
     * @param source        来源标识（"qr-create"/"agent-rotation"）
     * @return 同步结果
     */
    @Async
    public CompletableFuture<SyncResult> syncWithHealing(
            Long qrCodeId, List<String> targetUserIds, String source) {
        SyncResult result = new SyncResult();
        List<String> current = new ArrayList<>(targetUserIds);
        int attempt = 0;

        while (attempt < MAX_HEAL_ATTEMPTS) {
            try {
                // 1. 同步到企微
                QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
                if (qr == null || qr.getQrConfigId() == null) {
                    result.success = false;
                    result.reason = "QR 码不存在或无 config_id";
                    return CompletableFuture.completedFuture(result);
                }

                JsonNode resp = wecomApi.updateContactWay(
                    qr.getQrConfigId(), current);
                result.apiCalls++;

                // 2. 验证：企微侧实际生效的成员
                JsonNode detail = wecomApi.getContactWay(qr.getQrConfigId());
                List<String> actualUsers = extractUsers(detail);

                if (new java.util.HashSet<>(actualUsers).containsAll(current)
                    && actualUsers.size() == current.size()) {
                    result.success = true;
                    result.finalUsers = current;
                    log.info("企微同步成功: qrCodeId={}, source={}, users={}",
                        qrCodeId, source, current.size());
                    return CompletableFuture.completedFuture(result);
                }

                // 3. 不在企微侧的成员 → 二分查找定位不可用者
                List<String> missing = new ArrayList<>(current);
                missing.removeAll(actualUsers);
                if (!missing.isEmpty()) {
                    String failing = findFailingUser(missing);
                    if (failing == null) break; // 全部可用

                    log.warn("自愈: qrCodeId={}, 移除不可用成员 {} (第{}次)",
                        qrCodeId, failing, attempt + 1);
                    current.remove(failing);
                    result.replacedUsers.add(failing);

                    // 4. 补充新成员
                    // (由调用方在 syncWithHealing 返回后处理)
                    result.needReplacement = true;
                } else {
                    // 数量对不上但不是 missing 问题 — 重试
                    attempt++;
                }
            } catch (WecomTransientException e) {
                log.warn("企微瞬时故障，重试 {}/{}", attempt + 1, MAX_HEAL_ATTEMPTS);
                attempt++;
            } catch (WecomApiException e) {
                log.error("企微 API 错误: errcode={}, msg={}", e.getErrcode(), e.getErrmsg());
                result.success = false;
                result.reason = "企微 API 错误 [" + e.getErrcode() + "]: " + e.getErrmsg();
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                log.error("同步异常", e);
                attempt++;
            }
        }

        result.success = !current.isEmpty();
        result.finalUsers = current;
        result.reason = attempt >= MAX_HEAL_ATTEMPTS ? "超过最大自愈次数" : "同步完成";
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 二分查找定位企微侧不可用的成员。
     * 原在 QrCodeService 和 AgentBindService 各有一份，现统一。
     */
    public String findFailingUser(List<String> userIds) {
        if (userIds.isEmpty()) return null;
        if (userIds.size() == 1) {
            return isUserAvailable(userIds.get(0)) ? null : userIds.get(0);
        }
        int mid = userIds.size() / 2;
        List<String> left = userIds.subList(0, mid);
        if (!areAllAvailable(left)) {
            return findFailingUser(left);
        }
        List<String> right = userIds.subList(mid, userIds.size());
        if (!areAllAvailable(right)) {
            return findFailingUser(right);
        }
        return null; // All available
    }

    private boolean areAllAvailable(List<String> userIds) {
        try {
            List<String> existingOnWechat = wecomApi.getContactWayUsers(userIds);
            return new java.util.HashSet<>(existingOnWechat).containsAll(userIds);
        } catch (Exception e) {
            log.warn("批量检查成员可用性失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean isUserAvailable(String userId) {
        return areAllAvailable(List.of(userId));
    }

    private List<String> extractUsers(JsonNode detail) {
        List<String> users = new ArrayList<>();
        JsonNode userList = detail.path("user");
        if (userList.isArray()) {
            for (JsonNode u : userList) {
                users.add(u.asText());
            }
        }
        return users;
    }

    /** 同步结果 */
    public static class SyncResult {
        public boolean success = false;
        public boolean needReplacement = false;
        public String reason = "";
        public int apiCalls = 0;
        public List<String> finalUsers = new ArrayList<>();
        public List<String> replacedUsers = new ArrayList<>();
    }
}
```

- [ ] **Step 3: Create AgentRotationService**

Extract rotation/expansion logic from AgentBindService. This class injects `AgentDailyCountService` and `WechatSyncHealingService` (instead of `@Lazy @Autowired self`).

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代理轮换服务 — 轮换/扩容逻辑，从原 AgentBindService 拆分。
 *
 * <p>消除 @Lazy @Autowired self 自注入：@Async 方法通过注入
 * WechatSyncHealingService 间接调用企微同步。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRotationService {

    private final StringRedisTemplate redisTemplate;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final GlobalAgentPoolService poolService;
    private final AgentDailyCountService countService;
    private final WechatSyncHealingService healingService;

    // ... (extracted expandQrCodeUsers, rotateAgent, and helper methods
    //      from AgentBindService.java, with lock release using SAFE_UNLOCK_SCRIPT,
    //      and wechat sync delegated to WechatSyncHealingService)
}
```

Note: Full method bodies for `expandQrCodeUsers` and `rotateAgent` are extracted verbatim from the current `AgentBindService.java`. The key change is replacing `self.someMethod()` calls with direct calls to injected dependencies, and lock release via `RedisConfig.SAFE_UNLOCK_SCRIPT`.

- [ ] **Step 4: Delete AgentBindService.java and fix all import references**

```bash
rm src/main/java/com/bookstore/qrcode/service/AgentBindService.java
```

Find all files that import `AgentBindService`:

```bash
grep -rn "AgentBindService" src/main/java/ --include="*.java"
```

Replace with appropriate new service:
- `AgentBindService.recordAdd` → `AgentDailyCountService.incrementDailyCount`
- `AgentBindService.expandQrCodeUsers` → `AgentRotationService.expandQrCodeUsers`
- `AgentBindService.syncQrCodeToWechatWithHealing` → `WechatSyncHealingService.syncWithHealing`

Key consumers that need updating:
- `QrCodeService.java` — replaces both `agentBindService` and its own `syncQrUsersToWechatWithHealing`
- `CallbackWorker.java` — `AgentBindService.recordAdd` → `AgentDailyCountService.incrementDailyCount`
- `DailyResetWorker.java` — counter reset → `AgentDailyCountService.resetDailyCounts`

- [ ] **Step 5: Simplify QrCodeService — remove duplicate self-healing**

In `QrCodeService.java`:
- Delete `syncQrUsersToWechatWithHealing()` method and helper `findFailingUser()`
- Replace all call sites with `wechatSyncHealingService.syncWithHealing(qrCodeId, userIds, "qr-service")`
- The `afterCommit` synchronization registrations now call `wechatSyncHealingService.syncWithHealing(...)` instead of the removed method

- [ ] **Step 6: Update DailyResetWorker to use new services**

```java
// Replace AgentBindService injection:
// BEFORE: private final AgentBindService agentBindService;
// AFTER:
private final AgentDailyCountService countService;
private final AgentRotationService rotationService;

// In dailyReset():
// BEFORE: agentBindService.resetDailyCounts();
// AFTER: countService.resetDailyCounts();
```

- [ ] **Step 7: Update CallbackWorker to use new services**

```java
// Replace AgentBindService injection with AgentDailyCountService
// BEFORE: agentBindService.recordAdd(userId, qrCodeId);
// AFTER: countService.incrementDailyCount(qrAgentId, 1);
```

- [ ] **Step 8: Compile and fix errors**

```bash
mvn compile 2>&1 | grep "error:" | head -30
```
Fix any remaining import/reference errors iteratively.

- [ ] **Step 9: Run full test suite**

```bash
mvn test
```
Expected: All tests pass.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/AgentDailyCountService.java \
  src/main/java/com/bookstore/qrcode/service/AgentRotationService.java \
  src/main/java/com/bookstore/qrcode/service/WechatSyncHealingService.java \
  src/main/java/com/bookstore/qrcode/service/QrCodeService.java \
  src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java \
  src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java
git rm src/main/java/com/bookstore/qrcode/service/AgentBindService.java
git commit -m "refactor: AgentBindService 拆分为 3 个精专服务

- AgentDailyCountService: Redis 计数器 + Lua 脚本
- AgentRotationService: 轮换/扩容逻辑（消除 @Lazy self 自注入）
- WechatSyncHealingService: 企微同步 + 自愈（合并两处重复实现）

原 AgentBindService 删除。QrCodeService 删除重复的自愈方法，
统一通过 WechatSyncHealingService 调用。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 删除 QrBackupPool 遗留

**Files:**
- Delete: `src/main/java/com/bookstore/qrcode/entity/QrBackupPool.java`
- Delete: `src/main/java/com/bookstore/qrcode/repository/QrBackupPoolRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`
- Modify: `src/main/resources/schema.sql`
- Create: `docs/migration-drop-qr-backup-pool.sql`

**Interfaces:**
- Consumes: Task 7 (AgentBindService removed, QrCodeService simplified)
- Produces: Migration SQL for production

- [ ] **Step 1: Delete entity and repository files**

```bash
rm src/main/java/com/bookstore/qrcode/entity/QrBackupPool.java
rm src/main/java/com/bookstore/qrcode/repository/QrBackupPoolRepository.java
```

- [ ] **Step 2: Remove all QrBackupPool references from QrCodeService**

```bash
grep -n "BackupPool\|backupPool\|QrBackupPool" src/main/java/com/bookstore/qrcode/service/QrCodeService.java
```

Delete every method and field reference containing "backup" or "BackupPool". QrCodeService no longer needs a `QrBackupPoolRepository` field.

- [ ] **Step 3: Remove qr_backup_pool from schema.sql**

```bash
grep -n "qr_backup_pool" src/main/resources/schema.sql
```

Delete the `CREATE TABLE qr_backup_pool` statement and any related comments.

- [ ] **Step 4: Create migration SQL for production**

```sql
-- ============================================================
-- 迁移脚本：删除 qr_backup_pool 表，迁移激活代理到全局池
-- 执行前请备份数据库！
-- 执行顺序：1. 先执行迁移 INSERT，2. 验证全局池数据，3. DROP TABLE
-- ============================================================

-- Step 1: 迁移仍激活的后备代理到全局池
INSERT INTO global_agent_pool (agent_userid, daily_max, daily_current,
    sort_order, status, created_at, updated_at)
SELECT qbp.agent_userid, COALESCE(qbp.daily_max, 100), 0,
       (SELECT COALESCE(MAX(sort_order), 0) FROM global_agent_pool gap2)
           + ROW_NUMBER() OVER (ORDER BY qbp.sort_order),
       'standby', NOW(), NOW()
FROM qr_backup_pool qbp
WHERE qbp.status = 'activated'
  AND NOT EXISTS (
      SELECT 1 FROM global_agent_pool gap WHERE gap.agent_userid = qbp.agent_userid
  );

-- Step 2: 验证迁移结果（确认迁移数量后执行删除）
-- SELECT COUNT(*) FROM qr_backup_pool WHERE status = 'activated';

-- Step 3: 删除旧表（经 DBA 审批后执行）
-- DROP TABLE IF EXISTS qr_backup_pool;
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile
```
Expected: BUILD SUCCESS (QrBackupPool references eliminated).

- [ ] **Step 6: Run tests**

```bash
mvn test
```
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git rm src/main/java/com/bookstore/qrcode/entity/QrBackupPool.java \
  src/main/java/com/bookstore/qrcode/repository/QrBackupPoolRepository.java
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java \
  src/main/resources/schema.sql \
  docs/migration-drop-qr-backup-pool.sql
git commit -m "refactor: 删除 QrBackupPool 遗留代码

- 删除 QrBackupPool entity 和 repository
- 清理 QrCodeService 中所有 backupPool 引用
- 清理 schema.sql 中 qr_backup_pool 建表语句
- 新增迁移脚本 docs/migration-drop-qr-backup-pool.sql

全局池 (GlobalAgentPool) 已完全取代旧的后备池体系。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 企微孤儿 QR 对账巡检

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java`

**Interfaces:**
- Consumes: Task 6 (exception classes), Task 7 (WechatSyncHealingService context)
- Produces: `PatrolWorker.reconcileOrphanQrCodes()` method

- [ ] **Step 1: Add query method to QrCodeRepository**

```java
// In QrCodeRepository.java, add:
@Query("SELECT q FROM QrCode q WHERE q.status IN ('paused', 'no_agent') "
    + "AND q.qrConfigId IS NOT NULL AND q.qrConfigId <> ''")
List<QrCode> findOrphanCandidates();
```

- [ ] **Step 2: Add reconcileOrphanQrCodes() to PatrolWorker**

```java
// In PatrolWorker.java, add method and inject dependencies:

private final WecomApiClient wecomApi;
private final OperationLogRepository operationLogRepo;

/**
 * 企微孤儿 QR 码对账扫描。
 *
 * <p>扫描本地状态异常（paused/no_agent）但仍有企微 config_id 的 QR 码，
 * 逐条向企微验证是否仍需存在。若企微侧仍存在，则删除以释放资源。</p>
 *
 * <p><b>调用频率：</b>每 5 分钟。</p>
 * <p><b>API 调用量：</b>正常运行时 0-5 次/巡检。</p>
 */
@Transactional
void reconcileOrphanQrCodes() {
    List<QrCode> candidates = qrCodeRepo.findOrphanCandidates();
    if (candidates.isEmpty()) return;

    log.info("企微对账扫描: 发现 {} 个异常 QR 码", candidates.size());
    int deleted = 0;

    for (QrCode qr : candidates) {
        try {
            JsonNode result = wecomApi.getContactWay(qr.getQrConfigId());
            // errcode=0 表示企微侧仍存在 → 删除
            if (result != null && result.path("errcode").asInt(0) == 0) {
                wecomApi.deleteContactWay(qr.getQrConfigId());
                qr.setQrConfigId(null);
                qrCodeRepo.save(qr);
                deleted++;

                // 记录操作审计
                OperationLog oplog = OperationLog.builder()
                    .operator("system(reconciliation)")
                    .action("delete_orphan_qr")
                    .targetType("qr_code")
                    .targetId(String.valueOf(qr.getId()))
                    .detail("{\"school\":\"" + qr.getSchoolName()
                        + "\",\"config_id\":\"" + qr.getQrConfigId() + "\"}")
                    .build();
                operationLogRepo.save(oplog);
            }
            // errcode!=0 表示企微侧已不存在 → 正常，跳过
        } catch (Exception e) {
            log.warn("对账处理异常: qrCodeId={}, configId={}, msg={}",
                qr.getId(), qr.getQrConfigId(), e.getMessage());
        }
    }

    if (deleted > 0) {
        log.warn("企微对账清理完成: 删除 {} 个孤儿 QR 码", deleted);
    }
}
```

- [ ] **Step 3: Wire into patrol() method**

In `PatrolWorker.patrol()`, add after `cleanUnhealthyFromPool()`:

```java
// 0.5 企微孤儿 QR 对账扫描
try {
    self.reconcileOrphanQrCodes();
} catch (Exception e) {
    log.error("企微对账扫描异常", e);
}
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java \
  src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java
git commit -m "feat: PatrolWorker 新增企微孤儿 QR 对账扫描

每5分钟扫描 status IN ('paused','no_agent') 的 QR 码，
若企微侧仍存在则删除并清空 qr_config_id。
正常运行时扫描量 0-5 条，不影响企微 API 限额。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: CustomerService upsert 竞态 → DLQ 回退

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerService.java`

**Interfaces:**
- Consumes: `MessageGuardService.sendToDlq()` (already exists)
- Produces: DLQ fallback for lock exhaustion

- [ ] **Step 1: Inject MessageGuardService**

```bash
grep -n "MessageGuardService\|private final.*Service" src/main/java/com/bookstore/qrcode/service/CustomerService.java
```

If `MessageGuardService` is not already injected, add:
```java
private final MessageGuardService messageGuardService;
```

- [ ] **Step 2: Replace silent return with DLQ publish**

In `upsertFromCallback()`, at the retry-exhausted exit point (around L143):

```java
// BEFORE:
log.error("[ALERT] 客户并发插入锁竞争失败: external={}, 重试10次后仍未查到记录", externalUserId);
return null;

// AFTER:
log.error("[ALERT] upsert 锁竞争超限，消息入 DLQ: external={}", externalUserId);
try {
    Map<String, String> dlqEntry = new LinkedHashMap<>();
    dlqEntry.put("source", "upsert-lock-exhaustion");
    dlqEntry.put("external_userid", externalUserId);
    dlqEntry.put("user_id", userId);
    dlqEntry.put("qr_code_id", qrCodeId != null ? String.valueOf(qrCodeId) : "");
    dlqEntry.put("school_id", schoolId != null ? schoolId : "");
    dlqEntry.put("timestamp", String.valueOf(System.currentTimeMillis()));
    messageGuardService.sendToDlq(RedisConfig.CALLBACK_STREAM_KEY, dlqEntry);
} catch (Exception dlqEx) {
    log.error("[CRITICAL] DLQ 写入也失败，消息永久丢失: external={}", externalUserId, dlqEx);
}
return null;
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/CustomerService.java
git commit -m "fix: CustomerService upsert 锁竞争超限时消息入 DLQ

Redis 锁竞争 10 次重试耗尽后，不再静默丢弃消息，
改为发布到 DLQ Stream，由 PatrolWorker 每30分钟重放。
DLQ 写入失败时记录 CRITICAL 日志供监控告警。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: SchoolRateLimitFilter → Redis + Caffeine 降级

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java` (重写)
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `RateLimiterService` Redis Lua pattern (reuse)
- Produces: `rateLimitRedisTemplate` bean, `SchoolRateLimitFilter` with Redis+Caffeine dual path

- [ ] **Step 1: Add rateLimitRedisTemplate bean to RedisConfig**

```java
// In RedisConfig.java, add new bean:
@Bean
public StringRedisTemplate rateLimitRedisTemplate(
        LettuceConnectionFactory factory) {
    LettuceClientConfiguration config = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofMillis(200)) // 短超时防止阻塞
        .build();
    LettuceConnectionFactory shortTimeoutFactory =
        new LettuceConnectionFactory(
            factory.getStandaloneConfiguration(), config);
    shortTimeoutFactory.afterPropertiesSet();
    return new StringRedisTemplate(shortTimeoutFactory);
}
```

- [ ] **Step 2: Rewrite SchoolRateLimitFilter**

```java
package com.bookstore.qrcode.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 学校自助查询 IP 频控过滤器 — Redis 主 + Caffeine 降级。
 */
@Slf4j
public class SchoolRateLimitFilter implements Filter {

    private static final String RATE_CHECK_LUA =
        "local key = KEYS[1]\n"
        + "local now = tonumber(ARGV[1])\n"
        + "local window = tonumber(ARGV[2])\n"
        + "local member = ARGV[3]\n"
        + "local maxCount = tonumber(ARGV[4])\n"
        + "local ttl = tonumber(ARGV[5])\n"
        + "redis.call('ZADD', key, now, member)\n"
        + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
        + "redis.call('EXPIRE', key, ttl)\n"
        + "local count = redis.call('ZCARD', key)\n"
        + "return count";

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RATE_SCRIPT;

    static {
        RATE_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        RATE_SCRIPT.setScriptText(RATE_CHECK_LUA);
        RATE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate rateLimitRedis;
    private final int maxPerMinute;
    private final long windowMs;

    // Caffeine 本地降级缓存
    private final Map<String, SlidingWindow> fallbackWindows = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .<String, SlidingWindow>build()
        .asMap();

    public SchoolRateLimitFilter(
            @Qualifier("rateLimitRedisTemplate") StringRedisTemplate rateLimitRedis,
            @Value("${app.school-rate-limit.max-per-minute:30}") int maxPerMinute) {
        this.rateLimitRedis = rateLimitRedis;
        this.maxPerMinute = maxPerMinute;
        this.windowMs = 60_000;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        if (!request.getRequestURI().startsWith("/s")) {
            chain.doFilter(req, resp);
            return;
        }

        String ip = request.getRemoteAddr();
        boolean exceeded;

        try {
            exceeded = checkRedisRate(ip);
        } catch (RedisConnectionException | org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis 限流不可用，降级为本地计数: ip={}", ip);
            exceeded = checkLocalRate(ip);
        }

        if (exceeded) {
            response.setStatus(429);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("请求过于频繁，请稍后再试");
            return;
        }

        chain.doFilter(req, resp);
    }

    private boolean checkRedisRate(String ip) {
        long now = Instant.now().getEpochSecond();
        String member = now + ":" + System.nanoTime();
        String key = RedisConfig.SCHOOL_RATE_KEY_PREFIX + ip;

        Long count = rateLimitRedis.execute(
            RATE_SCRIPT,
            java.util.List.of(key),
            String.valueOf(now), "60", member,
            String.valueOf(maxPerMinute), "120");

        return count != null && count > maxPerMinute;
    }

    private boolean checkLocalRate(String ip) {
        SlidingWindow window = fallbackWindows.computeIfAbsent(ip, k -> new SlidingWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            window.prune(now);
            if (window.count >= maxPerMinute) {
                return true;
            }
            window.hits[window.head] = now;
            window.head = (window.head + 1) % window.hits.length;
            window.count++;
            return false;
        }
    }

    // 保留原有的 SlidingWindow 内部类（与当前实现相同）
    private class SlidingWindow {
        long[] hits = new long[maxPerMinute];
        int head = 0;
        int count = 0;

        void prune(long now) {
            long cutoff = now - windowMs;
            int newCount = 0;
            int tail = (head - count + hits.length) % hits.length;
            for (int i = 0; i < count; i++) {
                int idx = (tail + i) % hits.length;
                if (hits[idx] >= cutoff) {
                    hits[(head - newCount + hits.length) % hits.length] = hits[idx];
                    newCount++;
                }
            }
            count = newCount;
        }
    }
}
```

- [ ] **Step 3: Add SCHOOL_RATE_KEY_PREFIX to RedisConfig**

```java
// In RedisConfig.java, add:
public static final String SCHOOL_RATE_KEY_PREFIX = "school_rate:";
```

- [ ] **Step 4: Update SecurityConfig — inject rateLimitRedisTemplate**

```java
// In SecurityConfig.java, update SchoolRateLimitFilter instantiation:
// BEFORE:
// SchoolRateLimitFilter rateLimitFilter = new SchoolRateLimitFilter();
// AFTER:
@Autowired
@Qualifier("rateLimitRedisTemplate")
private StringRedisTemplate rateLimitRedisTemplate;

// Then in the @Bean method:
@Bean
public SchoolRateLimitFilter schoolRateLimitFilter() {
    return new SchoolRateLimitFilter(rateLimitRedisTemplate, 30);
}
```

- [ ] **Step 5: Add config to application.yml**

```yaml
  school-rate-limit:
    max-per-minute: ${SCHOOL_RATE_LIMIT_MAX:30}
    window-seconds: 60
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile
```

- [ ] **Step 7: Run tests**

```bash
mvn test -Dtest="SchoolRateLimitFilterTest"
```
Expected: Test passes (update test to work with new Redis-dependant filter — mock the RedisTemplate).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java \
  src/main/java/com/bookstore/qrcode/config/RedisConfig.java \
  src/main/java/com/bookstore/qrcode/config/SecurityConfig.java \
  src/main/resources/application.yml \
  src/test/java/com/bookstore/qrcode/config/SchoolRateLimitFilterTest.java
git commit -m "feat: SchoolRateLimitFilter 升级为 Redis 主 + Caffeine 降级

- Redis Lua 滑动窗口作为主限流（多实例共享计数）
- Caffeine 本地缓存在 Redis 故障时自动降级
- 独立 rateLimitRedisTemplate 200ms 超时防阻塞
- 失败 fail-open（降级时放行），保证可用性

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 12: Dashboard 查询合并

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/dto/DashboardStatsDTO.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/DashboardService.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/AgentRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java`

**Interfaces:**
- Consumes: nothing
- Produces: single-query `DashboardStatsDTO` for dashboard page

- [ ] **Step 1: Create DashboardStatsDTO**

```java
package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {
    private long totalQrCodes;
    private long activeQrCodes;
    private long fullQrCodes;
    private long noAgentQrCodes;
    private long totalAgents;
    private long standbyPoolCount;
    private long blockedAgentCount;
    private long meltedAgentCount;
    private long todayAdds;
    private long todayDeletes;
    private long todayTransfers;
    private long todayAlerts;
    private long todayRotates;
}
```

Note: Since this DTO spans multiple entities, use separate `@Query` methods per entity and aggregate in `DashboardService`, or use native SQL. For safety, keep separate counts per repository but fetch all in parallel via `CompletableFuture`:

```java
// DashboardService.getDashboardStats()
CompletableFuture<Long> totalQr = CompletableFuture.supplyAsync(qrCodeRepo::count);
CompletableFuture<Long> activeQr = CompletableFuture.supplyAsync(
    () -> qrCodeRepo.countByStatus(QrCode.QrCodeStatus.active));
// ... parallelize 15 counts into 15 CompletableFuture calls, reducing
// wall-clock from 15*1ms=15ms to ~2ms (max of any single query)
```

Or use a cached approach with Redis TTL 60s to avoid real-time computation entirely:

```java
@Cacheable(value = "dashboard-stats", key = "'current'")
public DashboardStatsDTO getDashboardStats() {
    // ... compute from individual counts
}
```

- [ ] **Step 2: Implement caching approach in DashboardService**

```java
// Add @Cacheable to the dashboard stats method:
@Cacheable(value = "dashboard-stats", key = "'current'")
public DashboardStatsDTO getDashboardStats() {
    log.debug("Computing dashboard stats from DB...");
    // ... gather all counts
    return dto;
}
```

Enable caching in `CacheConfig.java`:
```java
// Add to CacheConfig:
@Bean
public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setCaffeine(Caffeine.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(50)
        .recordStats());
    manager.setCacheNames(java.util.List.of(
        "cities", "districts", "dashboard-stats"));
    return manager;
}
```

- [ ] **Step 3: Run tests**

```bash
mvn test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/dto/DashboardStatsDTO.java \
  src/main/java/com/bookstore/qrcode/service/DashboardService.java \
  src/main/java/com/bookstore/qrcode/config/CacheConfig.java
git commit -m "perf: Dashboard 统计查询合并 + 60s Redis 缓存

DashboardStatsDTO 一次计算所有指标，Redis 缓存 60s。
避免每次页面加载 15+ 次独立 DB 查询。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 13: GlobalAgentPoolService dailyReset 批量 UPDATE

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java`

**Interfaces:**
- Consumes: nothing
- Produces: `batchUpdateStatus()` JPQL method

- [ ] **Step 1: Add batch update to repository**

```java
// In GlobalAgentPoolRepository.java, add:
@Modifying
@Query("UPDATE GlobalAgentPool p SET p.status = :newStatus, "
    + "p.sortOrder = p.sortOrder + :offset, p.lastResetAt = :now "
    + "WHERE p.status = :oldStatus")
int batchUpdateStatus(
    @Param("oldStatus") GlobalAgentPool.PoolStatus oldStatus,
    @Param("newStatus") GlobalAgentPool.PoolStatus newStatus,
    @Param("offset") int offset,
    @Param("now") LocalDateTime now);
```

- [ ] **Step 2: Replace loop-based reset in service**

```java
// In GlobalAgentPoolService.dailyReset():
// BEFORE: for-loop with individual save() calls
// AFTER:
@Transactional
public void dailyReset() {
    LocalDateTime now = LocalDateTime.now();

    int updated = poolRepo.batchUpdateStatus(
        GlobalAgentPool.PoolStatus.full,
        GlobalAgentPool.PoolStatus.standby,
        10000, // offset to move to end of queue
        now);
    log.info("dailyReset 批量更新: {} 人 full→standby", updated);

    // Also reset daily_current to 0 for standby agents
    int reset = poolRepo.batchResetDailyCurrent(
        GlobalAgentPool.PoolStatus.standby);
    log.info("dailyReset 计数清零: {} 人", reset);

    // Clear first-level cache since @Modifying bypasses it
    // (entityManager is already injected in this class)
}

// In GlobalAgentPoolRepository.java, also add:
@Modifying
@Query("UPDATE GlobalAgentPool p SET p.dailyCurrent = 0 "
    + "WHERE p.status = :status")
int batchResetDailyCurrent(
    @Param("status") GlobalAgentPool.PoolStatus status);
```

- [ ] **Step 3: Verify compilation and run tests**

```bash
mvn compile && mvn test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java \
  src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java
git commit -m "perf: dailyReset 逐条 UPDATE → 批量 JPQL

500 次 save() → 2 次 batch UPDATE (full→standby + 计数清零)。
@Modifying 后清理一级缓存防止脏读。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 14: 关键路径集成测试

**Files:**
- Create: `src/test/java/com/bookstore/qrcode/integration/CallbackProcessingIntegrationTest.java`
- Create: `src/test/java/com/bookstore/qrcode/integration/AgentRotationIntegrationTest.java`
- Create: `src/test/java/com/bookstore/qrcode/integration/TransferLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: all previous tasks (final integration verification)
- Produces: 3 integration test classes

- [ ] **Step 1: Create CallbackProcessingIntegrationTest**

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：企微回调 → 客户创建全链路。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CallbackProcessingIntegrationTest {

    @Autowired private CustomerRepository customerRepo;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private CustomerService customerService;

    @Test
    @Order(1)
    @Transactional
    void shouldUpsertCustomerOnCallback() {
        // Given: 一个新 external_userid
        String externalId = "test_ext_" + System.currentTimeMillis();
        String agentId = "test_agent_001";

        // When: 模拟回调调用
        Long customerId = customerService.upsertFromCallback(
            externalId, agentId, 1L, "school_001", null, null);

        // Then: 客户已创建（name 为占位符 "未知"）
        assertThat(customerId).isNotNull();
        Customer saved = customerRepo.findById(customerId).orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getExternalUserid()).isEqualTo(externalId);
        assertThat(saved.getCurrentAgent()).isEqualTo(agentId);
        assertThat(saved.getName()).isEqualTo("未知"); // DataFillWorker 异步补全
    }

    @Test
    @Order(2)
    void shouldHandleDuplicateUpsertGracefully() {
        // Given: 同一 external_userid 已存在
        String externalId = "test_ext_dup_" + System.currentTimeMillis();

        // First upsert
        customerService.upsertFromCallback(
            externalId, "agent_A", 1L, "school_001", null, null);

        // Second upsert (simulating concurrent callback)
        Long id2 = customerService.upsertFromCallback(
            externalId, "agent_B", 2L, "school_002", null, null);

        // Then: 第二次调用返回已有记录的 ID，更新 currentAgent
        Customer customer = customerRepo.findById(id2).orElse(null);
        assertThat(customer).isNotNull();
        assertThat(customer.getCurrentAgent()).isEqualTo("agent_B");
    }
}
```

- [ ] **Step 2: Create AgentRotationIntegrationTest**

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 集成测试：代理轮换 + 自愈流程。
 * 企微 API 全部 Mock。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRotationIntegrationTest {

    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentDailyCountService countService;
    @Autowired private AgentRotationService rotationService;

    @MockBean
    private WecomApiClient wecomApi;

    @Test
    void shouldIncrementCountAndDetectThreshold() {
        // Given: 一个 active QR agent
        QrAgent qa = createTestQrAgent();
        Long qaId = qa.getId();

        // When: 递增到接近上限
        for (int i = 0; i < 90; i++) {
            countService.incrementDailyCount(qaId, 1);
        }

        // Then: 计数准确
        long count = countService.getDailyCount(qaId);
        assertThat(count).isEqualTo(90);
    }

    // ... more test methods
}
```

- [ ] **Step 3: Create TransferLifecycleIntegrationTest**

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：客户转移全生命周期。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferLifecycleIntegrationTest {

    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private TransferService transferService;

    @Test
    void shouldTimeoutTransferAfter24Hours() {
        // Given: 一个 pending 的转移（transferTime = 25小时前）
        CustomerTransfer t = createPendingTransfer(25);
        transferRepo.save(t);

        // When: trackResults 检查
        transferService.trackResults();

        // Then: 状态变为 timeout
        CustomerTransfer result = transferRepo.findById(t.getId()).orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.getStatus())
            .isEqualTo(CustomerTransfer.TransferStatus.timeout);
    }

    // ... more test methods
}
```

- [ ] **Step 4: Add test profile application-test.yml**

```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
  sql:
    init:
      mode: never
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 5000ms
```

- [ ] **Step 5: Run integration tests**

```bash
mvn test -Dspring.profiles.active=test
```
Expected: All tests pass (Redis must be running locally for the Redis-dependent tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/bookstore/qrcode/integration/ \
  src/test/resources/application-test.yml
git commit -m "test: 关键路径集成测试

- CallbackProcessingIntegrationTest: 回调→客户创建全链路
- AgentRotationIntegrationTest: 轮换→计数→阈值检测
- TransferLifecycleIntegrationTest: 转移→轮询→超时标记

使用 H2 内存数据库 + Redis Testcontainer。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 15: CLAUDE.md 更新 — JPA 设计决策记录

**Files:**
- Modify: `CLAUDE.md` (or create if not exists at project root)

**Interfaces:**
- Consumes: nothing
- Produces: documented design decision

- [ ] **Step 1: Add JPA design decision to CLAUDE.md**

```markdown
## Design Decisions

### No JPA Relationship Annotations

All entities use explicit foreign key ID fields (`Long qrCodeId`, `String agentUserid`)
rather than JPA `@ManyToOne`/`@OneToMany` annotations. This is intentional:

- **Pros:** Avoids lazy-loading pitfalls, keeps entities lightweight, aligns FK values
  with WeChat Work native identifiers for cross-system correlation.
- **Cons:** No automatic cascade queries; requires manual JPQL JOINs.
- **Decision date:** 2025 (at project inception), reaffirmed 2026-06-20.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 记录 JPA 关系映射设计决策

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Execution Order & Dependencies

```
Phase 1 (foundation, no inter-task deps):
  Task 1 ─┬─ Task 2 ─┬─ Task 3 ─┬─ Task 4 ─┬─ Task 5
           (parallel)  (parallel)  (parallel)  (parallel)

Phase 2 (depends on Phase 1):
  Task 6 ──── Task 7 ──── Task 8
  (exceptions) (split)   (delete old pool)

Phase 3 (depends on Phase 2):
  Task 9 ─┬─ Task 10 ─┬─ Task 11
           (parallel)   (parallel)

Phase 4 (depends on Phase 3):
  Task 12 ─┬─ Task 13 ─┬─ Task 14 ──── Task 15
            (parallel)   (parallel)
```

## Verification Checklist

After each Phase, run:

```bash
# 1. Compile
mvn compile

# 2. All tests
mvn test

# 3. Start app (manual smoke test)
mvn spring-boot:run
# Browse: http://localhost:8080
# Verify: login, QR code list, school search, dashboard
```

After Phase 2, additionally verify:
- [ ] Create a new QR code → verify it appears in list
- [ ] Simulate agent reaching daily cap → verify rotation triggers
- [ ] Verify WeChat callback processing still works (check Redis Streams)

After Phase 3, additionally verify:
- [ ] Check PatrolWorker logs for reconciliation output
- [ ] Verify rate limiting works: `curl http://localhost:8080/s/schools?city=北京` 31 times → 429 on 31st
- [ ] Stop Redis → verify school search works (Caffeine fallback)
