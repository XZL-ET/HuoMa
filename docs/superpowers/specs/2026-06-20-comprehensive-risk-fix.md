# 火马平台综合风险修复方案（三轮审计合并）

> 状态：设计审批中 | 日期：2026-06-20
> 基于：设计缺陷审查(16) + 全维度风险审计(42) + 深度审计(56) = 114 项，去重后 98 项

---

## 执行优先级总览

| 阶段 | 项数 | 风险 | 测试要求 | 停机 |
|------|------|------|---------|------|
| 🔵 Phase 0：零风险 | 14 | 无 | 编译通过即可 | 无 |
| 🟢 Phase 1：低风险 | 22 | 低 | 回归测试 12 个现有用例 | 无 |
| 🟡 Phase 2：中等风险 | 15 | 中 | 全量测试 + 灰度观察 1 周 | 无 |
| 🟠 Phase 3：需协调 | 3 | 中-高 | 全量测试 | 需提前通知 |
| 🔴 Phase 4：凭据轮换 | 1 | 极高 | 手动验证 | 有（短暂） |

---

## 🔵 Phase 0：零风险（直接改，不影响现有逻辑）

### P0-1：为所有 CDN 资源添加 SRI 完整性校验（N9）

**文件：** `src/main/resources/templates/layout.html`、`download/layout.html`、`school/layout.html`

```html
<!-- Bootstrap CSS -->
<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"
      integrity="sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH"
      crossorigin="anonymous" rel="stylesheet">

<!-- Bootstrap Icons -->
<link href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.css"
      integrity="sha384-tViUnnbYAV00FlIhFb5jb4OUn5AYeA8KQnPFUE3eZ2nMxwdF8eNAdU9GnM4bGl9"
      crossorigin="anonymous" rel="stylesheet">

<!-- HTMX -->
<script src="https://unpkg.com/htmx.org@1.9.12"
        integrity="sha384-ujb1lZYygJmzgSwoxRggbCHcjc0rB2XoQrxeTUQyRjrOnlCoYta87iKBWq3EsdM2"
        crossorigin="anonymous"></script>

<!-- Chart.js -->
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"
        integrity="sha384-IMtUoGBJWWv8a1lP2cBkZ8YrJeMF4kSQUh7XC6Xu+kwNINLyVJkeGJk2Z0N8MeAF"
        crossorigin="anonymous"></script>

<!-- Bootstrap JS -->
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"
        integrity="sha384-YvpcrYf0tY3lHB60NNkmXc5s9fDVZLESaAA55NDzOxhy9GkcIdslK1eN7N6jIeHz"
        crossorigin="anonymous"></script>
```

> **注意：** integrity hash 值需在实际添加时从 CDN 获取准确值。

---

### P0-2：加安全响应头（N17）

**文件：** `deploy/nginx.conf`

在 `server` 块内添加：
```nginx
# 安全响应头
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;

# SSL 协议强化
ssl_protocols TLSv1.2 TLSv1.3;
ssl_ciphers HIGH:!aNULL:!MD5;
ssl_prefer_server_ciphers on;

# 上传大小限制
client_max_body_size 10m;
```

---

### P0-3：JVM 加 ExitOnOutOfMemoryError（N26）

**文件：** `deploy/bookstore-qrcode.service` 第 9 行

```ini
# 改前
ExecStart=/bin/bash -c "exec /usr/bin/java -jar -Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError

# 改后
ExecStart=/bin/bash -c "exec /usr/bin/java -jar -Xms512m -Xmx2g -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError \
    -XX:HeapDumpPath=/var/log/huoma/ -Duser.timezone=Asia/Shanghai \
    -Dspring.profiles.active=prod /opt/HuoMa/target/bookstore-qrcode-0.1.0.jar"
```

---

### P0-4：deploy.sh JAR 路径与 .service 对齐（N5）

**文件：** `deploy/deploy.sh` 和 `deploy/bookstore-qrcode.service`

两者必须指向同一路径。统一到 `/opt/HuoMa/target/bookstore-qrcode-0.1.0.jar`：

`deploy.sh` 第 10、11 行：
```bash
APP_DIR="/opt/HuoMa"
JAR_NAME="bookstore-qrcode-0.1.0.jar"
```

`deploy.sh` 第 26 行：
```bash
scp "target/${JAR_NAME}" "${SERVER_USER}@${SERVER_IP}:${APP_DIR}/target/${JAR_NAME}"
```

同步修正 `deploy.sh` 第 33 行的日志目录和 37 行的 service 复制路径，全部对齐到 `/opt/HuoMa`。

---

### P0-5：新建 huoma.env 模板（N14, N22）

**文件：** `deploy/huoma.env.template`（新建）

