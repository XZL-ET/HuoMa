# 火马平台综合风险修复方案

> 状态：设计审批中 | 日期：2026-06-20 | 基于：2026-06-20 全维度风险审计（42 项）

---

## 总览

本次修复覆盖安全、业务逻辑、运维三大维度 42 项风险，按优先级分四批：
- **🔴 第一批（8 项）：** 凭据泄露、DLQ 死循环、并发竞态、告警静默 — 本周必须修
- **🟠 第二批（14 项）：** 事务边界、锁超时、缺索引、运维安全 — 本月修
- **🟡 第三批（18 项）：** 性能优化、代码健壮性、配置修补 — 下季度
- **🟢 第四批（5 项）：** 低优先级观察项 — 择机处理

### 架构影响

**不改三层单体 + Redis Stream 架构。** 改动集中在：
- 凭据外部化（新增 `.env.example`，清洗 git 历史）
- DLQ 机制修复（确定性逻辑 ID + 重放前重置计数器 + fail-open 降级）
- 并发安全加固（乐观锁 + 分布式锁 TTL 扩展）
- 运维安全（非 root 用户 + 日志轮转 + 安全标头 + 索引）

### 性能影响

| 维度 | 影响 | 方向 |
|------|------|------|
| DB 读写 | 新增索引后查询提速 10-100x（全表扫描→索引查找），写入微增 1-2% | ✅ 改善 |
| Redis ops | 逻辑 ID 改用 SHA256 替 hashCode（+2μs/消息），整体可忽略 | → 持平 |
| 企微 API | 无新增调用（不改业务逻辑路径） | → 持平 |
| 内存 | 日志轮转阻止磁盘无限增长；线程池 +4 核心增加 ~4MB | → 可忽略 |

---

## 🔴 第一批：Critical + HIGH（8 项，本周）

---

### 1.1 凭据已永久暴露在 Git 历史中（C1）

**风险：** WeCom CorpSecret、Callback AES Key、生产 DB 密码作为 `application.yml` 中的默认值提交。通过 `git show <commit>` 可获取。BOOT-INF/classes/ 目录曾包含 application.yml 副本（已删除但历史可查）。

**修复步骤：**

**Step 1 — 轮换所有凭据（立即，在代码修改之前）**
1. 登录企业微信管理后台 → 应用管理 → 获取新的 CorpSecret
2. 更新回调 Token 和 EncodingAESKey
3. 修改生产 MySQL 密码：`ALTER USER 'bookstore'@'%' IDENTIFIED BY '<new_password>';`
4. 如果生产 Redis 有密码，修改它
5. 更新生产服务器上的 `/etc/systemd/system/huoma.env`，写入新的环境变量

**Step 2 — 移除所有硬编码默认值**

`application.yml` dev profile（第 96-98 行）：
```yaml
# 改前
wecom:
  corp-secret: ${WECOM_CORP_SECRET:<YOUR_WECOM_CORP_SECRET>}
  callback-token: ${WECOM_CALLBACK_TOKEN:<YOUR_WECOM_CALLBACK_TOKEN>}
  callback-encoding-aes-key: ${WECOM_CALLBACK_AES_KEY:<YOUR_WECOM_AES_KEY>}

# 改后
wecom:
  corp-secret: ${WECOM_CORP_SECRET:}     # 开发环境需在 IDE 或 .env 中设置
  callback-token: ${WECOM_CALLBACK_TOKEN:dev-token}
  callback-encoding-aes-key: ${WECOM_CALLBACK_AES_KEY:}
```

`application.yml` prod profile（第 109、125 行）：
```yaml
# 改前
spring:
  datasource:
    password: ${DB_PASSWORD:<YOUR_DB_PASSWORD>}
  data:
    redis:
      password: ${REDIS_PASSWORD:}

# 改后
spring:
  datasource:
    password: ${DB_PASSWORD}      # 无 fallback，未设环境变量则启动失败
  data:
    redis:
      password: ${REDIS_PASSWORD} # 无 fallback，要求显式配置
```

**Step 3 — 清洗 Git 历史**

```bash
# 安装 git-filter-repo
pip install git-filter-repo

# 用替换文件清洗（将 application.yml 中的所有秘密替换为占位符）
git filter-repo --path src/main/resources/application.yml \
  --blob-callback '
import re
def blob_callback(blob, meta):
    data = blob.data.decode("utf-8", errors="replace")
    # 替换 WeCom 凭据
    data = re.sub(r"<YOUR_WECOM_CORP_SECRET>", "REPLACED_SECRET", data)
    data = re.sub(r"<YOUR_WECOM_CALLBACK_TOKEN>", "REPLACED_TOKEN", data)
    data = re.sub(r"<YOUR_WECOM_AES_KEY>", "REPLACED_AES_KEY", data)
    data = re.sub(r"<YOUR_DB_PASSWORD>", "REPLACED_DB_PASSWORD", data)
    data = re.sub(r"<YOUR_MYSQL_ROOT_PASSWORD>", "REPLACED_DEV_PASSWORD", data)
    blob.data = data.encode("utf-8")
'

# 强制推送（需要团队协调：所有人重新 clone）
git push origin --force --all
```

