# 火马平台综合风险修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) for tracking.

**Goal:** 修复 55 项安全/可靠性/性能风险，覆盖 5 个 Phase，涉及 ~40 个文件

**Architecture:** 按风险等级分阶段执行。每阶段独立编译、独立测试、独立提交。

**Tech Stack:** Spring Boot 3.2.12, Apache POI 5.4.0, Redis Streams, MySQL, Nginx, systemd

**Spec Reference:** `docs/superpowers/specs/2026-06-20-comprehensive-risk-fix.md`

## Global Constraints

- Spring Boot 版本: 3.2.12 (非 3.3.x)
- Apache POI 版本: 5.4.0
- 不引入 JPA 关系注解 (CLAUDE.md)
- 不引入 Flyway/Liquibase (与 schema.sql 冲突)
- 不修改 WeChat Work API 调用签名
- 所有密码/密钥不得出现在代码或配置文件中
- 编译通过 (`./mvnw compile`) + 12 个现有单元测试全绿 = 每阶段最低门禁
- 每阶段完成后才进入下一阶段

---

## 🔵 Phase 0：零风险（14 项，5 个 Task）

### Task 0-A：SRI + 安全响应头 + nginx 强化
**覆盖：** P0-1, P0-2, P0-8
**文件：** `deploy/nginx.conf`, 5 个 HTML layout 文件
**独立可测：** nginx -t 语法检查

- [ ] `layout.html`: 5 个 CDN 标签加 integrity + crossorigin
- [ ] `download/layout.html`: 同上
- [ ] `school/layout.html`: 同上
- [ ] `deploy/nginx.conf`: 加 security headers + SSL + client_max_body_size + YOUR_DOMAIN 注释

### Task 0-B：JVM 参数 + systemd 修正 + 非 root 用户
**覆盖：** P0-3, P0-7, P1-9
**文件：** `deploy/bookstore-qrcode.service`
**独立可测：** 语法检查

- [ ] JVM: 加 `-XX:+ExitOnOutOfMemoryError -XX:HeapDumpPath=/var/log/huoma/ -Duser.timezone=Asia/Shanghai`
- [ ] After= 改为 `network.target nginx.service`
- [ ] User=huoma, Group=huoma

### Task 0-C：deploy.sh 路径对齐 + 健康检查 + IP 外置 + 先停后部署
**覆盖：** P0-4, P0-12, P1-14, P2-13
**文件：** `deploy/deploy.sh`
**独立可测：** bash -n 语法检查

- [ ] JAR 路径对齐到 `/opt/HuoMa/target/bookstore-qrcode-0.1.0.jar`
- [ ] 健康检查改用 SSH 本地 curl `/actuator/health`
- [ ] SERVER_IP/SERVER_USER 改为环境变量
- [ ] scp 前先 `systemctl stop`

### Task 0-D：huoma.env 模板 + .gitignore + .service 路径修正
**覆盖：** P0-5, P0-6, N5
**文件：** `deploy/huoma.env.template` (NEW), `.gitignore`
**独立可测：** 检查模板包含所有变量

- [ ] 新建 `deploy/huoma.env.template` 含全部环境变量文档
- [ ] `.gitignore` 加 `*.env` / `!*.env.template` / `!*.env.example`

### Task 0-E：application.yml 配置外置 + 凭据去硬编码 + Actuator 保护 + dev-login 保护
**覆盖：** P0-9, P0-10, P0-11, P0-13, P0-14, P1-17, P2-3, P2-6
**文件：** `application.yml`, `SecurityConfig.java`, `DownloadCenterController.java`
**独立可测：** `./mvnw compile` 通过；dev profile 启动无密码报错

