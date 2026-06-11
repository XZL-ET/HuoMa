# XX书店 · 企业微信活码管理平台 — 架构与运行逻辑分析

> 生成日期：2026-06-10
> 基于源码：`master` 分支，Spring Boot 3.2.5 + Java 17

---

## 一、项目概览

| 维度 | 说明 |
|------|------|
| **技术栈** | Spring Boot 3.2.5, Spring Data JPA, MySQL, Redis, Thymeleaf + htmx, Undertow |
| **业务场景** | 书店与学校合作"家校服务"，为每个学校生成企业微信活码，家长扫码加好友后系统自动分配服务老师、打标签、日限管控、流量调度 |
| **部署方式** | JAR 包独立运行，`server.port=8080` |
| **包结构** | `controller` / `service` / `entity` / `repository` / `wecom` / `worker` / `config` / `dto` |

---

## 二、核心数据模型

### 2.1 ER 关系图

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│   qr_code    │────▶│     qr_agent     │◀────│    agent     │
│   (活码)      │     │  (活码-员工关联)   │     │   (员工)     │
└──────────────┘     └──────────────────┘     └──────────────┘
       │                      │
       │               ┌──────┴──────┐
       │               │qr_backup_pool│  (后备池)
       │               └─────────────┘
       ▼
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   customer   │────▶│ customer_tag │◀────│     tag      │
│   (客户)      │     │ (客户-标签)   │     │   (标签)     │
└──────────────┘     └──────────────┘     └──────────────┘
       │
       ▼
┌──────────────────┐     ┌──────────────┐     ┌──────────────┐
│ customer_transfer│     │  agent_alert │     │ daily_report │
│   (继承记录)      │     │  (异常告警)   │     │   (日报)     │
└──────────────────┘     └──────────────┘     └──────────────┘
```

### 2.2 各表职责

| 表名 | 记录数规模 | 核心字段 | 职责 |
|------|-----------|---------|------|
| **qr_code** | 百~千级 | school_id, qr_config_id, qr_url, status, rotate_mode, warn_ratio, urgent_ratio, custom_tags | 一个学校 = 一个活码 = 一个企微"联系我"二维码 |
| **agent** | 十~百级 | userid, name, role, daily_total_cap, overall_status, melted_count_24h | 员工档案，全局状态（normal/warning/blocked/melted） |
| **qr_agent** | 千~万级 | qr_code_id, agent_userid, role, daily_max, daily_current, status | 员工在某活码下的日限和状态（active/full/removed/blocked） |
| **qr_backup_pool** | 千级 | qr_code_id, agent_userid, sort_order, status, daily_max | 活码后备接待员池，按优先级排队 |
| **customer** | 万~十万级 | external_userid, added_agent, current_agent, source_qr_id, school_id | 扫码客户，记录来源活码和归属员工 |
| **tag** | 百~千级 | name, type, parent_id, wecom_tag_id | 标签字典，分 system/form/manual 三类，含企微标签 ID |
| **customer_tag** | 十万~百万级 | customer_id, tag_id, source | 客户-标签多对多关联 |
| **agent_alert** | 千级 | agent_userid, alert_type, severity, auto_action, status | 异常告警记录，支持自动处理 |
| **customer_transfer** | 万级 | customer_id, from_userid, to_userid, status, greeting_sent | 在职继承流水 |
| **daily_report** | 天级 | date, total_add, total_transfer, active_qr, blocked_agent | 每日运营数据快照 |

### 2.3 核心概念定义

| 概念 | 说明 |
|------|------|
| **活码 (qr_code)** | 企微"联系我"二维码，一个学校一个，家长扫码添加书店企微好友 |
| **服务老师 (service)** | 活码主联系人，负责最终服务客户，每个活码 1~N 个 |
| **接待员 (receptionist)** | 后备池中的员工，服务老师日限满了后被激活上码分流 |
| **后备池 (backup_pool)** | 预配置的接待员列表，按 sort_order 优先级排队 |
| **在职继承 (transfer)** | 企微原生能力：客户从接待员转移给服务老师 |
| **熔断 (melt)** | 员工触发风控规则后自动从所有活码移除，30 分钟冷却 |
| **扩容 (expand)** | 服务老师日限满了，从后备池激活接待员加入企微活码 |

---

## 三、完整请求链路

### 3.1 全景图

```
家长扫码 ──▶ 企微服务器 ──▶ POST /api/wecom/callback ──▶ Redis Stream
                                                              │
                                                    ┌─────────▼──────────┐
                                                    │  CallbackWorker    │
                                                    │  (异步消费线程)     │
                                                    └─────────┬──────────┘
                                                              │
                                  ┌───────────────────────────┼───────────────────────┐
                                  ▼                           ▼                       ▼
                          ① 速率检测                    ② upsert 客户            ③ 自动打标
                          RateLimiterService           CustomerService          TagService
                          (滑窗算法, 防风控)             (新建或更新客户)          (市→区→学校)
                                  │                           │                       │
                                  ▼                           ▼                       ▼
                          ④ 日计数 +1                   ⑤ 在职继承               ⑥ 企微API打标
                          AgentBindService              TransferService          WecomApiClient
                          (满员→触发扩容)                (接待员→服务老师)