> ⚠️ **重要：** Step 3 会改变所有 commit SHA。团队必须先 commit + push 所有本地工作，然后在清洗后重新 clone。

**Step 4 — 添加 `.env.example` 模板**

新建 `deploy/.env.example`：
```bash
# 企业微信
WECOM_CORP_ID=wwxxxxxxxxxxxxxxxx
WECOM_CORP_SECRET=your-secret-here
WECOM_CALLBACK_TOKEN=your-token-here
WECOM_CALLBACK_AES_KEY=your-aes-key-here

# 数据库
DB_USERNAME=bookstore
DB_PASSWORD=your-password-here

# Redis
REDIS_PASSWORD=your-redis-password-here

# 管理员
ADMIN_DEFAULT_PASSWORD=your-admin-password-here
```

`.gitignore` 确保加 `*.env`（不含 example）。

**涉及文件：** `application.yml`, `deploy/.env.example`, `.gitignore` | **无代码逻辑变更。**

---

### 1.2 DLQ 重放不重置重试计数器，消息死循环（C2）

**问题：** `MessageGuardService.replayAllDlq()` 第 319-347 行 — 消息从 DLQ 重放到原 Stream 时，`dlq:retry:wecom:callback:stream:{logicalId}` 键未删除。计数器保持 >=4，消息被重新消费后立即又判死入 DLQ。

**修复：** 在重放每条消息前，根据消息内容重新计算逻辑 ID 并删除对应的 retry 计数器 key。

```java
// MessageGuardService.java — replayAllDlq() 方法内，XADD 之前新增：

// 重放前清理旧的重试计数器，让消息获得全新的重试次数
// 注意：必须在剥离 DLQ 元数据之后计算逻辑 ID，以保证与 markRetryOrDead 使用相同的 fields 计算
String logicalId = computeLogicalId(fields);  // 替换 hashCode()
String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + targetStreamKey + ":" + logicalId;
redisTemplate.delete(retryKey);
// 然后继续 XADD ...
```

同样修复 `replayDlq()` 方法（第 360-389 行），加上相同的 retry key 删除逻辑。

**涉及文件：** `MessageGuardService.java:319-389` | **改动 ~15 行。**

---

### 1.3 DLQ 逻辑 ID 用 `hashCode()`，碰撞导致消息丢失（C3）

**问题：** `MessageGuardService.java:117` — `Integer.toHexString(fields.hashCode())`。Java HashMap 的 `hashCode()` 是 32 位，~77K 消息后 50% 碰撞。两条不同消息共享一个重试计数器 → 一条被错误丢弃。

**修复：** 改用确定性字段拼接 + SHA-256 前 8 字节（或更长），确保唯一性。

```java
// MessageGuardService.java — 替换所有 hashCode() 调用

/**
 * 基于消息内容生成确定性逻辑 ID。
 * 使用 external_userid + userid + state 拼接后取 SHA-256 前 16 个 hex 字符，
 * 替代不稳定的 Java hashCode()，消除哈希碰撞导致的错误丢弃。
 *
 * <p>如果关键字段缺失，降级使用整个 fields 的 SHA-256。</p>
 */
private String computeLogicalId(Map<String, String> fields) {
    String externalUserId = fields.getOrDefault("external_userid", "");
    String userId = fields.getOrDefault("userid", "");
    String state = fields.getOrDefault("state", "");
    
    String seed = externalUserId + "|" + userId + "|" + state;
    // 如果关键字段全空，降级用整个 map 的字符串表示
    if (seed.equals("||")) {
        seed = fields.toString();
    }
    
    try {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, digest.length); i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    } catch (Exception e) {
        // SHA-256 一定可用，此异常永远不会发生
        return Integer.toHexString(seed.hashCode());
    }
}
```

然后替换三处调用：
- `markRetryOrDead()` 第 117 行
- `recoverOrphanedPending()` 第 214 行
- `replayAllDlq()` / `replayDlq()` 新增的 retry key 删除逻辑

**涉及文件：** `MessageGuardService.java:117,214,330+` | **新增 1 个方法 + 改 4 处调用。**

---

### 1.4 `sendToDlq()` 失败抛 RuntimeException，消息永久丢失（C4）

**问题：** `MessageGuardService.java:417-418` — DLQ 写入失败时抛 RuntimeException。Worker 线程捕获后记日志但不 ACK → 消息在 PEL 但永不重试 → 永久丢失。

**修复：** 改为 fail-open：DLQ 写入失败时仅记 CRITICAL 级别日志，不抛异常。让原消息留在 PEL 中，由 PEL 回收机制（每 60s）兜底。