- [ ] prod datasource.url: `localhost` → `${DB_HOST:localhost}`
- [ ] prod redis.host: `localhost` → `${REDIS_HOST:localhost}`
- [ ] DB_PASSWORD: 移除 fallback `${DB_PASSWORD:<YOUR_DB_PASSWORD>}` → `${DB_PASSWORD}`
- [ ] REDIS_PASSWORD: 移除 fallback → `${REDIS_PASSWORD}`
- [ ] dev WeCom secrets: 移除硬编码真实密钥，改为空/占位
- [ ] school-entry-url: `http://<YOUR_SERVER_IP>:8080/s` → `${SCHOOL_ENTRY_URL:http://localhost:8080/s}`
- [ ] SecurityConfig: `/actuator/metrics/**` → `hasRole("ADMIN")`
- [ ] DownloadCenterController: dev-login 加 `@Profile("dev")`
- [ ] 加 `app.agent.rotation-lock-ttl-seconds` 配置项
- [ ] 加 `app.redis-stream.callback-maxlen` 默认 50000
- [ ] 加 `server.undertow.threads.worker` 默认 20

---

## 🟢 Phase 1：低风险（22 项，8 个 Task）

### Task 1-A：Spring Boot 3.2.5→3.2.12 + POI 5.2.5→5.4.0
**覆盖：** P1-1 (N3, N4)
**文件：** `pom.xml`
**验证：** `./mvnw clean test` — 12 个现有测试必须全绿

- [ ] `<version>3.2.12</version>` in parent
- [ ] `poi-ooxml` version → 5.4.0

### Task 1-B：前端 XSS 修复（3 处）
**覆盖：** P1-2 (N8, N18)
**文件：** `dashboard-charts.js`, `user/list.html`
**验证：** 页面渲染无报错

- [ ] 排行榜: innerHTML → createElement + textContent
- [ ] 漏斗图: innerHTML → DOM API
- [ ] user/list.html: confirm() 移除 data-name 拼接

### Task 1-C：MessageGuardService DLQ 三 Bug + 指数退避
**覆盖：** P1-3 (C2, C3, C4), P2-2 (N11)
**文件：** `MessageGuardService.java`
**验证：** 写 MessageGuardServiceTest 单元测试

- [ ] hashCode → SHA-256 deterministic logical ID (computeLogicalId)
- [ ] replayAllDlq/replayDlq: 重放前 delete retry counter
- [ ] sendToDlq: fail-open (DLQ 写失败不抛异常)
- [ ] markRetryOrDead: 指数退避 2^N 秒 capped at 60s
- [ ] 消费者 consumeLoop 检查 `_retry_at` 字段

### Task 1-D：QrCodeService 三修复
**覆盖：** P1-4 (H5), P1-8 (H3), P1-21 (M3)
**文件：** `QrCodeService.java`
**验证：** `./mvnw test`

- [ ] delete(): 先 DB 标记 deleted → afterCommit 调 WeChat API
- [ ] create(): config_id/qr_code null 检查抛 IllegalStateException
- [ ] search(): keyword 先精确匹配 schoolId → 再模糊搜索

### Task 1-E：GlobalAgentPoolService 悲观锁
**覆盖：** P1-5 (C5)
**文件：** `GlobalAgentPoolRepository.java`, `GlobalAgentPoolService.java`
**验证：** `./mvnw test`

- [ ] Repository: 新增 `findStandbysForUpdate()` — `@Lock(PESSIMISTIC_WRITE)` + 3s timeout
- [ ] Service: takeStandby() 改用 `findStandbysForUpdate`

### Task 1-F：熔断计数清零 + 告警静默修复 + 除零保护
**覆盖：** P1-6 (H1), P1-7 (H2, H4)
**文件：** `AgentRepository.java`, `DailyResetWorker.java`, `PatrolWorker.java`, `WechatSyncHealingService.java`, `QrCodeService.java`
**验证：** `./mvnw compile` + 逻辑审查