```bash
# ===== 火马平台环境变量模板 =====
# 复制此文件为 /etc/systemd/system/huoma.env 并填入真实值
# 切勿将真实凭据提交到 git！

# --- 数据库 ---
DB_HOST=rm-xxxx.mysql.rds.aliyuncs.com    # 阿里云 RDS 地址
DB_USERNAME=bookstore
DB_PASSWORD=<your-password>

# --- Redis ---
REDIS_HOST=r-xxxx.redis.rds.aliyuncs.com  # 阿里云 Tair 地址
REDIS_PASSWORD=<your-password>

# --- 企业微信 ---
WECOM_CORP_ID=wwxxxxxxxxxxxxxxxx
WECOM_CORP_SECRET=<your-secret>
WECOM_CALLBACK_TOKEN=<your-token>
WECOM_CALLBACK_AES_KEY=<your-aes-key>

# --- 管理员 ---
ADMIN_DEFAULT_USERNAME=admin
ADMIN_DEFAULT_PASSWORD=<your-admin-password>

# --- 业务配置 ---
TRANSFER_FORM_URL=https://your-domain.com/transfer-form
SCHOOL_ENTRY_URL=https://your-domain.com/s
SCHOOL_RATE_LIMIT_MAX=30
UNDERTOW_WORKER_THREADS=40
```

---

### P0-6：.gitignore 加 *.env 规则（N30）

**文件：** `.gitignore`

```gitignore
# 环境变量文件（包含密码）
*.env
!*.env.template
!*.env.example
```

---

### P0-7：systemd After= 改为仅依赖 nginx（N31）

**文件：** `deploy/bookstore-qrcode.service` 第 3 行

```ini
# 改前
After=network.target mysql.service redis-server.service

# 改后（阿里云 RDS/Tair 是远端服务，无需本地依赖）
After=network.target nginx.service
```

---

### P0-8：nginx YOUR_DOMAIN 替换说明（N16）

**文件：** `deploy/nginx.conf` 第 11 行

```nginx
# 部署前必须将 YOUR_DOMAIN 替换为实际域名
server_name YOUR_DOMAIN;
```

---

### P0-9：application.yml prod 数据源和服务地址外置（N6, N15, N29）

**文件：** `application.yml` prod profile

```yaml
# 改前
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/bookstore_qrcode?useSSL=true&...
  data:
    redis:
      host: localhost

# 改后
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:3306/bookstore_qrcode?useSSL=true&...
  data:
    redis:
      host: ${REDIS_HOST:localhost}

# school-entry-url 也改为环境变量
app:
  school-entry-url: ${SCHOOL_ENTRY_URL:http://localhost:8080/s}
```

---

### P0-10：生产 DB_PASSWORD 和 REDIS_PASSWORD 移除硬编码 fallback（N7）

**文件：** `application.yml` prod profile

```yaml
# 改前
spring:
  datasource:
    password: ${DB_PASSWORD:<YOUR_DB_PASSWORD>}
  data:
    redis:
      password: ${REDIS_PASSWORD:}

# 改后（未设环境变量则启动失败，而非用已知密码默默启动）
spring:
  datasource:
    password: ${DB_PASSWORD}
  data:
    redis:
      password: ${REDIS_PASSWORD}
```

---

### P0-11：dev profile 移除 WeCom 密钥默认值（C1）

**文件：** `application.yml` 第 95-98 行

```yaml
# 改前
wecom:
  corp-secret: ${WECOM_CORP_SECRET:<YOUR_WECOM_CORP_SECRET>}
  callback-token: ${WECOM_CALLBACK_TOKEN:<YOUR_WECOM_CALLBACK_TOKEN>}
  callback-encoding-aes-key: ${WECOM_CALLBACK_AES_KEY:<YOUR_WECOM_AES_KEY>}

# 改后
wecom:
  corp-secret: ${WECOM_CORP_SECRET:}
  callback-token: ${WECOM_CALLBACK_TOKEN:dev-token}
  callback-encoding-aes-key: ${WECOM_CALLBACK_AES_KEY:}
```

---

### P0-12：health check 改用 /actuator/health（N27）

**文件：** `deploy/deploy.sh` 第 54 行

```bash
# 改前
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://${SERVER_IP}:8080/")

# 改后（通过 SSH 本地检查，不暴露 8080 到公网）
STATUS=$(ssh "${SERVER_USER}@${SERVER_IP}" \
    "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health" 2>/dev/null || echo "000")
```

---

### P0-13：Actuator metrics 加 ADMIN 角色保护（M14）

**文件：** `SecurityConfig.java` 第 39 行

```java
.requestMatchers("/actuator/health/**").permitAll()
.requestMatchers("/actuator/metrics/**").hasRole("ADMIN")  // 新增
```

---

### P0-14：dev-login 加 @Profile("dev")（H9）

**文件：** `DownloadCenterController.java` 第 88 行

```java
@Profile("dev")
@GetMapping("/oauth/dev-login")
public String devLogin(...) { ... }
```

---

## 🟢 Phase 1：低风险（改动小，回归测试即可）

### P1-1：Spring Boot 3.2.5 → 3.2.12（N4, N3）

**文件：** `pom.xml` 第 12 行

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.12</version>  <!-- was 3.2.5 -->
</parent>
```

同时升级 POI：
```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.4.0</version>  <!-- was 5.2.5 -->
</dependency>
```

**验证：** `./mvnw clean test`（12 个现有测试必须全绿）

---

### P1-2：前端 innerHTML XSS 修复（N8, N18）

**文件 1：** `dashboard-charts.js` — 排行榜渲染（第 231 行）

```javascript
// 改前：API 返回的 row.name / row.schoolName 直接拼 HTML
tbody.innerHTML = data.map(rowFn).join('');