```java
// MessageGuardService.java — sendToDlq() 方法

public void sendToDlq(String originStreamKey, Map<String, String> fields) {
    try {
        Map<String, String> dlqFields = new LinkedHashMap<>(fields);
        dlqFields.put("_dlq_origin_stream", originStreamKey);
        dlqFields.put("_dlq_time", Instant.now().toString());

        redisTemplate.opsForStream().add(RedisConfig.DLQ_STREAM_KEY, dlqFields);
        redisTemplate.opsForStream().trim(RedisConfig.DLQ_STREAM_KEY,
            RedisConfig.DLQ_STREAM_MAXLEN, true);

        log.warn("消息直接入 DLQ: originStream={}, fields={}", originStreamKey, fields);
    } catch (Exception e) {
        // fail-open：DLQ 写入失败时不抛异常，消息留在原 Stream PEL 中
        // 由 PEL 回收机制（每 60s）兜底重试，避免消息永久丢失
        log.error("DLQ 直接写入失败（fail-open，消息留在 PEL 待回收）: originStream={}", originStreamKey, e);
    }
}
```

**涉及文件：** `MessageGuardService.java:404-418` | **改动 ~5 行。**

---

### 1.5 `takeStandby()` 无并发保护，同一员工被分配到多个活码（C5）

**问题：** `GlobalAgentPoolService.takeStandby()` 有 `@Transactional` 但无悲观锁或乐观锁。两个并发线程同时读到同一条 `standby` 记录，都将其分配。

**修复：** 在 `findByStatusOrderBySortOrder` 上加 `@Lock(PESSIMISTIC_WRITE)`，在事务期间锁定读到的所有 standby 行。

```java
// GlobalAgentPoolRepository.java — 修改查询方法

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("SELECT p FROM GlobalAgentPool p WHERE p.status = :status ORDER BY p.sortOrder ASC")
List<GlobalAgentPool> findByStatusOrderBySortOrder(
    @Param("status") GlobalAgentPool.PoolStatus status);
```

但 `PESSIMISTIC_WRITE` 在 MySQL InnoDB 中对 `SELECT` 不加 `FOR UPDATE` 需要验证。更可靠的方案是显式 JPQL：

```java
// GlobalAgentPoolRepository.java — 新增方法

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("SELECT p FROM GlobalAgentPool p WHERE p.status = :status ORDER BY p.sortOrder ASC")
List<GlobalAgentPool> findStandbysForUpdate(@Param("status") GlobalAgentPool.PoolStatus status);
```

然后在 `takeStandby()` 中调用 `findStandbysForUpdate`，生成的 SQL 会包含 `FOR UPDATE`，在事务提交前阻塞其他并发 `takeStandby()`。

**涉及文件：** `GlobalAgentPoolRepository.java`, `GlobalAgentPoolService.java:70` | **改动 ~10 行。**

**权衡：** 加锁会增加 `takeStandby()` 的等待时间（最多 3 秒超时）。并发创建活码时，第二个请求等第一个提交后才能读到更新后的数据。考虑到活码创建不是高频操作（每天几次到几十次），影响可忽略。

---

### 1.6 `meltedCount24h` 永不清零 → 永久熔断（H1）

**问题：** `AlertService.java:166` — `agent.getMeltedCount24h() + 1`，字段名含 "24h" 但无定时清零。员工 3 次熔断（可能间隔数周）后永久 blocked。

**修复：** 在 `DailyResetWorker` 中增加熔断计数清零逻辑（放在每日重置中一起执行）。

```java
// DailyResetWorker.java — 新增方法

/**
 * 每日清零所有员工的熔断计数，防止跨天累积导致永久封禁。
 * 注意：blocked 状态的员工不在此处理（需人工解除），
 * 仅清零 meltedCount24h 字段，不改变 overallStatus。
 */
@Transactional
public void resetMeltCounts() {
    int updated = agentRepo.batchResetMeltedCount();
    log.info("熔断计数清零: {} 人", updated);
}
```

```java
// AgentRepository.java — 新增 JPQL

@Modifying
@Query("UPDATE Agent a SET a.meltedCount24h = 0 WHERE a.meltedCount24h > 0")
int batchResetMeltedCount();
```

在 `DailyResetWorker` 的 `dailyReset()` 方法中，`dailyReset()` 调用之后加：
```java
self.resetMeltCounts();
```

**涉及文件：** `AgentRepository.java`, `DailyResetWorker.java` | **新增 ~20 行。**

---

### 1.7 `PatrolWorker` 除零风险 + 告警静默丢弃（H2 + H4）

**H2 — 除零：** `PatrolWorker.java:286` — `dailyCurrent / dailyMax`，当 `dailyMax=0` 时结果为 `Infinity`，误触发 `traffic_spike`。