```

### 3.2 逐步骤详解

#### ① 速率检测 — `RateLimiterService.recordAdd()`

```
Redis Sorted Set 滑窗:
  rate:{userId}:15s  →  15秒内 > 20人 → 降速警告（日志 + 100ms 微延迟）
  rate:{userId}:60s  →  1分钟内 > 60人 → 触发熔断（调 AlertService.meltAgent）
```

使用 Redis ZSET，score 为 Unix 时间戳，member 为 `timestamp:nanotime`。每次 INCR 后清理过期数据（`ZREMRANGEBYSCORE`），通过 `ZCARD` 获取窗口内计数。

#### ② 客户记录 — `CustomerService.upsertFromCallback()`

```
external_userid 查 DB
    ├── 已存在 → 更新 current_agent、source_qr_id（用户可能扫了新活码）
    │           如果之前是 deleted → 恢复为 active
    │
    └── 不存在 → 调企微 API 取客户详情（昵称/头像/unionid/type）
                 → INSERT 新记录
```

#### ③ 自动打标 — `TagService.autoTag()`

```
state(学校ID)
    │
    ▼
查 qr_code → 取出 city / district / schoolName / custom_tags
    │
    ├── 市标签 → getOrCreateTag(city, system)
    ├── 区标签 → getOrCreateTag(district, system, parent=市标签)
    ├── 学校标签 → getOrCreateTag(schoolName, system, parent=区标签)
    └── 自定义标签 → getOrCreateTag(每个逗号分隔的标签名, system)
    │
    ▼
企微 API: markTag(external_userid, userid, [wecom_tag_ids])
本地: customer_tag 关联写入（UNIQUE 约束去重）
```

**标签懒创建策略**：
1. 先从本地 `tag` 表查
2. 不存在 → 调企微 API `add_corp_tag` 创建 → 拿到 wecom_tag_id → 写入本地
3. 已存在但无 wecom_tag_id → 从企微标签列表按名称匹配补同步
4. 所有标签归入"家校服务"标签组，`cachedGroupId` 内存缓存避免重复查企微

#### ④ 日计数 + 扩容判断 — `AgentBindService.incrementDailyCount()`

```
Redis INCR agent:daily:{userid}:{qrCodeId}     ← 该活码下日计数
Redis INCR agent:daily:total:{userid}           ← 该员工全局日计数
TTL = 今夜 00:00 的秒数

同步到 DB: qr_agent.daily_current = newCount