// 改后：用 textContent 安全插入
tbody.innerHTML = '';
data.forEach(function(row) {
    var tr = document.createElement('tr');

    var td1 = document.createElement('td');
    td1.className = 'text-center fw-bold';
    td1.textContent = row.rank;
    tr.appendChild(td1);

    var td2 = document.createElement('td');
    td2.textContent = row.name;
    if (row.rank <= 3) {
        var badge = document.createElement('span');
        badge.className = 'badge bg-warning text-dark';
        badge.textContent = 'Top' + row.rank;
        td2.appendChild(document.createTextNode(' '));
        td2.appendChild(badge);
    }
    tr.appendChild(td2);

    var td3 = document.createElement('td');
    td3.className = 'text-end fw-semibold';
    td3.textContent = row.count.toLocaleString();
    tr.appendChild(td3);

    tbody.appendChild(tr);
});
```

`s/qrCodes` 同理，将 `row.schoolName` 用 `textContent` 插入。

**文件 2：** `dashboard-charts.js` — 漏斗图（第 192 行）

```javascript
// 改前
container.innerHTML = html;

// 改后：用 createElement + textContent 构建
container.innerHTML = '';
steps.forEach(function(step, i) {
    var pct = Math.max(step.value * 100 / maxVal, 15);
    var div = document.createElement('div');
    div.className = 'funnel-step';
    div.style.width = pct + '%';
    div.style.background = colors[i];

    var span = document.createElement('span');
    span.textContent = step.label;
    div.appendChild(span);

    var strong = document.createElement('strong');
    strong.textContent = step.value.toLocaleString();
    div.appendChild(strong);

    container.appendChild(div);
});
```

**文件 3：** `user/list.html` 第 73 行 — confirm() 注入

```html
<!-- 改前 -->
onsubmit="return confirm('确定要删除用户「' + this.getAttribute('data-name') + '」吗？')"

<!-- 改后 -->
onsubmit="return confirm('确定要删除该用户吗？')"
```

---

### P1-3：MessageGuardService DLQ 三 Bug 修复（C2, C3, C4）

**文件：** `MessageGuardService.java`

**a. hashCode → SHA-256 确定性 ID（C3）**

新增方法替代 `Integer.toHexString(fields.hashCode())`：

```java
private String computeLogicalId(Map<String, String> fields) {
    String externalUserId = fields.getOrDefault("external_userid", "");
    String userId = fields.getOrDefault("userid", "");
    String state = fields.getOrDefault("state", "");

    String seed = externalUserId + "|" + userId + "|" + state;
    if (seed.equals("||")) {
        seed = fields.toString();
    }

    try {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {  // 128-bit, collision prob ≈ 1/2^64
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    } catch (Exception e) {
        return Integer.toHexString(seed.hashCode());
    }
}
```

替换 3 处调用：`markRetryOrDead`(L117)、`recoverOrphanedPending`(L214)、`replayAllDlq`/`replayDlq`。

**b. replayAllDlq/replayDlq 重放前删 retry counter（C2）**

在 XADD 回原 Stream 前：
```java
// 重放前清理旧计数器，让消息获得全新重试次数
String logicalId = computeLogicalId(fields);
String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + targetStreamKey + ":" + logicalId;
redisTemplate.delete(retryKey);
```

**c. sendToDlq fail-open（C4）**

```java
public void sendToDlq(String originStreamKey, Map<String, String> fields) {
    try {
        // ... 现有逻辑 ...
    } catch (Exception e) {
        // fail-open：DLQ 写入失败时不抛异常，消息留在 PEL 待回收
        log.error("DLQ 写入失败（fail-open，消息留 PEL）: originStream={}", originStreamKey, e);
    }
}
```

---

### P1-4：QrCodeService.delete() 先 DB 后 WeChat（H5）

**文件：** `QrCodeService.java` delete 方法

```java
@Transactional
public void delete(Long qrCodeId) {
    QrCode qr = qrCodeRepo.findById(qrCodeId)
        .orElseThrow(() -> new IllegalArgumentException("活码不存在"));
    String configId = qr.getQrConfigId();

    // 1. 先标记 DB 为 deleted（事务内）
    qr.setStatus(QrCode.QrStatus.deleted);
    qrCodeRepo.save(qr);

    // 2. 后调 WeChat API（事务提交后，失败由对账扫描补偿）
    if (configId != null && !configId.isEmpty()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        wecomApi.deleteContactWay(configId);
                    } catch (WecomApiException e) {
                        log.error("WeChat 侧活码删除失败（由对账扫描补偿）: configId={}, errcode={}",
                            configId, e.getErrcode());
                    }
                }
            });
    }
}
```

---

### P1-5：GlobalAgentPoolService.takeStandby() 加悲观锁（C5）

**文件：** `GlobalAgentPoolRepository.java`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("SELECT p FROM GlobalAgentPool p WHERE p.status = :status ORDER BY p.sortOrder ASC")
List<GlobalAgentPool> findStandbysForUpdate(@Param("status") GlobalAgentPool.PoolStatus status);
```

**文件：** `GlobalAgentPoolService.java` takeStandby() 第 70 行