```java
// PatrolWorker.java — checkOverloadedQrCodes() 方法内

// 改前
double ratio = (double) a.getDailyCurrent() / (double) a.getDailyMax();

// 改后
int dailyMax = a.getDailyMax();
if (dailyMax <= 0) {
    log.warn("员工 {} dailyMax={} 异常（≤0），跳过负载检查", a.getAgentUserid(), dailyMax);
    continue;
}
double ratio = (double) a.getDailyCurrent() / (double) dailyMax;
```

**H4 — 告警静默丢弃：** 三处 `catch (Exception ignored) {}` 改为至少记 ERROR 日志。

`WechatSyncHealingService.java:110`：
```java
// 改前
} catch (Exception ignored) {}

// 改后
} catch (Exception e) {
    log.error("自愈移除后告警创建失败: userid={}, qrCodeId={}", failing, qrCodeId, e);
}
```

`QrCodeService.java:254`：
```java
// 改前
} catch (Exception ignored) {}

// 改后
} catch (Exception e) {
    log.error("活码创建后同步失败告警创建也失败: qrCodeId={}", qrCode.getId(), e);
}
```

`AlertService.java:233-236` 的 `createAlert()` 返回 null 时，调用方改为检查返回值并记录：
```java
// WechatSyncHealingService.java、QrCodeService.java、PatrolWorker.java 等调用方
AgentAlert alert = alertService.createAlert(...);
if (alert == null) {
    log.error("告警创建失败（createAlert 返回 null）: type={}, userid={}", alertType, agentUserid);
}
```

**涉及文件：** `PatrolWorker.java:286`, `WechatSyncHealingService.java:110`, `QrCodeService.java:254` | **改动 ~15 行。**

---

### 1.8 `QrCodeService.create()` 未对 WeChat API 响应做 null 检查（H3）

**问题：** `QrCodeService.java:206,208` — `result.get("config_id").asText()`，若字段缺失 NPE。

**修复：**

```java
// QrCodeService.java — create() 方法内

JsonNode configIdNode = result.get("config_id");
JsonNode qrCodeNode = result.get("qr_code");
if (configIdNode == null || configIdNode.isNull()) {
    throw new IllegalStateException("企微 createContactWay 响应缺少 config_id 字段");
}
if (qrCodeNode == null || qrCodeNode.isNull()) {
    throw new IllegalStateException("企微 createContactWay 响应缺少 qr_code 字段");
}
String configId = configIdNode.asText();
String qrCodeUrl = qrCodeNode.asText();
```

**涉及文件：** `QrCodeService.java:205-210` | **改动 ~10 行。**

---

## 🟠 第二批：HIGH（6 项，本月）

---

### 2.1 `QrCodeService.delete()` 非原子删除（H5）

**问题：** 先调 WeChat API 删活码（第 395 行），后删 DB。WeChat 侧成功但 DB 失败 → DB 有记录但 WeChat 已删。反过来 WeChat 失败也难判断实际是否已删。

**修复：** 调整顺序：先删 DB（标记状态），后调 WeChat API。如果 WeChat API 失败，由对账扫描兜底。

```java
// QrCodeService.java — delete() 方法

@Transactional
public void delete(Long qrCodeId) {
    QrCode qr = qrCodeRepo.findById(qrCodeId).orElseThrow(...);
    
    String configId = qr.getQrConfigId();
    
    // 1. 先软删除 DB 侧（或标记为 deleted 状态，由后续任务物理清理）
    qr.setStatus(QrCode.QrStatus.deleted);
    qrCodeRepo.save(qr);
    
    // 2. 后调 WeChat API（失败不影响 DB 状态，由 reconcileOrphanQrCodes 兜底）
    if (configId != null && !configId.isEmpty()) {
        try {
            wecomApi.deleteContactWay(configId);
        } catch (WecomApiException e) {
            log.error("WeChat 侧活码删除失败（将由对账扫描补偿）: configId={}, errcode={}",
                configId, e.getErrcode());
            // 不抛异常：DB 已标记 deleted，对账扫描会重试删除
        }
    }
}
```

**涉及文件：** `QrCodeService.java:385-410` | **改动 ~20 行。**

---

### 2.2 `AgentRotationService` 分布式锁 TTL 30s 可能超时（H6）

**问题：** `AgentRotationService.java:144,230` — 锁 TTL 硬编码 30 秒。若 `@Transactional` 方法超时 + 慢 WeChat API，锁过期后第二条线程进入临界区。

**修复：** 将锁 TTL 延长到 60s，并作为配置项。

`application.yml`：
```yaml
app:
  agent:
    rotation-lock-ttl-seconds: ${AGENT_ROTATION_LOCK_TTL:60}
```

`AgentRotationService.java`：
```java
@Value("${app.agent.rotation-lock-ttl-seconds:60}")
private int rotationLockTtlSeconds;

// 使用处
Duration lockTtl = Duration.ofSeconds(rotationLockTtlSeconds);
Boolean locked = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, lockValue, lockTtl);
```