检查三级阈值（当前计数 vs 日上限 × 配置百分比）:
    ├── currentCount >= dailyMax (100%)
    │       → expandQrCodeUsers() 扩容
    │
    ├── currentCount >= dailyMax × urgentRatio (默认 95%)
    │       → preActivateBackup() 提前激活后备
    │
    └── currentCount >= dailyMax × warnRatio (默认 80%)
            → 仅日志记录
```

#### ⑤ 在职继承 — `TransferService.initiate()`

```
根据 state 找到活码 → 取 active 状态的服务老师
    │
    ▼
企微 API: transferCustomer(接待员, 服务老师, 客户 external_userid)
    │
    ├── errcode=0 → 写入 customer_transfer (status=pending_confirm)
    │               记录 form_filled_at_transfer (客户是否已填收集表单)
    │
    └── errcode≠0 → 写入 customer_transfer (status=api_failed, 记录 fail_reason)
```

### 3.3 扩容流程详解 — `expandQrCodeUsers()`

```
┌──────────────────────────────────────────────────────┐
│  前置检查                                              │
│  ① 获取 Redis 分布式锁 rotate:lock:{qrCodeId}:expand  │
│  ② 如果 rotateMode=manual → 只告警不自动扩容           │
│  ③ 如果后备池无 standby 接待员 → 告警并返回            │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│  执行扩容                                              │
│  ① 取后备池 sort_order 最小的 standby 接待员           │
│  ② 标记该后备为 activated                              │
│  ③ 创建 QrAgent 记录（role=receptionist, 使用后备的     │
│     daily_max, status=active）                        │
│  ④ 满员员工标记为 full + 记录 replaced_by              │
│  ⑤ syncQrUsersToWechat() — 更新企微活码用户列表         │
│     (服务老师 active 才上码 + 接待员 active 才上码)      │
│  ⑥ 写 qr_rotate_log（轮换/扩容日志）                    │
│  ⑦ 释放 Redis 锁                                      │
└──────────────────────────────────────────────────────┘
```

**提前激活 `preActivateBackup()`** 逻辑类似，但不标记任何人 full，只在紧急阈值（95%）时提前把后备接待员加入活码。

---

## 四、并发模型：扫码后的线程处理

### 4.1 三层线程架构

```
Undertow IO 线程池          Redis Stream            单线程消费
(HTTP 请求处理)             (消息缓冲队列)            (业务处理)

  请求1 ──▶ XADD ──▶ ┌──────────────────┐
  请求2 ──▶ XADD ──▶ │ wecom:callback:  │    ┌──────────────────┐
  请求3 ──▶ XADD ──▶ │     stream       │───▶│ consumeLoop()    │
  请求4 ──▶ XADD ──▶ │                  │    │ (单线程 while 循环)│
  ...                 └──────────────────┘    └──────────────────┘
                                                     │
  秒回200 ◀────────────── 不等待 ◀────────────────────┘