```java
// 改前
List<GlobalAgentPool> standbys = poolRepo.findByStatusOrderBySortOrder(...);

// 改后
List<GlobalAgentPool> standbys = poolRepo.findStandbysForUpdate(...);
```

---

### P1-6：meltedCount24h 每日清零（H1）

**文件：** `AgentRepository.java`

```java
@Modifying
@Query("UPDATE Agent a SET a.meltedCount24h = 0 WHERE a.meltedCount24h > 0")
int batchResetMeltedCount();
```

**文件：** `DailyResetWorker.java` — 在 dailyReset 末尾加：

```java
// 清零熔断计数，防止跨天累积永久封禁
int reset = agentRepo.batchResetMeltedCount();
if (reset > 0) log.info("熔断计数清零: {} 人", reset);
```

---

### P1-7：PatrolWorker 除零保护 + 告警静默修复（H2, H4）

**除零（H2）：** `PatrolWorker.java` checkOverloadedQrCodes()

```java
int dailyMax = a.getDailyMax();
if (dailyMax <= 0) {
    log.warn("员工 {} dailyMax={} 异常，跳过负载检查", a.getAgentUserid(), dailyMax);
    continue;
}
double ratio = (double) a.getDailyCurrent() / (double) dailyMax;
```

**告警静默（H4）：** 三处 `catch (Exception ignored) {}` 改为 `catch (Exception e) { log.error(...) }`：
- `WechatSyncHealingService.java:110`
- `QrCodeService.java:254`

---

### P1-8：QrCodeService.create() 加 null 检查（H3）

```java
JsonNode configIdNode = result.get("config_id");
JsonNode qrCodeNode = result.get("qr_code");
if (configIdNode == null || configIdNode.isNull()) {
    throw new IllegalStateException("企微 createContactWay 响应缺少 config_id");
}
if (qrCodeNode == null || qrCodeNode.isNull()) {
    throw new IllegalStateException("企微 createContactWay 响应缺少 qr_code");
}
String configId = configIdNode.asText();
String qrCodeUrl = qrCodeNode.asText();
```

---

### P1-9：系统以非 root 用户运行（H7）

**文件：** `deploy/bookstore-qrcode.service` 第 6 行

```ini
User=huoma
Group=huoma
```

**新增部署步骤**（`deploy/setup.sh` 或部署文档）：
```bash
sudo useradd -r -s /bin/false -d /opt/HuoMa huoma
sudo chown -R huoma:huoma /opt/HuoMa /var/log/huoma
```

---

### P1-10：日志轮转配置（H8）

**文件：** `src/main/resources/logback-spring.xml`（新建）

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

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

    <springProfile name="dev">
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>
    <springProfile name="prod,staging">
        <root level="INFO">
            <appender-ref ref="FILE"/>
            <appender-ref ref="ERROR_FILE"/>
        </root>
    </springProfile>
</configuration>
```

**同时**：systemd service 文件移除 `>> /var/log/huoma/stdout.log` 重定向（logback 自行写文件）。

---

### P1-11：WecomCallbackValidator 日志去 token（M9）

**文件：** `WecomCallbackValidator.java` 第 132-134 行

```java
// 改前
log.info("回调签名调试: token={}, ...", callbackToken, ...);

// 改后（不再记录 token 值）
log.info("回调签名调试: timestamp={}, nonce={}, encrypt前20字符={}",
    timestamp, nonce, encrypt.substring(0, Math.min(20, encrypt.length())));
```

---

### P1-12：下载中心 filter 重新验证员工活跃状态（M10）

**文件：** `DownloadAuthenticationFilter.java` 第 47-49 行

```java
// 在 session 校验后增加员工活跃状态验证
String userid = (String) session.getAttribute(SESSION_EMPLOYEE_USERID);
Employee employee = employeeRepo.findByUserid(userid).orElse(null);
if (employee == null || !employee.getActive()) {
    session.removeAttribute(SESSION_EMPLOYEE_USERID);
    response.sendRedirect("/download/oauth/entry");
    return;
}
```

需注入 `EmployeeRepository`（若尚未注入）。

---

### P1-13：TransferService 欢迎语用真实姓名（M8）

**文件：** `TransferService.java` 第 279 行

```java
// 改前
String teacherName = transfer.getToUserid();

// 改后（查 Employee 表获取真实姓名）
String teacherName = employeeRepo.findByUserid(transfer.getToUserid())
    .map(Employee::getName)
    .orElse(transfer.getToUserid());
```

需注入 `EmployeeRepository`。

---

### P1-14：deploy.sh 硬编码 IP 和 root 改为环境变量（M12）

**文件：** `deploy/deploy.sh` 第 8-9 行

```bash
SERVER_IP="${DEPLOY_SERVER_IP:?请设置 DEPLOY_SERVER_IP 环境变量}"
SERVER_USER="${DEPLOY_SERVER_USER:huoma}"
```

---

### P1-15：Stream NOGROUP 恢复空 catch 加日志（M15）

三处：`CallbackWorker.java:183`、`TagWorker.java:172`、`DataFillWorker.java:158`

```java
} catch (Exception e) {
    log.warn("Stream/ConsumerGroup 创建异常: {}", e.getMessage());
}
```

---

### P1-16：每次消费循环 TRIM 改为每 10 次（M16）

三处：`CallbackWorker.java`、`TagWorker.java`、`DataFillWorker.java`

```java
// 添加字段
private int trimCounter = 0;