**涉及文件：** `application.yml`, `AgentRotationService.java:144,230` | **改动 ~10 行。**

---

### 2.3 应用以 root 运行 + 无日志轮转 + dev-login 无 Profile 保护（H7/H8/H9）

**H7 — root 运行：**

`deploy/bookstore-qrcode.service`：
```ini
# 改前
User=root

# 改后
User=huoma
Group=huoma
```

创建用户脚本（加入 `deploy/setup.sh` 或部署文档）：
```bash
sudo useradd -r -s /bin/false -d /opt/HuoMa huoma
sudo chown -R huoma:huoma /opt/HuoMa /var/log/huoma
```

**H8 — 日志轮转：** 新增 `logback-spring.xml`（替代 Spring Boot 默认的纯控制台输出）。

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <!-- 控制台输出（开发环境） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件输出 — 按天轮转，保留 30 天 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/huoma/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/huoma/application.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 错误日志单独输出 -->
    <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/huoma/error.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/huoma/error.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>90</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Spring profile 激活 -->
    <springProfile name="dev">
        <root level="INFO">
            <appender-ref ref="CONSOLE" />
        </root>
    </springProfile>

    <springProfile name="prod">
        <root level="INFO">
            <appender-ref ref="FILE" />
            <appender-ref ref="ERROR_FILE" />
        </root>
    </springProfile>
</configuration>
```

systemd service 文件中移除 `>> /var/log/huoma/stdout.log` 重定向（logback 自行写入文件），改为：
```ini
ExecStart=/usr/bin/java -jar ... /opt/HuoMa/target/bookstore-qrcode-0.1.0.jar
```

同时配置 logrotate（`/etc/logrotate.d/huoma`）：
```
/var/log/huoma/stdout.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
    copytruncate
}
```

**H9 — dev-login 加 Profile 保护：**

```java
// DownloadCenterController.java:88

@Profile("dev")  // 新增：仅开发环境可用
@GetMapping("/oauth/dev-login")
public String devLogin(...) { ... }
```

**涉及文件：** `deploy/bookstore-qrcode.service`, `src/main/resources/logback-spring.xml`（新建）, `deploy/logrotate-huoma.conf`（新建）, `DownloadCenterController.java:88` | **新增 2 文件 + 改 2 文件。**

---

### 2.4 生产 DB/Redis 密码有硬编码 fallback（H10）

已在 C1 中一并修复（`application.yml` 中移除 fallback 值）。

---

### 2.5 AsyncConfig 线程池满载时 AbortPolicy 静默丢弃（H11）

**问题：** `AsyncConfig.java:68-81` — taskExecutor 的 12 核心线程全部被 TagWorker(8) + DataFillWorker(4) 占用。`syncQrCodeToWechatAsync` 和批量导入被 `AbortPolicy` 静默丢弃。

**修复：** 拆分线程池 — 给同步/批量任务独立 executor。

```java
// AsyncConfig.java — 新增 @Bean

/**
 * 批量操作/同步专用线程池 — 隔离 TagWorker/DataFillWorker，
 * 避免队列被 Stream 消费线程占满后，活码同步任务被 AbortPolicy 丢弃。
 */
@Bean("batchExecutor")
public Executor batchExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("batch-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
}
```

然后将 `syncQrCodeToWechatAsync` 的 `@Async` 注解改为 `@Async("batchExecutor")`。

**涉及文件：** `AsyncConfig.java`, 所有 `@Async` 调用方 | **改动 ~20 行。**

---

### 2.6 关键表缺少索引（H12）

**`customer_tag` 缺 `tag_id` 索引** — `schema.sql`：
```sql
ALTER TABLE customer_tag ADD INDEX idx_tag_id (tag_id);
```

**`operation_log` 缺 `(action, created_at)` 复合索引** — `schema.sql`：
```sql
ALTER TABLE operation_log ADD INDEX idx_action_created (action, created_at);
```

**`agent_alert` 缺 `alert_type` 索引** — `schema.sql`：
```sql
ALTER TABLE agent_alert ADD INDEX idx_alert_type (alert_type);
ALTER TABLE agent_alert ADD INDEX idx_alert_qrcode (qr_code_id);
```

**`qr_code` 缺 `school_name` 索引** — `schema.sql`：
```sql
ALTER TABLE qr_code ADD INDEX idx_school_name (school_name);
```

**涉及文件：** `schema.sql` | **新增 ~20 行 DDL。**

---

## 🟡 第三批：MEDIUM（18 项，下季度）

---

### 3.1 事务内 HTTP 调用（M1, M2）

**涉及：** `PatrolWorker.reconcileOrphanQrCodes()` + `QrCodeService.delete()`

**修复：** 将 HTTP 调用移出 `@Transactional` 范围。

`PatrolWorker.reconcileOrphanQrCodes()` — 拆分为两步：
```java
// 第 1 步（无事务）：查询 + 调 WeChat API，收集需删除的 configId 列表
public void reconcileOrphanQrCodes() {
    List<QrCode> orphans = qrCodeRepo.findOrphanCandidates();
    List<String> toDelete = new ArrayList<>();
    for (QrCode qr : orphans) {
        try {
            JsonNode detail = wecomApi.getContactWay(qr.getQrConfigId());
            toDelete.add(qr.getQrConfigId()); // WeChat 侧存在，标记待删
        } catch (WecomApiException e) {
            // WeChat 侧不存在，跳过
        }
    }
    if (!toDelete.isEmpty()) {
        self.deleteOrphanConfigIds(toDelete); // 第 2 步（事务内删 DB）
    }
}

