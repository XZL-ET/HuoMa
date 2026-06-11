# 全局员工池重构 — 项目总结

> **日期**: 2026-06-11 ~ 2026-06-12  
> **分支**: `master`  
> **远程仓库**: `github.com/XZL-ET/HuoMa`  
> **总变更**: 20 commits · 71 files · +7,983 / −742 行

---

## 一、背景

### 问题

XX书店企微活码管理平台在客户扫码添加好友时，原架构存在以下问题：

1. **每码独立后备池** — 每个活码维护独立的 `qr_backup_pool`，员工在不同活码的后备池中重复出现
2. **日限额按活码独立计算** — 与实际企微限制（按员工全局统计）不匹配，可被跨码绕过
3. **在职继承自动触发** — 扫码回调中同步执行继承逻辑，阻塞主消费线程
4. **`@Async` 被绕过** — `this.method()` 调用不走 Spring AOP 代理，异步方法与事务注解失效
5. **角色复杂** — 员工分 service/receptionist/dual 三种角色，配置繁琐

### 目标

将系统重构为**全局共享员工池**架构，围绕 7 个核心设计：

| # | 设计要点 |
|---|----------|
| 1 | 全局员工池 `global_agent_pool` 替代每码独立后备池 `qr_backup_pool` |
| 2 | 单一全局日限额 `agent:daily:total:{userid}`，Redis INCR 原子计数 |
| 3 | 单码轮换 — 员工满员仅从当前活码下码，其他活码不受影响 |
| 4 | 在职继承改为管理员手动触发（删除 TransferWorker） |
| 5 | 简化角色 — 所有员工统一为 receptionist |
| 6 | 修复 `@Async`/`@Transactional` 被 `this.` 调用绕过 AOP 代理的 bug |
| 7 | 活码创建时支持多员工初始上码 + 自定义标签（扫码自动打标） |

---

## 二、实施阶段

### 阶段 1：全项目代码注释（8 commits）

在重构前为全部代码补全中文 Javadoc，确保重构后有完整的上下文参考。

| 提交 | 范围 | 文件数 |
|------|------|--------|
| `62ca955` | Entity 层 11 个实体类 | 10 |
| `153a33e` | Repository 层 11 个接口 | 11 |
| `119850d` | Service 层 8 个服务类 | 8 |
| `da3e3fd` | Controller 层 6 个控制器 | 6 |
| `c9f96d7` | WeCom API + Worker 层 8 个文件 | 8 |
| `e5405f5` | Config / DTO / Application 层 5 个文件 | 5 |
| `31929c9` | 10 个 Thymeleaf HTML 模板 | 10 |
| `64a399e` | pom.xml / application.yml / schema.sql | 3 |

---

### 阶段 2：全局员工池架构重构（7 commits）

#### 2.1 数据层 — 新建全局池表、实体、Repository

**文件**: `schema.sql` · `GlobalAgentPool.java` · `GlobalAgentPoolRepository.java`

- 新建 `global_agent_pool` 表：`agent_userid`(UNIQUE)、`daily_max`(默认200)、`daily_current`、`sort_order`、`status`(standby/full/blocked)
- 状态流转：`standby`(待命) → `full`(今日满员，午夜自动恢复) / `blocked`(管理员手动暂停)
- `qr_code` 表加 3 列：`transfer_target_userid`、`initial_agent_count`、`custom_tags`

#### 2.2 服务层 — 全局员工池服务

**文件**: `GlobalAgentPoolService.java`

核心方法：

| 方法 | 功能 |
|------|------|
| `takeStandby(Set<String> excludeUserids)` | 从池中取优先级最高的 standby，自动跳过排除列表中的员工 |
| `markFull(String agentUserid)` | 标记员工日限到达，standby → full |
| `updateDailyCurrent(String agentUserid, int count)` | 同步 Redis 全局计数到 DB |
| `ensureInPool(String userid, int dailyMax)` | 懒初始化 — 确保员工在池中（同步创建 Agent 记录） |
| `dailyReset()` | 每日 00:00 将所有 full 员工恢复为 standby，清零日计数 |