// 消费循环中
if (++trimCounter % 10 == 0) {
    redisTemplate.opsForStream().trim(streamKey, maxLen, true);
}
```

---

### P1-17：AgentRotationService 锁 TTL 可配置（H6）

**文件：** `application.yml`

```yaml
app:
  agent:
    rotation-lock-ttl-seconds: ${AGENT_ROTATION_LOCK_TTL:60}
```

**文件：** `AgentRotationService.java`

```java
@Value("${app.agent.rotation-lock-ttl-seconds:60}")
private int rotationLockTtlSeconds;

// 使用处
Duration lockTtl = Duration.ofSeconds(rotationLockTtlSeconds);
```

---

### P1-18：AsyncConfig 拆出独立 batchExecutor（H11）

**文件：** `AsyncConfig.java`

```java
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

将所有 `@Async` 同步/批量方法的注解改为 `@Async("batchExecutor")`。

---

### P1-19：SchoolRateLimitFilter 窗口值用配置 + URI 精确匹配（M5, M6）

**文件：** `SchoolRateLimitFilter.java`

```java
// 窗口值
long windowSecs = windowMs / 1000;
redisTemplate.execute(RATE_SCRIPT,
    List.of(key, String.valueOf(maxPerMinute), String.valueOf(windowSecs)),
    String.valueOf(windowSecs * 2));

// URI 匹配
String uri = request.getRequestURI();
if (!uri.startsWith("/s/") && !uri.equals("/s")) return;
```

---

### P1-20：KEYS 改 SCAN（M7）

**文件：** `AgentDailyCountService.java`

```java
public void resetDailyCounts() {
    ScanOptions options = ScanOptions.scanOptions()
        .match(RedisConfig.DAILY_COUNT_KEY_PREFIX + "*").count(100).build();
    try (var cursor = redisTemplate.scan(options)) {
        while (cursor.hasNext()) {
            redisTemplate.unlink(cursor.next());
        }
    }
}
```

---

### P1-21：QL 搜索 Service 层前置过滤（M3）

**文件：** `QrCodeService.java` search 方法

```java
// 如果 keyword 用于 LIKE %...%，确保至少有一个精确筛选条件先走索引
// 在调用 search() 之前，如果 keyword 非空，先调用
if (StringUtils.hasText(keyword)) {
    // 先尝试精确匹配 schoolId
    QrCode exact = qrCodeRepo.findBySchoolId(keyword).orElse(null);
    if (exact != null) return List.of(exact);
}
// 再按城市/区县/状态精确筛选后模糊搜索
```

---

### P1-22：qrcode/create.html EmployeePicker XSS 修复（N35）

```javascript
// 改前：模板字面量直接拼 HTML
this.el.innerHTML = `...${it.name}...${it.userid}...`;

// 改后：用 DOM API 或确保 Thymeleaf 服务端转义生效
// 当前数据源来自服务器渲染的 window._employeeList，已被 Thymeleaf 转义
// 增加前端二次防御：在插入前对 name 和 userid 做 HTML 转义
function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
// 然后在模板字面量中使用 escapeHtml(it.name), escapeHtml(it.userid)
```

---

## 🟡 Phase 2：中等风险（改核心路径，需全量测试 + 灰度观察）

### P2-1：CallbackWorker 异常传播 + classifyWecomError 接入（N1, N2）

**这是本轮审计最关键的发现。**

**文件：** `CallbackWorker.java` — handleAddSuccess 方法

```java
private void handleAddSuccess(JsonNode event) {
    String externalUserId = getField(event, "external_userid");
    String userId = getField(event, "userid");
    String state = getField(event, "state");

    if (externalUserId == null || userId == null) {
        log.warn("添加成功事件缺少关键字段: external={}, userid={}", externalUserId, userId);
        return;
    }

    // ① 速率检测（非关键路径，异常不传播）
    try {
        rateLimiterService.recordAdd(userId);
    } catch (Exception e) {
        log.error("速率检测失败: userid={}", userId, e);
    }

    // ② 记录/更新客户信息 — 关键路径，失败必须传播
    Long customerId = customerService.upsertFromCallback(externalUserId, userId, state);

    // ③ 发布打标事件 — 失败传播（让消息入重试，而非静默丢弃）
    if (state != null) {
        Map<String, Object> tagEvent = new java.util.LinkedHashMap<>();
        tagEvent.put("external_userid", externalUserId);
        tagEvent.put("userid", userId);
        tagEvent.put("state", state);
        redisTemplate.opsForStream().add(
            RedisConfig.TAG_STREAM_KEY,
            Map.of("event", objectMapper.writeValueAsString(tagEvent)));
    }

    // ④ 员工日计数 — 非关键路径，失败不传播
    try {
        rotationService.incrementDailyCount(userId, state);
    } catch (Exception e) {
        log.error("日计数失败: userid={}, state={}", userId, state, e);
    }

    log.info("添加成功处理完成: external={}, userid={}, state={}, customerId={}",
        externalUserId, userId, state, customerId);
}
```