@Transactional  // 只有 DB 操作在事务内
public void deleteOrphanConfigIds(List<String> configIds) { ... }
```

**涉及文件：** `PatrolWorker.java:179-219`, `QrCodeService.java:385-410`

---

### 3.2 全量加载三张表到内存（M3）

**修复：** `EmployeeSyncService` 改用查询 + 分页，或使用 `COUNT()` + `EXISTS` 判断。不改核心逻辑，仅优化 SQL。

```java
// 改前：加载全表
List<Employee> employees = employeeRepo.findAllByActiveTrueOrderByName();
List<GlobalAgentPool> pools = poolRepo.findAll();
List<Agent> agents = agentRepo.findAll();

// 改后：按需查询
Set<String> poolUserids = poolRepo.findAllUserids(); // 仅查 userid 列
Set<String> agentUserids = agentRepo.findAllUserids(); // 仅查 userid 列
// 只查不在池中的活跃员工
List<Employee> newEmployees = employeeRepo.findActiveNotInUserids(poolUserids);
```

新增 repository 方法：
```java
@Query("SELECT p.agentUserid FROM GlobalAgentPool p")
Set<String> findAllUserids();

@Query("SELECT e FROM Employee e WHERE e.active = true AND e.userid NOT IN :userids ORDER BY e.name")
List<Employee> findActiveNotInUserids(@Param("userids") Set<String> userids);
```

**涉及文件：** `EmployeeSyncService.java`, `EmployeeRepository.java`, `GlobalAgentPoolRepository.java`, `AgentRepository.java`

---

### 3.3 DataFill 事件发布失败导致客户数据不完整（M4）

**修复：** 在 `CustomerService.upsertFromCallback()` 中，DataFill 事件发布失败时将消息重新入队到专门的 repair stream，而非仅记日志：

```java
try {
    redisTemplate.opsForStream().add(RedisConfig.DATAFILL_STREAM_KEY, fields);
} catch (Exception e) {
    log.error("DataFill 事件发布失败，消息入修复队列: external={}", externalUserId, e);
    // 标记此客户需要数据修复
    customer.setDataNeedsRepair(true);  // 新增字段
    customerRepo.save(customer);
}
```

`customer` 表新增 `data_needs_repair` 字段（`schema.sql`）：
```sql
ALTER TABLE customer ADD COLUMN data_needs_repair TINYINT(1) NOT NULL DEFAULT 0;
```

**涉及文件：** `CustomerService.java:183-195`, `schema.sql`

---

### 3.4 `SchoolRateLimitFilter` 窗口值硬编码（M5）+ URI 匹配过宽（M6）

**M5 修复：**
```java
// SchoolRateLimitFilter.java:109

// 改前
redisTemplate.execute(RATE_SCRIPT, List.of(key, String.valueOf(maxPerMinute), "60"), "120");

// 改后
long windowSecs = windowMs / 1000;
redisTemplate.execute(RATE_SCRIPT, List.of(key, String.valueOf(maxPerMinute),
    String.valueOf(windowSecs)), String.valueOf(windowSecs * 2));
```

**M6 修复：** 将 URI 前缀匹配改为精确路径匹配：
```java
// 改前
if (!request.getRequestURI().startsWith("/s")) return;

// 改后
String uri = request.getRequestURI();
if (!uri.startsWith("/s/") && !uri.equals("/s")) return;
```

**涉及文件：** `SchoolRateLimitFilter.java:75,109`

---

### 3.5 `KEYS` 命令阻塞 Redis（M7）

**修复：** 改用 `SCAN` 替代 `KEYS`：

```java
// AgentDailyCountService.java:58