#### 2.3 核心服务 — AgentBindService 重构（改动最大）

**文件**: `AgentBindService.java`

**依赖变化**：
- 移除 `QrBackupPoolRepository`
- 新增 `GlobalAgentPoolRepository` + `GlobalAgentPoolService` + `AlertService`
- 新增 `@Lazy @Autowired private AgentBindService self`（自注入代理，打破循环依赖）

**核心流程**：

```
扫码回调 → incrementDailyCount()
  ├─ Redis INCR agent:daily:{userid}:{qrCodeId}（按码维度）
  ├─ Redis INCR agent:daily:total:{userid}（全局维度）
  ├─ TTL 设为次日凌晨 00:00
  ├─ DB 同步：QrAgent.dailyCurrent + GlobalAgentPool.dailyCurrent
  └─ self.checkAndRotate()（走 AOP 代理，@Transactional 生效）
        │
        ├─ globalCount >= dailyMax → expandQrCodeUsers()
        │     ├─ 分布式锁 rotate:lock:{qrCodeId}:expand (10s TTL)
        │     ├─ 构建排除列表（已在码员工）
        │     ├─ poolService.takeStandby(excludeUserids)
        │     ├─ 创建新 QrAgent(active) + 满员员工 status→full
        │     ├─ poolService.markFull(fullUserId)
        │     └─ afterCommit → self.syncQrCodeToWechatAsync()（异步企微同步）
        │
        ├─ globalCount >= urgentThreshold → preActivateBackup()
        │     ├─ 同上构建排除列表 + takeStandby
        │     └─ 仅加人不标记满员
        │
        └─ globalCount >= warnThreshold → 日志预警
```

**三级阈值机制**：

| 级别 | 阈值公式 | 动作 |
|------|----------|------|
| warn（预警） | `dailyMax * warnRatio / 100`（默认 80%） | 仅日志记录 |
| urgent（紧急） | `dailyMax * urgentRatio / 100`（默认 95%） | 提前激活后备，不标记满员 |
| full（日限） | `dailyMax`（100%） | 满员下码 + 池取人扩容 |

#### 2.4 QrCode 创建流程重构

**文件**: `QrCodeService.java` · `QrCodeCreateRequest.java`

- `create()` 新增步骤 4：`syncQrUsersToWechat(qr.getId())`，确保从池中补齐的员工同步到企微
- `bindAgents()` 重写：
  - 优先 `initialAgentUserids`（逗号分隔多员工）
  - 兼容旧格式 `serviceTeacherUserid`/`receptionistUserid`
  - 不足 `initialAgentCount` 时自动从全局池补齐
  - 增量化 `boundUserids` 传入 `takeStandby()` 防止重复
- `delete()` 移除后备池级联删除 — 全局池员工保留供其他活码使用
- `getBackups()` 从 `List<QrBackupPool>` 改为 `List<GlobalAgentPool>`

#### 2.5 Worker 层适配

**CallbackWorker.java**：
- `handleAddSuccess()` 从 5 步精简为 4 步
- 步骤③ 自动打标 → XADD 到 `wecom:tag:stream`（TagWorker 异步消费）
- 步骤⑤ 在职继承 → 删除（改为管理员手动触发）
- 移除 `TagService`/`TransferService` 依赖

**DailyResetWorker.java**：
- 步骤 2 新增 `poolService.dailyReset()` — 全局池 full→standby
- 步骤 3 `recoverFullAgents()` 恢复 QrAgent full→active（排除 blocked/melted）

**PatrolWorker.java**：
- `checkEmptyBackupPools()` → `checkGlobalPoolLow()`
- 告警规则：standby=0（完全枯竭）/ standby<5（严重不足）

**TransferWorker.java** → **删除**

#### 2.6 Redis 配置清理

**RedisConfig.java**：
- 新增 `TAG_STREAM_KEY`/`TAG_CONSUMER_GROUP`/`TAG_CONSUMER_NAME`（打标事件流）
- 移除 `TRANSFER_STREAM_KEY`/`TRANSFER_CONSUMER_GROUP`/`TRANSFER_CONSUMER_NAME`
- 移除 `transferConsumerGroup()` Bean