**关键变动：** ② 和 ③ 的 try-catch 移除。异常向上抛给 `consumeLoop`，由它调用 `classifyWecomError` 决定处理策略。

**文件：** `CallbackWorker.java` — consumeLoop 的 catch 块

```java
} catch (WecomApiException e) {
    ErrorAction action = MessageGuardService.classifyWecomError(e);
    switch (action) {
        case DLQ:
            // 永久故障 — 直接入 DLQ，不重试
            messageGuardService.sendToDlq(CALLBACK_STREAM_KEY, fields);
            ack(streamKey, group, msgId);
            break;
        case REFRESH_TOKEN_AND_RETRY:
            wecomApi.refreshToken();
            messageGuardService.markRetryOrDead(streamKey, group, msgId, fields, e.getMessage());
            break;
        case WAIT_AND_RETRY:
            if (e instanceof WecomRateLimitException) {
                long wait = ((WecomRateLimitException) e).getRetryAfterSeconds();
                Thread.sleep(wait * 1000);
            }
            messageGuardService.markRetryOrDead(streamKey, group, msgId, fields, e.getMessage());
            break;
        case RETRY:
        default:
            messageGuardService.markRetryOrDead(streamKey, group, msgId, fields, e.getMessage());
            break;
    }
} catch (Exception e) {
    messageGuardService.markRetryOrDead(streamKey, group, msgId, fields, e.getMessage());
}
```

**同样的改动也加到 TagWorker 和 DataFillWorker 的 consumeLoop 中。**

---

### P2-2：markRetryOrDead 加指数退避（N11）

**文件：** `MessageGuardService.java` markRetryOrDead

```java
if (retryCount <= MAX_RETRIES) {
    // 指数退避：第 N 次重试延迟 2^N 秒（capped at 60s）
    long delaySec = Math.min((long) Math.pow(2, retryCount), 60);
    // 将消息写入带延迟标记的 Stream
    Map<String, String> delayedFields = new LinkedHashMap<>(fields);
    delayedFields.put("_retry_at", String.valueOf(Instant.now().getEpochSecond() + delaySec));
    redisTemplate.opsForStream().add(streamKey, delayedFields);
    log.warn("消息处理失败，{}s 后重试 ({}/{}): stream={}, error={}",
        delaySec, retryCount, MAX_RETRIES, streamKey, errorInfo);
}
```

消费者在 `consumeLoop` 中检查 `_retry_at`：
```java
String retryAt = fields.get("_retry_at");
if (retryAt != null && Long.parseLong(retryAt) > Instant.now().getEpochSecond()) {
    // 尚未到重试时间，放回并跳过
    redisTemplate.opsForStream().add(streamKey, fields);
    ack(streamKey, group, msgId);
    continue;
}
```

---

### P2-3：CALLBACK_STREAM MAXLEN 从 10000 调至 50000（N12）

**文件：** `application.yml`

```yaml
app:
  redis-stream:
    callback-maxlen: ${CALLBACK_STREAM_MAXLEN:50000}  # was 10000
```

---

### P2-4：DB 复合索引（N10, N19-N22, H12）

**文件：** `schema.sql`（新增 DDL）

```sql
-- 高风险：无索引导致全表扫描
CREATE INDEX idx_qr_code_school_name ON qr_code (school_name);

-- 中风险：缺少复合索引 → 文件排序
CREATE INDEX idx_pool_status_sort ON global_agent_pool (status, sort_order);
CREATE INDEX idx_qr_agent_sort ON qr_agent (qr_code_id, sort_order);
CREATE INDEX idx_qr_agent_status ON qr_agent (qr_code_id, status);
CREATE INDEX idx_agent_qr_status ON qr_agent (agent_userid, status);
CREATE INDEX idx_alert_status_created ON agent_alert (status, created_at);
CREATE INDEX idx_alert_agent_type_status_created ON agent_alert (agent_userid, alert_type, status, created_at);
CREATE INDEX idx_alert_severity_created ON agent_alert (severity, created_at);
CREATE INDEX idx_alert_qr_code ON agent_alert (qr_code_id);
CREATE INDEX idx_rotate_qrcode_created ON qr_rotate_log (qr_code_id, created_at);
CREATE INDEX idx_log_userid_downloaded ON qr_download_log (agent_userid, downloaded_at);
CREATE INDEX idx_employee_active_name ON employee (active, name);
CREATE INDEX idx_customer_add_time_agent ON customer (add_time, added_agent);
CREATE INDEX idx_customer_add_time_qr ON customer (add_time, source_qr_id);
CREATE INDEX idx_customer_add_time_status ON customer (add_time, status);
CREATE INDEX idx_customer_tag_tag_id ON customer_tag (tag_id);
CREATE INDEX idx_operation_log_action_created ON operation_log (action, created_at);
CREATE INDEX idx_transfer_status_retry ON customer_transfer (status, retry_count);
```

---

### P2-5：CustomerService DataFill 降级修复（M4）

**文件：** `CustomerService.java` upsertFromCallback