public void resetDailyCounts() {
    // 改前
    var keys = redisTemplate.keys(pattern);
    if (keys != null) redisTemplate.delete(keys);

    // 改后：使用 SCAN 非阻塞遍历 + 批量删除
    ScanOptions options = ScanOptions.scanOptions()
        .match(RedisConfig.DAILY_COUNT_KEY_PREFIX + "*")
        .count(100)
        .build();
    try (var cursor = redisTemplate.scan(options)) {
        while (cursor.hasNext()) {
            String key = cursor.next();
            redisTemplate.unlink(key); // UNLINK 异步删除，不阻塞 Redis
        }
    }
}
```

**涉及文件：** `AgentDailyCountService.java:55-63`

---

### 3.6 欢迎语用 userid 而非真实姓名（M8）

**修复：** 在发送欢迎语之前查 Employee 表获取姓名：

```java
// TransferService.java:279

// 改前
String teacherName = transfer.getToUserid();

// 改后
String teacherName = employeeRepo.findByUserid(transfer.getToUserid())
    .map(Employee::getName)
    .orElse(transfer.getToUserid()); // 降级为 userid
```

**涉及文件：** `TransferService.java:276-291`, `EmployeeRepository.java`

---

### 3.7 WeChat callback token 被 INFO 日志打印（M9）

**修复：** 移除日志中的 token 值：

```java
// WecomCallbackValidator.java:133

// 改前
log.info("回调签名调试: token={}, ...", callbackToken, ...);

// 改后
log.info("回调签名调试: timestamp={}, nonce={}, encrypt前20字符={}",
    timestamp, nonce, encrypt != null ? encrypt.substring(0, Math.min(20, encrypt.length())) : "null");
```

**涉及文件：** `WecomCallbackValidator.java:132-134`

---

### 3.8 `DownloadAuthenticationFilter` 不验证员工是否仍活跃（M10）

**修复：** 在过滤器中增加活跃状态检查：

```java
// DownloadAuthenticationFilter.java:47-49

Employee employee = employeeRepo.findByUserid(userid).orElse(null);
if (employee == null || !employee.getActive()) {
    log.warn("员工 {} 不存在或已离职，拒绝下载中心访问", userid);
    // 清除 session 并重定向到错误页面
    session.removeAttribute(SESSION_EMPLOYEE_USERID);
    response.sendRedirect("/download/oauth/entry");
    return;
}
```

**涉及文件：** `DownloadAuthenticationFilter.java:47-49`, 需注入 `EmployeeRepository`

---

### 3.9 缺少安全响应头（M11）

**修复：** `deploy/nginx.conf` 增加：
```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

**涉及文件：** `deploy/nginx.conf`

---

### 3.10 部署脚本硬编码生产 IP 和 root SSH（M12）

**修复：** 从环境变量读取：

```bash
# deploy/deploy.sh

SERVER_IP="${DEPLOY_SERVER_IP:?请设置 DEPLOY_SERVER_IP 环境变量}"
SERVER_USER="${DEPLOY_SERVER_USER:?请设置 DEPLOY_SERVER_USER 环境变量}"
```

**涉及文件：** `deploy/deploy.sh:8-9`

---

### 3.11 无 staging/预发布 profile（M13）

**修复：** 在 `application.yml` 添加 `staging` profile，复制 prod 配置但使用测试数据库和测试凭据：

```yaml
---
spring:
  config:
    activate:
      on-profile: staging
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:3306/bookstore_qrcode_staging?...
    username: ${DB_USERNAME:bookstore_staging}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 4
```

**涉及文件：** `application.yml`

---

### 3.12 Actuator metrics 端点无需认证（M14）

**修复：** `SecurityConfig.java` 中区分 health 和 metrics：
```java
.requestMatchers("/actuator/health/**").permitAll()
.requestMatchers("/actuator/metrics/**").hasRole("ADMIN")  // 新增
```

**涉及文件：** `SecurityConfig.java:38-39`

---

### 3.13 Stream NOGROUP 恢复空 catch（M15）

**修复：** 在三个 Worker 的空 catch 中增加 WARN 日志：

```java
// CallbackWorker.java:183, TagWorker.java:172, DataFillWorker.java:158

} catch (Exception e) {
    log.warn("Stream/Group 创建失败（非 NOGROUP 错误）: {}", e.getMessage());
}
```

**涉及文件：** `CallbackWorker.java:183`, `TagWorker.java:172`, `DataFillWorker.java:158`

---

### 3.14 每次消费循环都 TRIM Stream（M16）

**修复：** 调整为每 10 次迭代修剪一次：

```java
// 在 Worker 循环类中添加实例字段
private int trimCounter = 0;

// 消费循环中
if (++trimCounter % 10 == 0) {
    redisTemplate.opsForStream().trim(streamKey, maxLen, true);
}
```

**涉及文件：** `CallbackWorker.java:160`, `TagWorker.java:153`, `DataFillWorker.java:136`

---

### 3.15 `school-entry-url` HTTP + 硬编码 IP（M17）

已在 C1 中一并修复（改为环境变量，生产用 HTTPS）。

---

### 3.16 Undertow worker 仅 20 线程，高负载瓶颈（M18）

**修复：** 生产环境增加线程数，通过环境变量覆盖：