```

### 4.2 各层详细说明

#### 第一层：Undertow Worker 线程池（多线程，< 20ms）

```java
// WecomCallbackController.receive() — 跑在 Undertow worker 线程上
// 1. 解密 XML（纯 CPU）
// 2. 提取关键字段（纯字符串）
// 3. XADD 写入 Redis Stream（一次网络 IO）
// 4. 立即返回 "success"
// 全程不查 DB，不调企微 API，不阻塞
```

如果企微同时涌入 100 个回调，Undertow 线程池用多线程并发处理，每个请求都在 20ms 内完成 XADD 后立即返回。

#### 第二层：Redis Stream（消息缓冲队列）

- Key: `wecom:callback:stream`
- 消费者组: `callback-worker-group`
- 消费者: `worker-1`（仅 1 个）
- ACK 机制保证每条消息至少处理一次
- 即使服务重启，未 ACK 的消息可重新消费

#### 第三层：CallbackWorker 单线程消费（顺序执行）

```java
// CallbackWorker.consumeLoop() — 单线程无限循环
while (running) {
    // 每次拉最多 50 条
    List<MapRecord> records = redisTemplate.opsForStream().read(
        Consumer.from(GROUP, "worker-1"),
        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
    );

    if (records == null || records.isEmpty()) {
        Thread.sleep(100);   // 空队列时休眠 100ms
        continue;
    }

    // 逐条顺序处理，同一线程内同步执行
    for (MapRecord record : records) {
        try {
            processEvent(eventJson);    // ①→②→③→④→⑤ 完整处理
            ack(record);                // 处理成功才 ACK
        } catch (Exception e) {
            log.error("处理失败", e);    // 异常不 ACK，下次重新消费
        }
    }
}
```

**为什么不并发消费？**

| 原因 | 说明 |
|------|------|
| 天然防并发冲突 | 同一活码的后备池激活、同一员工的日计数，不会被多个线程同时修改 |
| 消息顺序保证 | 先扫码的先处理，扩容/轮换顺序不会乱 |
| 降低 DB 竞争 | 不会出现两个线程同时 UPDATE 同一条 `qr_agent` |
| 代码简单可靠 | 不需要到处加锁 |

### 4.3 并发安全机制

虽然业务处理是单线程的，但以下操作仍需额外保护：

| 机制 | 应用场景 | 实现方式 |
|------|---------|---------|
| **Redis 原子操作** | 日计数 INCR、滑窗 ZADD/ZCARD | Redis 命令本身是原子的，多线程并发也不会丢数 |
| **Redis 分布式锁** | 扩容 `expandQrCodeUsers()` | `SETNX` + TTL 10 秒，防止同时扩容和提前激活 |
| **数据库 UNIQUE 约束** | `customer_tag` 打标去重 | `(customer_id, tag_id)` 唯一键，`try-catch` 吞重复异常 |
| **`@Transactional`** | 扩容、创建活码、继承发起 | 多表写操作要么全成功要么全回滚 |

### 4.4 三个并发时序示例

```
时间轴 ──────────────────────────────────────────────────▶

家长A扫码    家长B扫码    家长C扫码
   │            │            │
   ▼            ▼            ▼
┌──────────────────────────────────────┐
│      Undertow Worker 线程池          │  ◀── 多线程并行
│  线程1: 解密→XADD                    │
│  线程2: 解密→XADD                    │
│  线程3: 解密→XADD                    │
│        全部 < 20ms 返回              │
└──────────────────┬───────────────────┘
                   │  消息顺序写入
                   ▼
┌──────────────────────────────────────┐
│         Redis Stream                 │  ◀── 消息缓冲
│   msg1: A扫码  msg2: B扫码  msg3: C  │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│    CallbackWorker (单线程)           │  ◀── 顺序消费
│                                      │
│  msg1:                               │
│    ① 速率检测(Redis 滑窗, O(1))      │
│    ② 记录客户(DB INSERT/UPDATE)      │
│    ③ 打标(企微API + DB)              │
│    ④ 日计数+1(Redis INCR)            │
│    ⑤ 检查满员→扩容(DB + 企微API)     │
│    ⑥ 在职继承(企微API)              │
│    ACK msg1                          │
│                                      │
│  msg2: ← msg1 完全处理完才开始       │
│    ...                               │
│                                      │
│  msg3: ← msg2 完全处理完才开始       │
│    ...                               │
└──────────────────────────────────────┘
```

### 4.5 如果流量大了怎么办？

当前设计下，单线程消费约能处理 **每秒几十到上百个回调**。如需提升吞吐：

1. **增加消费者数量**（Redis Stream 消费者组天然支持）：
   ```java
   // 从 1 个 worker 扩展到 4 个
   for (int i = 0; i < 4; i++) {
       callbackExecutor.execute(() -> consumeLoop("worker-" + i));
   }
   ```
   但需注意：多消费者引入并发，需要扩展分布式锁的覆盖范围。

2. **批量调用企微 API**：打标、发消息等操作可以合并批量请求。

3. **读写分离**：将客户记录等查询操作走只读从库。

4. **当前架构已够用**：书店家校场景的扫码量不是秒杀级，单线程顺序处理完全满足需求。

---

## 五、定时任务体系

### 5.1 每日重置 — `DailyResetWorker`

**触发时间**：每天 00:00 (`@Scheduled(cron = "0 0 0 * * *")`)

```
① 清空 Redis 日计数 keys (agent:daily:*)
    └── redisTemplate.delete(keys)