```java
try {
    redisTemplate.opsForStream().add(RedisConfig.DATAFILL_STREAM_KEY, fields);
} catch (Exception e) {
    // 降级：标记客户需要数据修复，由 PatrolWorker 批量修复
    log.error("DataFill 事件发布失败，标记客户待修复: external={}", externalUserId, e);
    customer.setDataNeedsRepair(true);
    customerRepo.save(customer);
}
```

**文件：** `schema.sql`

```sql
ALTER TABLE customer ADD COLUMN data_needs_repair TINYINT(1) NOT NULL DEFAULT 0;
```

---

### P2-6：Undertow worker 线程数可配置（M18）

**文件：** `application.yml`

```yaml
server:
  undertow:
    threads:
      worker: ${UNDERTOW_WORKER_THREADS:20}
```

生产环境 `huoma.env` 中设置 `UNDERTOW_WORKER_THREADS=40`。

---

### P2-7：CustomerService 排行榜JPQL → 使用新索引

已建索引（P2-4），无需代码改动。索引 `idx_customer_add_time_agent` 和 `idx_customer_add_time_qr` 会自动加速 `findTopAdders` 和 `findTopQrCodes`。

---

### P2-8：EmployeeSyncService 全量加载优化（M3）

**文件：** `GlobalAgentPoolRepository.java`

```java
@Query("SELECT p.agentUserid FROM GlobalAgentPool p")
Set<String> findAllUserids();
```

**文件：** `EmployeeSyncService.java`

```java
// 改前：全表加载
List<Employee> employees = employeeRepo.findAllByActiveTrueOrderByName();
List<GlobalAgentPool> pools = poolRepo.findAll();

// 改后：只查不在池中的活跃员工
Set<String> poolUserids = poolRepo.findAllUserids();
List<Employee> newEmployees = employeeRepo.findByActiveTrueAndUseridNotIn(poolUserids);
```

---

### P2-9：QrCodeService.updateAgent() 非法 role 加日志（L3）

```java
} catch (IllegalArgumentException e) {
    log.warn("updateAgent 收到非法 role: qrCodeId={}, userid={}, role={}",
        qrCodeId, agentUserid, role, e);
}
```

---

### P2-10：WecomApiClient ObjectMapper 改为注入（L1）

```java
// 改前
private final ObjectMapper objectMapper = new ObjectMapper();

// 改后
private final ObjectMapper objectMapper; // @RequiredArgsConstructor 注入 Spring 管理的单例
```

---

### P2-11：findFailingUser 线性扫描加上限（L2）

```java
int maxLinearScan = Math.min(right, left + 10);
for (int i = left; i < maxLinearScan; i++) {
    // ...existing logic...
}
```

---

### P2-12：staging profile（M13）

**文件：** `application.yml`

```yaml
---
spring:
  config:
    activate:
      on-profile: staging
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:3306/bookstore_qrcode_staging?useSSL=true&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME:bookstore_staging}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 4
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      password: ${REDIS_PASSWORD}
  jpa:
    show-sql: false
  thymeleaf:
    cache: true
```

---

### P2-13：`deploy.sh` scp 前先停服务（N25）

```bash
# 2. 上传前先停止服务
echo "[2/5] 停止服务..."
ssh "${SERVER_USER}@${SERVER_IP}" "systemctl stop bookstore-qrcode || true"
```

---

### P2-14：PEL 回收 idle 阈值从 30s 提至 120s（H13）

**文件：** `PatrolWorker.java`

```java
// 改前
private static final long PEL_IDLE_MS = 30_000;

// 改后（给滚动重启留足缓冲）
private static final long PEL_IDLE_MS = 120_000;
```

---

### P2-15：schema.sql COLLATE 显式设置（N33）

```sql
-- 在所有 CREATE TABLE 末尾统一添加
DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

---

## 🟠 Phase 3：需团队协调

### P3-1：Git 历史清洗（C1）

```bash
# 1. 团队所有人提交并 push 所有本地工作
# 2. 仓库管理员执行清洗
pip install git-filter-repo
git filter-repo --path src/main/resources/application.yml \
  --blob-callback '
import re
def blob_callback(blob, meta):
    data = blob.data.decode("utf-8", errors="replace")
    data = re.sub(r"<YOUR_WECOM_CORP_SECRET>", "REMOVED", data)
    data = re.sub(r"<YOUR_WECOM_CALLBACK_TOKEN>", "REMOVED", data)
    data = re.sub(r"<YOUR_WECOM_AES_KEY>", "REMOVED", data)
    data = re.sub(r"<YOUR_DB_PASSWORD>", "REMOVED", data)
    data = re.sub(r"<YOUR_MYSQL_ROOT_PASSWORD>", "REMOVED", data)
    blob.data = data.encode("utf-8")
'
git push origin --force --all

# 3. 所有人重新 clone
```

---

### P3-2：加 OWASP Dependency-Check Maven Plugin

**文件：** `pom.xml` `<build><plugins>`

```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>10.0.4</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
    </configuration>
    <executions>
        <execution>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

### P3-3：加 `spring-security-test` 测试依赖