```yaml
# application.yml
server:
  undertow:
    threads:
      worker: ${UNDERTOW_WORKER_THREADS:20}
```

生产环境设置 `UNDERTOW_WORKER_THREADS=40`。

---

### 3.17 部署后健康检查用 `/` 而非 `/actuator/health`（M19）

**修复：** `deploy/deploy.sh:53`：
```bash
# 改前
curl -s -o /dev/null -w "%{http_code}" "http://${SERVER_IP}:8080/"

# 改后
curl -s -o /dev/null -w "%{http_code}" "http://${SERVER_IP}:8080/actuator/health"
```

---

### 3.18 `NotificationService` 缺失 — `alertEmptyBackup` 只在日志中有记录（M20）

**修复：** 后续迭代增加企微机器人推送。当前仅在告警表中记录是可接受的。

---

## 🟢 第四批：LOW（5 项，观察）

---

### 4.1 `WecomApiClient` 私建 ObjectMapper（L1）

```java
// 改为注入 Spring Boot 自动配置的 ObjectMapper（支持模块注册、统一配置）
@Autowired
private ObjectMapper objectMapper; // 代替 private final ObjectMapper = new ObjectMapper()
```

### 4.2 `findFailingUser()` 线性扫描无 API 调用上限（L2）

增加硬上限：
```java
int maxLinearScan = Math.min(right, left + 10); // 最多扫描 10 个
for (int i = left; i < maxLinearScan; i++) { ... }
```

### 4.3 `updateAgent()` 非法 role 静默无操作（L3）

加 WARN 日志：
```java
catch (IllegalArgumentException e) {
    log.warn("updateAgent 收到非法 role 值: {}", role, e);
}
```

### 4.4 `RateLimiterService` 15s 窗口仅观测不拦截（L4）

当前设计是有意之举（文档已说明）。不改。

### 4.5 `schema.sql` 动态 ALTER TABLE（L5）

未来迁移到 Flyway。当前不改。

---

## 风险矩阵

| 编号 | 类别 | 严重度 | 修复难度 | 影响范围 | 测试方式 |
|------|------|--------|----------|----------|----------|
| C1 | 凭据安全 | CRITICAL | 中 | git历史+配置 | 手动验证 |
| C2 | DLQ | CRITICAL | 低 | 1 文件 | 单元测试 |
| C3 | DLQ | CRITICAL | 低 | 1 文件 | 单元测试 |
| C4 | DLQ | CRITICAL | 低 | 1 文件 | 单元测试 |
| C5 | 并发 | CRITICAL | 中 | 2 文件 | 集成测试 |
| H1 | 业务 | HIGH | 低 | 2 文件 | 单元测试 |
| H2 | 业务 | HIGH | 低 | 1 文件 | 单元测试 |
| H3 | 业务 | HIGH | 低 | 1 文件 | 单元测试 |
| H4 | 业务 | HIGH | 低 | 3 文件 | 单元测试 |
| H5 | 业务 | HIGH | 低 | 1 文件 | 单元测试 |
| H6 | 并发 | HIGH | 低 | 2 文件 | 集成测试 |
| H7 | 运维 | HIGH | 中 | 部署文件 | 手动验证 |
| H8 | 运维 | HIGH | 中 | 新增文件 | 手动验证 |
| H9 | 安全 | HIGH | 低 | 1 文件 | 手动验证 |
| H10 | 安全 | HIGH | 低 | 1 文件 | 手动验证 |
| H11 | 并发 | HIGH | 中 | 3+ 文件 | 单元测试 |
| H12 | 性能 | HIGH | 低 | 1 文件 | 手动验证 |
| M1-M18 | 混合 | MEDIUM | 低-中 | 多文件 | 单元测试 |
| L1-L5 | 混合 | LOW | 低 | 多文件 | 单元测试 |

---

## 测试策略

### 第一批（Critical）
- **C2-C4：** 新增 `MessageGuardServiceTest` 单元测试，覆盖 DLQ 重放→计数器重置→重试成功、逻辑 ID 确定性、sendToDlq fail-open
- **C5：** `GlobalAgentPoolServiceTest` 模拟并发 takeStandby，验证 `FOR UPDATE` 锁
- **H1-H4：** 继承现有单元测试，新增边界用例

### 第二批
- **H6：** 集成测试模拟锁 TTL 过期场景
- **H11：** `AsyncConfigTest` 验证线程池隔离

### 第三/四批
- 每项独立单元测试，改动范围小，逐项回归即可

---

## 不做的

- ❌ DLQ 专用消费者（架构变更太大，留待后续迭代）
- ❌ Flyway 迁移（与当前 schema.sql 机制冲突，需单独立项）
- ❌ 企微机器人告警推送（新功能，超出风险修复范围）
- ❌ 全局 API 限流器（需评估企微真实限流阈值后再设计）