- [ ] AgentRepository: `batchResetMeltedCount()` 批量 UPDATE
- [ ] DailyResetWorker: 每日调用清零
- [ ] PatrolWorker: dailyMax<=0 时 continue
- [ ] 3 处 catch(Exception ignored){} → catch(Exception e){log.error(...)}

### Task 1-G：Worker 优化（NOGROUP 日志 + TRIM 频率 + AsyncConfig batchExecutor）
**覆盖：** P1-15 (M15), P1-16 (M16), P1-18 (H11)
**文件：** `CallbackWorker.java`, `TagWorker.java`, `DataFillWorker.java`, `AsyncConfig.java`
**验证：** `./mvnw compile`

- [ ] 三 Worker: NOGROUP catch 块加 `log.warn`
- [ ] 三 Worker: TRIM 每 10 次循环
- [ ] AsyncConfig: 新增 `batchExecutor` bean (CallerRunsPolicy)

### Task 1-H：日志 + 安全 + 业务小修复（6 项合并）
**覆盖：** P1-10 (H8), P1-11 (M9), P1-12 (M10), P1-13 (M8), P1-19 (M5, M6), P1-20 (M7)
**文件：** `logback-spring.xml` (NEW), `WecomCallbackValidator.java`, `DownloadAuthenticationFilter.java`, `TransferService.java`, `SchoolRateLimitFilter.java`, `AgentDailyCountService.java`
**验证：** `./mvnw compile`

- [ ] 新建 `logback-spring.xml`: 30d 滚动 + 90d Error 日志
- [ ] WecomCallbackValidator: 去 token 日志
- [ ] DownloadAuthenticationFilter: 重新验证员工活跃状态 (inject EmployeeRepository)
- [ ] TransferService: 欢迎语用真实姓名 (inject EmployeeRepository)
- [ ] SchoolRateLimitFilter: windowSecs 动态计算 + URI `/s/` 精确匹配
- [ ] AgentDailyCountService: KEYS → SCAN 批量删除

---

## 🟡 Phase 2：中等风险（15 项，6 个 Task）

### Task 2-A：CallbackWorker/TagWorker/DataFillWorker 异常传播 + classifyWecomError
**覆盖：** P2-1 (N1, N2)
**文件：** `CallbackWorker.java`, `TagWorker.java`, `DataFillWorker.java`
**验证：** 完整链路手动回归（创建活码→回调→打标→数据填充）

- [ ] CallbackWorker.handleAddSuccess: 移除 ② ③ try-catch，异常向上传播
- [ ] consumeLoop catch: switch(ErrorAction) — DLQ / REFRESH_TOKEN_AND_RETRY / WAIT_AND_RETRY / RETRY
- [ ] TagWorker/DataFillWorker consumeLoop: 同样 classWecomError 接入

### Task 2-B：DB 复合索引 + schema COLLATE
**覆盖：** P2-4 (N10, N19-N22, H12), P2-15 (N33), P2-5 (M4)
**文件：** `schema.sql`
**验证：** DDL 在测试库执行无报错

- [ ] 新增 16 个复合索引 DDL
- [ ] 所有 CREATE TABLE 统一 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
- [ ] customer 表加 `data_needs_repair` 列

### Task 2-C：CustomerService DataFill 降级
**覆盖：** P2-5 (M4)
**文件：** `CustomerService.java`
**验证：** `./mvnw test`

- [ ] upsertFromCallback: DataFill Stream 写入失败 → 标记 dataNeedsRepair
- [ ] Customer entity: 加 dataNeedsRepair 字段

### Task 2-D：EmployeeSyncService 全量→按需查询
**覆盖：** P2-8 (M3)
**文件：** `GlobalAgentPoolRepository.java`, `EmployeeSyncService.java`
**验证：** `./mvnw test`

- [ ] GlobalAgentPoolRepository: `findAllUserids()` 投影查询
- [ ] EmployeeSyncService: 只查不在池中的活跃员工