**文件：** `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 🔴 Phase 4：凭据轮换（须在 Phase 3 之前完成）

### 轮换顺序

1. **登录企业微信管理后台** → 应用管理 → 获取新 CorpSecret
2. **更新回调配置** → 生成新 Token 和 EncodingAESKey
3. **修改生产 DB 密码**：`ALTER USER 'bookstore'@'%' IDENTIFIED BY '<new_password>';`
4. **修改生产 Redis 密码**（如有）
5. **更新生产服务器 `huoma.env`**，写入新凭据
6. **重启应用**，验证企微回调正常
7. **更新 `deploy/huoma.env.template`**（占位符即可，不含真实值）

---

## 附录 A：改动文件清单

| 文件 | Phase | 改动类型 |
|------|-------|---------|
| `pom.xml` | P1-1, P3-2, P3-3 | 版本升级 + 新增 plugin/dependency |
| `application.yml` | P0-9, P0-10, P0-11, P1-17, P2-3, P2-6, P2-12 | 配置外置 + profile 新增 |
| `schema.sql` | P2-4, P2-5 | 新增索引 DDL + ALTER TABLE |
| `deploy/huoma.env.template` | P0-5 | 新建 |
| `deploy/bookstore-qrcode.service` | P0-3, P0-7, P1-9 | 修正路径、用户、JVM 参数 |
| `deploy/deploy.sh` | P0-4, P0-12, P1-14, P2-13 | 修正路径、健康检查、环境变量 |
| `deploy/nginx.conf` | P0-2, P0-8 | 安全头 + SSL + body size |
| `.gitignore` | P0-6 | 加 `*.env` 规则 |
| `src/main/resources/logback-spring.xml` | P1-10 | 新建 |
| `SecurityConfig.java` | P0-13 | metrics 鉴权 |
| `DownloadCenterController.java` | P0-14 | @Profile("dev") |
| `MessageGuardService.java` | P1-3, P2-2 | SHA-256 逻辑ID + DLQ 修复 + 退避 |
| `QrCodeService.java` | P1-4, P1-8, P1-21 | delete 原子性 + null 检查 + 搜索优化 |
| `GlobalAgentPoolService.java` | P1-5 | 悲观锁 |
| `GlobalAgentPoolRepository.java` | P1-5, P2-8 | FOR UPDATE 查询 + 投影查询 |
| `AgentRepository.java` | P1-6 | 批量清零熔断计数 |
| `DailyResetWorker.java` | P1-6 | 熔断计数清零 |
| `PatrolWorker.java` | P1-7, P2-14 | 除零保护 + PEL 阈值 |
| `WechatSyncHealingService.java` | P1-7 | 告警不静默 |
| `AgentRotationService.java` | P1-17 | 锁 TTL 可配置 |
| `AsyncConfig.java` | P1-18 | batchExecutor |
| `SchoolRateLimitFilter.java` | P1-19 | 窗口值动态化 + URI 精确匹配 |
| `AgentDailyCountService.java` | P1-20 | KEYS→SCAN |
| `CallbackWorker.java` | P2-1 | 异常传播 + classifyWecomError |
| `TagWorker.java` | P2-1, P1-15, P1-16 | classifyWecomError + TRIM优化 |
| `DataFillWorker.java` | P2-1, P1-15, P1-16 | classifyWecomError + TRIM优化 |
| `CustomerService.java` | P2-5 | DataFill 降级 |
| `EmployeeSyncService.java` | P2-8 | 按需查询 |
| `WecomCallbackValidator.java` | P1-11 | 去 token 日志 |
| `DownloadAuthenticationFilter.java` | P1-12 | 活跃状态验证 |
| `TransferService.java` | P1-13 | 真实姓名 |
| `QrCodeRepository.java` | P1-21 | 搜索优化 |
| `WecomApiClient.java` | P2-10 | ObjectMapper 注入 |
| `WechatSyncHealingService.java` | P2-11 | 线性扫描上限 |
| `dashboard-charts.js` | P1-2 | innerHTML → DOM API |
| `user/list.html` | P1-2 | confirm() 简化 |
| `qrcode/create.html` | P1-22 | HTML 转义 |
| `layout.html` 等 3 文件 | P0-1 | SRI 属性 |

---

## 附录 B：测试策略

| 阶段 | 测试内容 | 工具 |
|------|---------|------|
| Phase 0 | `./mvnw compile` 通过即可 | Maven |
| Phase 1 | 全部 12 个现有单元测试 + 新增 MessageGuardServiceTest | JUnit + Mockito |
| Phase 2 | 全量单元测试 + 手动回归（创建活码→回调→打标→数据填充 完整链路）| JUnit + 手动 |
| Phase 3 | 全量测试 + OWASP dependency-check 通过 | Maven plugin |
| Phase 4 | 手动验证企微回调 + 活码创建 + 扫码添加 | 生产环境 |

---

## 附录 C：不做

- ❌ 企微机器人告警推送（新功能，非风险修复）
- ❌ Flyway/Liquibase 迁移（与现有 schema.sql 机制冲突，需单独立项）
- ❌ 全局 API 限流器（需采集企微真实限流阈值后设计）
- ❌ 微服务拆分（架构决策，超出风险修复范围）
- ❌ CSP 强执行模式（先 Report-Only 观察，避免误杀合法脚本）
- ❌ JSON 格式日志（logback 暂用文本格式，后续迭代再加）