② 恢复 full 状态的员工 → active，清零 daily_current
    └── 排除 overall_status = blocked / melted 的
    └── 记录 last_reset_at
    └── 调用 syncQrUsersToWechat() 让老员工重新上码

③ 生成昨日日报 (daily_report)
    ├── 今日新增客户数
    ├── 继承总数/成功数
    ├── 异常总数
    ├── 活跃活码数 / 满员活码数
    └── 被封员工数 / 熔断员工数
```

### 5.2 定时巡检 — `PatrolWorker`

**触发时间**：每 5 分钟 (`@Scheduled(cron = "0 */5 * * * *")`)

```
① 扫描活跃活码中后备池为空的 → 告警
    └── alertService.alertEmptyBackup()

② 扫描所有接待员都 > 90% 日限的活码 → 告警
    └── alertService.createAlert(type=traffic_spike)

③ 统计今日异常汇总
```

### 5.3 继承追踪 — `TransferMonitorWorker`

**触发时间**：每 10 分钟 (`@Scheduled(cron = "0 */10 * * * *")`)

```
遍历所有 pending_confirm 的继承记录:
    ├── 调 wecomApi.getTransferResult() 查询企微结果
    ├── TRANSFER_SUCCEED → 确认 + 发交接欢迎语
    ├── TRANSFER_FAIL/REFUSED → 标记拒绝
    ├── TRANSFER_WAIT → retry_count + 1
    └── retry_count > 144 (≈24h) → 超时标记
```

**中午 12 点**额外执行超时记录清理 (`@Scheduled(cron = "0 0 12 * * *")`)

---

## 六、异常监控与自动处置

### 6.1 告警等级与自动动作

| 告警类型 | 触发条件 | 严重度 | 自动动作 |
|---------|---------|--------|---------|
| add_fail (84061 频率过高) | 企微风控回 84061 | **high** | **melted** — 立即熔断 |
| add_fail (累计) | 1h 内 ≥ 5~10 次 | **high** | **paused** — 暂停员工 |
| greeting_fail (累计) | 1h 内 ≥ 5 次 | **high** | **paused** — 暂停员工 |
| melt (累计) | 24h 内 ≥ 3 次熔断 | **high** | **blocked** — 升级封禁 |
| empty_backup | 后备池为空 | **high** | none — 人工处理 |
| traffic_spike | 全员日限 > 90% | **medium** | none — 人工关注 |

### 6.2 熔点 → 熔断 → 封禁 升级链

```
正常 (normal)
    │
    │  触发风控 (84061 / 1min>60人)
    ▼
熔断 (melted) — 30分钟冷却
    │
    │  24h 内累计 ≥ 3 次
    ▼
封禁 (blocked) — 需人工解封
```

### 6.3 速率控制两阶段

```
阶段一：15 秒窗口 > 20 人
    → 降速警告（日志 + Thread.sleep(100ms)）

阶段二：1 分钟窗口 > 60 人
    → 触发熔断（调 AlertService.meltAgent）
    → 设置 overall_status = melted
    → 从所有活码下架