#### 2.7 数据迁移

**文件**: `docs/migration-global-pool.sql`

```sql
-- 1. qr_backup_pool → global_agent_pool（GROUP BY 去重，按最小 sortOrder 优先）
-- 2. active QrAgent → global_agent_pool（INSERT IGNORE 补齐遗漏）
-- 3. 更新 qr_code.initial_agent_count = 1
-- 幂等设计：全程 INSERT IGNORE / NOT EXISTS
```

---

### 阶段 3：全流程逻辑审查 + 修复（1 commit）

| 提交 | 问题 | 修复 |
|------|------|------|
| `916bf6d` | ① `DailyResetWorker` 未重置全局池 | 加 `poolService.dailyReset()` |
| | ② `checkAndRotate` 的 `@Transactional` 被 `this.` 绕过 | 改为 `self.checkAndRotate()` 代理调用 |
| | ③ `expandQrCodeUsers` 缺去重检查 | 加已在码员工判断 |
| | ④ 建码后未同步企微 | `create()` 加 `syncQrUsersToWechat()` |
| | ⑤ `preActivateBackup` 过时 guard | 移除 `activeReceptionists>0` 检查 + 加去重 |

---

### 阶段 4：逻辑问题修复（1 commit）

| 提交 | 问题 | 修复 |
|------|------|------|
| `d0010e6` | ① `AgentBindService.dailyReset()` 死代码（从未被调用） | 删除方法，类级注释更新引用 DailyResetWorker |
| | ② `takeStandby()` 可能反复返回已在码员工 → 死循环 | 加 `Set<String> excludeUserids` 参数，遍历跳过 |
| | ③ 全局池枯竭时无告警 | `expandQrCodeUsers`/`preActivateBackup` 加 `alertEmptyBackup()` |

---

### 阶段 5：生产部署兼容修复（1 commit）

| 提交 | 问题 | 修复 |
|------|------|------|
| `745488b` | ① `ADD COLUMN IF NOT EXISTS` 生产 MySQL 版本不支持 | 改为 INFORMATION_SCHEMA 动态 SQL |
| | ② Spring Boot 3.x 默认禁止循环引用 | `application.yml` 加 `spring.main.allow-circular-references: true` |
| | ③ 构造器自注入无法打破循环 | `self` 改为 `@Lazy @Autowired` 字段注入 |

**服务器**: `<YOUR_SERVER_IP>:8080` · 部署后 HTTP 200 正常

---

### 阶段 6：前端适配（1 commit）

| 提交 | 问题 | 修复 |
|------|------|------|
| `73b2587` | `detail.html` 后备池日限列硬编码 `200`、状态列写死 `待命` | 日限 → `th:text="${b.dailyMax}"`；状态 → 按 `b.status` 动态三色显示 |

---

## 三、架构对比

| 维度 | 旧架构 | 新架构 |
|------|--------|--------|
| 后备池 | `qr_backup_pool`（每码独立） | `global_agent_pool`（全局共享，一人一记录） |
| 日限额 | `QrAgent.dailyMax`（按码独立计数） | `GlobalAgentPool.dailyMax`（全局统一 Redis 计数） |
| 计数 Key | `agent:daily:{userid}:{qrCodeId}` | 同上 + `agent:daily:total:{userid}`（新增全局维度） |
| 轮换取人 | 从本码后备池取 | 从全局池 `takeStandby(excludeUserids)` |
| 员工角色 | service / receptionist / dual | 统一 receptionist |
| 在职继承 | CallbackWorker 自动触发 | 管理员手动触发（TransferWorker 已删除） |
| @Async 绕过 | `this.syncQrCodeToWechatAsync()` | `self.syncQrCodeToWechatAsync()`（代理调用） |
| 建码上人 | 单个服务老师 + 接待员 | 多员工 initialAgentUserids + 自动从池补齐 |
| 每日重置 | QrAgent full→active | 全局池 full→standby + QrAgent full→active |
| 巡检 | 按码检查后备池 | 全局池 standby 余量检查 |