### Task 2-E：WecomApiClient ObjectMapper 注入 + findFailingUser 线性扫描上限 + staging profile
**覆盖：** P2-10 (L1), P2-11 (L2), P2-12 (M13)
**文件：** `WecomApiClient.java`, `WechatSyncHealingService.java`, `application.yml`
**验证：** `./mvnw compile`

- [ ] WecomApiClient: ObjectMapper 改为 @RequiredArgsConstructor 注入
- [ ] WechatSyncHealingService: findFailingUser 线性扫描上限 left+10
- [ ] application.yml: 新增 staging profile

### Task 2-F：PEL 阈值 + 排行榜索引确认
**覆盖：** P2-14 (H13), P2-7
**文件：** `PatrolWorker.java`
**验证：** `./mvnw compile`

- [ ] PatrolWorker: PEL_IDLE_MS 30s → 120s
- [ ] 确认索引 idx_customer_add_time_agent/idx_customer_add_time_qr 覆盖排行榜查询

---

## 🟠 Phase 3：需协调（3 项，2 个 Task）

### Task 3-A：Git 历史清洗
**覆盖：** P3-1 (C1)
**文件：** 所有 commit 历史
**验证：** 清洗后 clone 全新仓库 → `git log -p | grep -E "(secret|password|token)"` 无敏感信息
**前置条件：** 团队所有人提交 + push → 管理员执行 → 所有人重新 clone

### Task 3-B：OWASP + spring-security-test
**覆盖：** P3-2, P3-3
**文件：** `pom.xml`
**验证：** `./mvnw dependency-check:check` 无 CVSS≥7 告警

- [ ] pom.xml 加 OWASP dependency-check-maven plugin
- [ ] pom.xml 加 spring-security-test test dependency

---

## 🔴 Phase 4：凭据轮换（1 项，文档）

### Task 4-A：凭据轮换
**覆盖：** Phase 4
**文件：** 生产环境变量 + 企微管理后台
**验证：** 人工验证企微回调 + 活码创建 + 扫码添加 正常
**前置条件：** Phase 0-E 代码已部署（移除硬编码 fallback）

---

## 执行顺序

```
Phase 0 (A→B→C→D→E) 并行可
    ↓ compile + 12 tests pass
Phase 1 (A→B→C→D→E→F→G→H) 部分并行
    ↓ compile + 12 tests pass + 新增 unit tests pass
Phase 2 (A→B→C→D→E→F) 顺序执行（A 必须先于其他）
    ↓ 全量测试 + 手动回归
Phase 3 (A必须先于B) → Phase 4
```

## 改动文件清单（按 Phase）

| Phase | 文件数 | 文件列表 |
|-------|--------|---------|
| 0 | 12 | nginx.conf, bookstore-qrcode.service, deploy.sh, huoma.env.template(NEW), .gitignore, application.yml, SecurityConfig.java, DownloadCenterController.java, layout.html, download/layout.html, school/layout.html |
| 1 | 15 | pom.xml, dashboard-charts.js, user/list.html, MessageGuardService.java, QrCodeService.java, GlobalAgentPoolRepository.java, GlobalAgentPoolService.java, AgentRepository.java, DailyResetWorker.java, PatrolWorker.java, WechatSyncHealingService.java, CallbackWorker.java, TagWorker.java, DataFillWorker.java, AsyncConfig.java, logback-spring.xml(NEW), WecomCallbackValidator.java, DownloadAuthenticationFilter.java, TransferService.java, SchoolRateLimitFilter.java, AgentDailyCountService.java |
| 2 | 7 | CallbackWorker.java, TagWorker.java, DataFillWorker.java, schema.sql, CustomerService.java, GlobalAgentPoolRepository.java, EmployeeSyncService.java, WecomApiClient.java, WechatSyncHealingService.java, application.yml, PatrolWorker.java |
| 3 | 1 | pom.xml + git history |
| 4 | 0 | 仅外部操作 |