```

---

## 七、管理后台

### 7.1 页面路由

| 路由 | 模板 | 功能 |
|------|------|------|
| `GET /` | 重定向 | → `/qrcodes` |
| `GET /qrcodes` | `qrcode/list` | 活码列表（分页、搜索、按城市/区/状态筛选） |
| `GET /qrcodes/create` | `qrcode/create` | 手动创建活码表单 |
| `GET /qrcodes/batch-import` | `qrcode/batch-import` | Excel 批量导入 |
| `GET /qrcodes/{id}` | `qrcode/detail` | 活码详情（联系人管理、后备池、轮换日志） |
| `GET /customers` | `customer/list` | 客户列表（搜索、筛选） |
| `GET /customers/{id}` | `customer/detail` | 客户详情（含标签） |
| `GET /agents` | `agent/list` | 员工管理 |
| `GET /alerts` | `alert/list` | 异常告警 |
| `GET /dashboard` | `dashboard/index` | 数据看板 |

### 7.2 活码管理操作

| 操作 | 接口 | 说明 |
|------|------|------|
| 创建活码 | `POST /qrcodes/create` | → 企微 API 创建联系我二维码 → 写入 DB → 绑定员工 |
| 批量导入 | `POST /qrcodes/batch-import` | 异步解析 Excel，Redis Hash 跟踪进度 |
| 删除活码 | `POST /qrcodes/{id}/delete` | 删除企微活码 + 清理关联数据 |
| 添加联系人 | `POST /qrcodes/{id}/agents` | 接待员加入活码 |
| 编辑联系人 | `POST /qrcodes/{id}/agents/{agentId}/update` | 修改日限、角色、排序 |
| 移除联系人 | `POST /qrcodes/{id}/agents/{agentId}/remove` | 标记 removed（服务老师不可移除） |
| 添加后备 | `POST /qrcodes/{id}/backups` | 加入后备池 |
| 调整后备优先级 | `POST /qrcodes/{id}/backups/{backupId}/move` | up/down 交换 sort_order |
| 切换轮换模式 | `POST /qrcodes/{id}/rotate-mode` | auto ↔ manual |
| 设置阈值 | `POST /qrcodes/{id}/thresholds` | warn_ratio / urgent_ratio (1-100) |
| 同步企微 | `POST /qrcodes/{id}/sync` | 同步 active 员工列表到企微活码 |
| 暂停/启用 | `POST /qrcodes/{id}/toggle-status` | active ↔ paused |
| 更新样式 | `POST /qrcodes/{id}/style` | 主题色、引导文案、是否显示学校名 |
| 下载二维码 | `GET /qrcodes/{id}/download` | 单个 PNG (72/300 dpi) |
| 批量下载 | `POST /qrcodes/batch-download` | ZIP 包 |

---

## 八、企微 API 集成

### 8.1 `WecomApiClient` 封装的 API

| API | 用途 | HTTP 方法 |
|-----|------|----------|
| `gettoken` | 获取 access_token（7200s 缓存，200s 提前刷新） | GET |
| `add_contact_way` | 创建"联系我"活码 | POST |
| `update_contact_way` | 更新活码用户列表 | POST |
| `del_contact_way` | 删除活码 | POST |
| `add_corp_tag` | 创建企业标签（含标签组） | POST |
| `get_corp_tag_list` | 获取企业标签列表 | POST |
| `mark_tag` | 为客户打标签 | POST |
| `transfer_customer` | 发起在职继承 | POST |
| `get_transfer_result` | 查询继承结果 | POST |
| `externalcontact/get` | 获取客户详情 | GET |
| `externalcontact/list` | 获取员工客户列表 | GET |
| `user/simplelist` | 获取部门成员列表（递归子部门） | GET |
| `message/send` | 发送文本消息给客户 | POST |

### 8.2 access_token 管理

```java
public synchronized String getAccessToken() {
    if (token 未过期) return token;
    调企微 API 获取新 token;
    cache(token, 过期时间 = now + 7200 - 200);
    return token;
}
```

- `synchronized` 保证并发安全（CAS 也可，但 token 刷新频率极低）
- 提前 200 秒刷新，避免边界过期

### 8.3 回调安全

- **签名校验**：SHA1(Token + Timestamp + Nonce + Encrypt) 排序后拼接
- **消息解密**：AES-256-CBC（Base64 解码 EncodingAESKey → 解密 → PKCS#7 去填充 → 去掉 random(16B) + msg_len(4B) + corpId 尾部）
- **CDATA 处理**：企微 XML 回调使用 `<![CDATA[...]]>` 包裹内容，解析时两种格式都兼容

---

## 九、关键技术决策

| 决策 | 选择 | 原因 |
|------|------|------|
| Web 服务器 | Undertow 替代 Tomcat | 更轻量、NIO 性能更好 |
| 消息队列 | Redis Stream 替代 RabbitMQ | 减少外部依赖，满足流量需求 |
| 前端方案 | Thymeleaf + htmx 替代前后端分离 | 管理后台交互简单，降低复杂度 |
| 日计数 | Redis INCR 替代 DB UPDATE | 原子操作 + TTL 自动过期 |
| 速率控制 | Redis ZSET 滑窗 | 精确到秒级的滑动窗口计数 |
| 并发控制 | 单线程消费 + 分布式锁 | 天然避免竞态，代码简单可靠 |
| 标签同步 | 懒创建 + 缓存 | 减少企微 API 调用次数 |
| 批量导入 | 异步 + Redis Hash 进度 | 不阻塞 HTTP 请求，可查进度 |

---

## 十、部署配置

### 10.1 环境变量

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `DB_PASSWORD` | MySQL 密码 | `<YOUR_MYSQL_ROOT_PASSWORD>` (dev) |
| `WECOM_CORP_ID` | 企微企业 ID | `ww36b412d53f0fe0c6` (dev) |
| `WECOM_CORP_SECRET` | 企微应用 Secret | (dev 内置) |
| `WECOM_CALLBACK_TOKEN` | 回调签名 Token | `<YOUR_WECOM_CALLBACK_TOKEN>` (dev) |
| `WECOM_CALLBACK_AES_KEY` | 回调加解密 AES Key | (dev 内置) |

### 10.2 Profile

- **dev**：`application.yml` (spring.profiles.active: dev) — 本地 MySQL + Redis，Thymeleaf 不缓存
- **prod**：需配置全部环境变量，Thymeleaf 开启缓存，SSL 连接数据库

### 10.3 线程池配置

| Bean | 核心线程 | 最大线程 | 队列 | 用途 |
|------|---------|---------|------|------|
| `callbackExecutor` | 4 | 8 | 5000 | Redis Stream 消费者线程 |
| `taskExecutor` | 2 | 4 | 1000 | 批量导入等异步任务 |

---

## 十一、总结：一条扫码请求的完整旅程

```
1. 家长扫学校活码 → 添加书店企微好友

2. 企微服务器回调 POST /api/wecom/callback (加密 XML)
   └── Undertow 线程池中某线程处理

3. WecomCallbackController.receive()
   ├── SHA1 验签
   ├── AES-256-CBC 解密 XML
   ├── 快速字符串提取 Event/UserID/ExternalUserID/State
   ├── XADD Redis Stream (wecom:callback:stream)
   └── 返回 "success" (< 20ms)

4. CallbackWorker (单线程) 异步消费:
   ├── ① RateLimiterService.recordAdd()    — Redis 滑窗防封
   ├── ② CustomerService.upsertFromCallback — 新建/更新客户
   ├── ③ TagService.autoTag()               — 市/区/学校/自定义标签
   ├── ④ AgentBindService.incrementDailyCount — 日计数 + 满员→扩容
   └── ⑤ TransferService.initiate()         — 接待员→服务老师

5. 定时任务:
   ├── 00:00 DailyResetWorker    — 清零计数 + 恢复员工 + 日报
   ├── 每5分钟 PatrolWorker      — 后备池检查 + 全员高负载告警
   └── 每10分钟 TransferMonitor  — 追踪继承结果 + 发欢迎语

6. 管理后台: /qrcodes (活码管理) /customers (客户管理) /dashboard (看板)
```