---

## 四、关键文件清单

| 操作 | 文件 |
|------|------|
| **新建** | `GlobalAgentPool.java` · `GlobalAgentPoolRepository.java` · `GlobalAgentPoolService.java` · `TagWorker.java` · `docs/migration-global-pool.sql` · `docs/architecture-analysis.md` |
| **删除** | `TransferWorker.java` |
| **重写** | `AgentBindService.java`（轮换引擎核心） |
| **大幅修改** | `QrCodeService.java` · `QrCodeController.java` · `CallbackWorker.java` · `DailyResetWorker.java` · `PatrolWorker.java` · `RedisConfig.java` · `QrCodeCreateRequest.java` |
| **新增字段** | `QrCode.java` · `schema.sql` |
| **配置** | `application.yml` · `AsyncConfig.java` |
| **前端** | `detail.html` |
| **文档** | `docs/migration-global-pool.sql` · `docs/superpowers/plans/2026-06-12-global-agent-pool-plan.md` |

---

## 五、全流程闭环

```
建码（create）
  ├─ bindAgents: initialAgentUserids → ensureInPool → QrAgent(active)
  ├─ 池自动补齐: takeStandby(boundUserids)
  └─ syncQrUsersToWechat → 企微 API updateContactWay

客户扫码
  └─ WeChat 回调 → Controller XADD → CallbackWorker
       ├─ ① 速率检测
       ├─ ② 客户入库
       ├─ ③ XADD tag 事件（TagWorker 异步消费）
       └─ ④ incrementDailyCount
            ├─ Redis INCR × 2（按码 + 全局）
            ├─ DB 同步（QrAgent + GlobalAgentPool）
            └─ self.checkAndRotate（@Transactional 生效）
                 ├─ full → expandQrCodeUsers（锁/排除/取人/下码/企微同步）
                 ├─ urgent → preActivateBackup（锁/排除/取人/企微同步）
                 └─ warn → log

每日 00:00（DailyResetWorker）
  ├─ 清空 Redis agent:daily:*
  ├─ poolService.dailyReset（full→standby）
  ├─ recoverFullAgents（full→active，排除 blocked/melted）
  └─ generateDailyReport

每 5 分钟（PatrolWorker）
  ├─ checkGlobalPoolLow（standby=0 枯竭 / <5 不足）
  └─ checkOverloadedQrCodes（全员 >90% 日限）
```

---

## 六、容错设计

| 场景 | 处理方式 |
|------|----------|
| 全局池枯竭 | `takeStandby` 返回 null → `alertEmptyBackup` 高危告警 → 满员员工保持服务（不强制下码） |
| 并发扩容 | 分布式锁 `rotate:lock:{qrCodeId}:expand`，SET NX EX 10s |
| 事务回滚 | `afterCommit` 不触发 → 企微不同步失效数据 |
| @Async 绕过 | `@Lazy @Autowired self` 字段注入 + 代理调用 |
| 已在码员工被重复获取 | `takeStandby(excludeUserids)` 跳过 + 行内注释说明 |
| MySQL < 8.0.29 | INFORMATION_SCHEMA 动态 SQL 检查列存在性 |
| DB 连接断开 | HikariCP 连接池自动重连 |
| Redis 断开 | Worker 消费循环 sleep 5s 重试 |

---

## 七、未完成的后续事项

1. **数据迁移** — `docs/migration-global-pool.sql` 需在生产数据库执行（表已建，数据迁移脚本幂等可随时执行）
2. **全局池管理 UI** — 目前管理后台可查看全局备用池，但无独立的全局池管理页面（添加/移除/排序员工影响全平台）
3. **迁移后清理** — 确认全局池稳定运行后，可删除 `qr_backup_pool` 表
4. **生产监控** — 关注 `alertEmptyBackup` 告警频率、全局池 standby 余量趋势、日限到达率

---

> 🤖 本文档由 Claude Code 根据 2026-06-11 ~ 2026-06-12 全流程对话自动生成。
