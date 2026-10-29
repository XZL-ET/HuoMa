# 告警降噪与可操作化设计

- 日期：2026-10-29
- 范围：`agent_alert` 告警的产生、关闭与展示
- 现状数据来自生产 RDS（2026-10-29 上午）

## 1. 问题与证据

`/alerts` 当前有 **2,140 条未处理告警**，用户在页面上无法判断先做什么。

按类型分布（status=open）：

| 类型 | open 条数 | 最早 | 最新 |
|---|---:|---|---|
| qr_agent_anomaly | 895 | 09-30 12:30 | 10-29 08:40 |
| wechat_unavailable | 374 | 09-30 12:03 | 10-29 08:40 |
| inheritance_no_service | 270 | 09-30 12:00 | 10-29 08:30 |
| inheritance_ambiguous_service | 269 | 09-30 12:00 | 10-29 08:30 |
| traffic_spike | 168 | 09-30 18:45 | 10-23 20:15 |
| service_teacher_anomaly | 78 | 10-01 03:07 | 10-29 03:07 |
| anomaly_replace_shortfall | 78 | 10-01 03:07 | 10-29 03:07 |
| transfer_retry_exhausted_svc | 40 | 09-30 12:00 | 10-29 01:30 |
| daily_anomaly_heal | 39 | 10-01 03:07 | 10-29 03:07 |
| leak_tag_skip_missing_qrcode | 17 | 10-01 10:25 | 10-25 21:10 |
| daily_reset_failure | 10 | 10-01 00:00 | 10-10 00:00 |
| transfer_dlq_backlog | 2 | 10-10 14:45 | 10-10 14:45 |

`wechat_unavailable` 的 374 条里 **358 条 detail 含 `errcode=48002`**，是交接/降级消息被企微拒绝，文案却写成“请恢复该员工的客户联系权限”——归因错误，运维照做无效。

三个根因：

1. **只追加、不去重**：`AlertService.createAlert` 每次无条件 INSERT。同一条件只要还在，就按各自的节流窗口持续产新行：
   - `qr_agent_anomaly`：PatrolWorker 每小时一条；两个服务老师自 07-28 起长期异常 → 约 20 条/天；
   - `inheritance_no_service` / `inheritance_ambiguous_service`：每天 08:30 批次各一条；
   - `service_teacher_anomaly` / `anomaly_replace_shortfall`：每天 03:07 自愈批次各活码各一条。
2. **条件恢复后不回写**：只有人工点“解决”，代码里没有“异常消失 → 关闭旧告警”的路径。`daily_reset_failure` 已 19 天没再出现，10 条旧告警仍挂着。
3. **页面不指向动作**：列表按时间倒序，类型筛选只列 5 个旧类型；除“关联活码”外没有跳转入口；48002 文案误导。

另有一个潜在噪声炸弹：`PatrolWorker.checkOverloadedQrCodes` 每 5 分钟对每个全员高负载的活码建一条 `traffic_spike`，无节流；一旦某校满负荷，一天可产生 288 条。

## 2. 目标

1. 打开 `/alerts` 默认看到“待处理事项”分组，每组一句话、影响数量、一个处理入口。
2. 未处理数从 2,140 降到真实待办量级（目标 < 30），且不再日增。
3. 每条告警都有明确去处（可点）；无法自动处理的，给出下一步动作。
4. 历史全部保留（`auto_resolved`），可按类型/时间查。

## 3. 非目标

- 不改各 Worker 的触发阈值与风控策略（只改“告警怎么记、怎么关、怎么展示”）。
- 不重建告警系统：沿用 `agent_alert` 表与 `open / resolved / auto_resolved` 状态机。
- 不做站外通知（短信/企微/邮件）。
- 不解决 48002 本身（换接口是另一件事），只把它从 358 条噪声收敛成 1 条准确说明。

## 4. 核心设计

### 4.1 创建侧：条件类告警 upsert

在 `AlertService.createAlert` 内部按类型分流：

- **条件类**（同一条待办，条件持续存在就更新，不新增）：
  `qr_agent_anomaly`、`wechat_unavailable`、`wechat_unavailable_service`、`wechat_48002`（新）、
  `inheritance_no_service`、`inheritance_ambiguous_service`、`empty_backup`、
  `service_teacher_anomaly`、`anomaly_replace_shortfall`、`anomaly_replace_blocked`、
  `daily_anomaly_heal`、`traffic_spike`、`*_stream_backlog`、`transfer_dlq_backlog`、
  `daily_reset_failure`、`deletion_report_fail`、`leak_*`、`qr_sync_failed_on_create`、`qr_service_removed`、
  `dlq_replay_exhausted`、`employee_departed_service`。
- **事件类**（每次发生是独立事实，保留逐条）：`add_fail`、`melt`、`transfer_fail`、`transfer_retry_exhausted_svc`。

条件类 upsert 规则：

1. 查找 `status=open` 且 `alert_type + agent_userid + qr_code_id` 相同的行（null 视为 null 匹配）。
2. 命中：更新 `detail`、`last_seen_at=now`、`occurrence_count+1`、`severity=max(旧,新)`，返回原行。
3. 未命中：正常插入，并显式写 `last_seen_at=now`、`occurrence_count=1`。
4. `created_at` 保持不变（用于看“这个问题多久了”）。

合并键的两个特例：

- `traffic_spike` 只用 `(type, null, qr_code_id)` —— 现有代码把 `agentUserid` 取为“第一位接待员”，接待员顺序一变键就变，会产生合并不掉的第二条 open。
- 全局类（继承两类、`*_stream_backlog`、`transfer_dlq_backlog`、`dlq_replay_exhausted`、`daily_reset_failure`、`deletion_report_fail`）key 为 `(type, null, null)`，即每种一条。

两机并发 upsert 可能各插一条；由 4.3 的每小时去重收敛兜底，不引入唯一索引（MySQL 对含 NULL 的唯一键无法约束这种情况）。

### 4.2 条件恢复：自动关闭

条件消失时由产生方主动关单，不依赖通用超时：

| 条件 | 关闭位置 | 判定 |
|---|---|---|
| `qr_agent_anomaly` | `PatrolWorker.patrolQrAgentAnomalies` | `anomalyCount == 0` → `resolveOpenByType("qr_agent_anomaly")` |
| `traffic_spike` | `PatrolWorker.checkOverloadedQrCodes` | 本轮算出高负载活码集合后，查 DB 中 open 的 `traffic_spike` 行，`qrCodeId` 不在集合内的逐条关闭 |
| `inheritance_no_service` / `inheritance_ambiguous_service` | `InheritanceJob.transferBatch` | **仅当本次为全量扫描**（夜间 08:30 批次，`fullScan=true`）且列表为空 → 关闭对应类型 |
| `service_teacher_anomaly` / `anomaly_replace_shortfall` / `anomaly_replace_blocked` | `PatrolWorker.dailyAnomalyAutoHeal`（03:07，遍历全部 active 活码） | 批次汇总本轮命中过的 key，open 行中不在该集合的逐条关闭 |
| `*_stream_backlog` | `StreamBacklogMonitor` | 该流 backlog/PEL 回落阈值以下 → 关闭该 `alertType` |
| `transfer_dlq_backlog` | `TransferMonitorWorker.checkDlq` | `dlqLen <= 0` → 关闭 |
| `dlq_replay_exhausted` | `PatrolWorker.autoReplayDlq` | 本轮实际执行重放后 `summary.exhausted() == 0` → 关闭；被节流跳过时不动；另有 7 天兜底归档 |
| `daily_reset_failure` | `DailyResetWorker` | 当日重置成功 → 关闭 |
| `deletion_report_fail` | `DeletionReportService` | 下一次删除报告生成成功 → 关闭；另有 7 天兜底归档 |
| `employee_departed_service` | `EmployeeSyncService` 同步入库 | 该员工本轮同步为在职 → 按 agent 关闭；另有 30 天兜底归档 |
| `empty_backup` | `PatrolWorker.checkGlobalPoolLow` | 池恢复 standby 且高于补充阈值 → 关闭 |

关闭统一写：`status=auto_resolved`、`resolvedAt=now`、`resolvedBy="auto:condition-cleared"`、日志 info。

两条判定原则（实现时不要简化掉）：

- **不拿“本轮列表为空”当恢复信号**，除非本轮是覆盖全量的扫描。继承类的 `noServiceSchools` 只在增量扫描命中 dirty 学校、且活码有接待员时才填充，白天列表为空只代表“今天没有脏学校”；`traffic_spike` 的“上一轮集合”是进程内存，双机轮流持锁或重启后必然误判。一律按 DB 中 open 行 vs 本轮实际异常集合做差。
- **真实待办不设超时**：`service_teacher_anomaly` / `anomaly_replace_shortfall` / `anomaly_replace_blocked` 只在条件解除时关闭，不按天归档——它们指向还需要人去处理的员工/活码（如当前 3543/3551 的两位服务老师）。
- **失败不等于恢复**：批次里单个活码处理抛异常时，该活码的 key 必须按“仍在异常”处理（在调用前登记 key，而不是成功后登记），否则一次异常会把真实告警误关。巡检本身失败时同理：不关闭任何告警。

### 4.3 每小时维护任务：去重 + 过期归档

新增 `AlertMaintenanceJob`：

- 调度：每小时 `:20` 一次；应用启动完成后额外跑一次（`ApplicationReadyEvent`），用于上线当次清空历史积压。
- 分布式锁：`lock:job:alert-maintenance`，TTL 10 分钟，复用 `ScheduledJobGuard.runExclusive`（两机只跑一台）。
- 步骤：
  1. **合并重复**：按 4.1 的**合并键**分组（`traffic_spike` 只按活码、全局类按类型，不能直接用三列原始值分组，否则旧数据里带 `agent_userid` 的 `traffic_spike` 会另立一组合不掉）。保留 `id` 最小（最早）的一条，其余置 `auto_resolved`（`resolvedBy="auto:dedup"`）——`created_at` 列不可更新，存活最早那条才能让“这个问题多久了”保持真实。存活行合并时：`occurrence_count=组内求和`、`last_seen_at=组内最大`、`severity=取高`、`detail` 取最新。只处理条件类，事件类不动。
  2. **过期归档**：
     - `daily_anomaly_heal`：自愈已完成，`created_at` 早于 24h 全部归档（新产生的保留 24h 供查看）。
     - `traffic_spike`：`last_seen_at`（无则 `created_at`）早于 24h 归档。
     - `wechat_48002`：早于 24h 归档（每天最多一条新的）。
     - `qr_agent_anomaly`：`last_seen_at` 早于 7 天归档（巡检本身故障时的兜底，避免永久残留）。**按 `last_seen_at` 而非 `created_at`**——upsert 不更新 `created_at`，按它归档会让持续存在的异常每周被归档一次又重新出现。
     - `dlq_replay_exhausted` / `deletion_report_fail`：`last_seen_at` 早于 7 天归档。
     - `employee_departed_service`：`last_seen_at` 早于 30 天归档（此时已远超一次离职交接的合理处理周期）。
     - `wechat_unavailable` 且 `detail LIKE '%无客户联系权限%'`：全部归档（一次性清理 358 条历史污染；规则长期保留，防止旧版本回滚再次写入）。**不要用 `errcode=48002` 做条件**——`WechatSyncHealingService` 自愈失败的真实告警文案里也带 `errcode=%d`，一旦数值是 48002 会把“员工企微不可用已被移除”这类真实待办一起清掉。
     - 事件类：`transfer_retry_exhausted_svc` 14 天、`add_fail` / `melt` / `transfer_fail` 30 天后归档。
     - **不归档**：`inheritance_*`、`service_teacher_anomaly`、`anomaly_replace_shortfall`、`anomaly_replace_blocked`、`empty_backup`、`wechat_unavailable`（非 48002）、`wechat_unavailable_service`、`*_stream_backlog`、`transfer_dlq_backlog`、`daily_reset_failure`、`leak_*`、`qr_*` —— 这些要么由 4.2 的条件关闭负责，要么是必须人处理完才能消失的真实待办。
  3. 批量 500 条一页，记录日志：`告警维护: 合并 M 条、归档 N 条`。

首次运行预期：895→1、374→约 16（保留非 48002 的同步类，每条员工一条）、270→1、269→1、168→0、78→约 2、78→约 2、39→0、358 条 48002→0；剩余约 10–30 条真实待办。其中 `service_teacher_anomaly` / `anomaly_replace_*` 合并后仍有 2–4 条，指向 3543/3551 两位自 07-28 起异常的服务老师——这是真实待办，保留。

### 4.4 48002 从“员工权限”改为“系统级能力缺失”

`OutboundMsgWorker.alertNoPermission` 改为：

- 类型 `wechat_48002`，`agentUserid=null`，`qrCodeId=null`，severity `low`。
- 全局节流：Redis 键 `alert:48002:tick`，**只约束“是否新建行/写日志”**；每次命中都照常把当天计数写进那条唯一的 open 行。
- detail 记录当天失败次数（用 Redis INCR 计数，键 `alert:48002:count:{yyyyMMdd}`，TTL 2 天），按自然日归零。口径是“次”不是“人”：欢迎语按员工去重、交接欢迎语按客户逐条，两者单位不同，混在一起只讲人次会失真。
- 每次命中走 upsert：`(type, null, null)` 命中已有 open 行 → 更新 `detail`（今日人数）、`last_seen_at`、`occurrence_count+1`；只有当天还没有行时才新建。这样“今日 N 人”不会停在第一次的值。
- 文案：
  `企微接口 48002（本企业未开通家校沟通能力）：欢迎语/降级消息/交接欢迎语无法送达，今日 N 次发送失败。这不是员工权限问题，无需在企微后台逐个恢复；自 2026-07-21 起持续存在，需更换发送方案。`
- 不再逐员工创建；`WechatSyncHealingService` 的 `wechat_unavailable` / `wechat_unavailable_service` 语义不变（那是真实的同步失败）。

**另一个 48002 来源（已定纳入）**：交接欢迎语走 `TransferService` 的另一条发送路径，**根本不经过 `alertNoPermission`**（永久失败被静默标记为已发送，见问题第 1 条，约 865 次/天）。若不计入，卡片上的“今日 N 人”会明显低估 48002 的实际影响。做法（只加计数，不改发送逻辑）：

- 在 `TransferService` 的“永久失败 → 标记已发”分支里，对同一个 `alert:48002:count:{yyyyMMdd}` 计数 +1；
- 不改发送逻辑、不修 48002 本身、不新增告警行——只是让那条系统级说明的数字诚实。

### 4.5 告警页改版

**顶部：待处理事项卡片**

- 按类型聚合所有 open 告警，只显示数量 > 0 的类型；按“最高严重级别 → 条数 → 最近时间”排序。
- 每张卡片：
  - 标题（人话）+ 严重级别色条；
  - 影响：`N 条 · 最近 MM-dd HH:mm`；条件类显示“涉及 X 个活码 / Y 名员工”；
  - 建议动作一句话；
  - 按钮「去处理 →」跳转（见 4.6）。
- 该分区只做“导航”，点击卡片滚动/跳转到下方对应筛选。

**中部：统计**
保留现有状态/严重度/时间统计，但把“未处理”拆成两个数：`需要处理（actionable）` 与 `已自动恢复/可归档（informational）`。

**下部：明细表**
- 默认 `status=open`（当前默认是全部状态）。
- 新增列：`最近发生`（`lastSeenAt`，无则 `createdAt`）、`次数`（`occurrenceCount`）。
- 操作列：`去处理 →`（按类型）+ `✔ 解决`。**条件类**的“解决”按钮文案改为「标记已处理」并附提示：条件仍在时，下一轮巡检会重新出现——否则用户点了“解决”5 分钟后看到同一条又冒出来，会认为功能坏了。
- 类型筛选下拉改为从统一元数据生成，覆盖全部类型的中文名；不再硬编码 5 个。元数据里没有的未知类型回退为原始 `alert_type` + 无链接，不报错。
- 删除「一键解决全部」。替换为「归档可自动恢复/过期项」：只对 informational 类型执行 4.3 的过期规则，避免把真实待办一把抹掉。

### 4.6 类型元数据与深链

新增 `AlertTypeMeta`（代码内静态表）：`type → 中文名、标题、建议动作、severity 参考、是否 informational、链接生成器`。

链接映射（全部指向“已登录即可访问”的页面；`/transfers` 仅 ADMIN，不使用）：

| 类型 | 链接 | 按钮文案 |
|---|---|---|
| qr_agent_anomaly / daily_anomaly_heal | `/qrcodes/verify-all-agents` | 查看异常员工 |
| service_teacher_anomaly / anomaly_replace_shortfall / anomaly_replace_blocked / empty_backup / leak_* / qr_sync_failed_on_create / qr_service_removed / traffic_spike | `/qrcodes/{qrCodeId}`（无 id → `/qrcodes`） | 去活码 |
| inheritance_no_service / inheritance_ambiguous_service | `/qrcodes` | 去配置服务老师 |
| add_fail / melt / transfer_fail / wechat_unavailable / wechat_unavailable_service / employee_departed_service | `/agents` | 去员工管理 |
| wechat_48002 | 无按钮 | 说明性卡片，附“归档” |
| transfer_retry_exhausted_svc | `/qrcodes/{qrCodeId}/transfers`（无 id → `/dashboard`） | 查看继承记录 |
| transfer_dlq_backlog / dlq_replay_exhausted / *_stream_backlog / daily_reset_failure | `/dashboard` | 去总览 |
| deletion_report_fail | `/deletion-records` | 去删除记录 |

继承类告警没有 `qrCodeId`，短期只能到活码列表；detail 里列出学校名（最多 5 个），页面直接展示。后续可在 detail 里补 schoolId 做精确跳转，列为后续项，不阻塞本次。

### 4.7 数据模型

`agent_alert` 增加两列和一个复合索引：

```sql
ALTER TABLE agent_alert
  ADD COLUMN last_seen_at DATETIME NULL AFTER resolved_at,
  ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER last_seen_at,
  ADD INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id);

UPDATE agent_alert SET last_seen_at = created_at WHERE last_seen_at IS NULL;
```

- 现有索引只有 `(agent_userid, status)` 和 `(created_at)`；upsert 的查找条件和每小时分组的条件都是 `(alert_type, status, ...)`，不加索引会全表扫描，归档累积后越来越慢。
- `schema.sql` 同步更新；`ddl-auto: none`，生产由用户手动执行（上线前执行，旧版本代码不受影响）。
- 实体 `AgentAlert` 增加 `lastSeenAt`、`occurrenceCount`。

## 5. 实现清单

| 文件 | 改动 |
|---|---|
| `entity/AgentAlert.java` | +`lastSeenAt`、+`occurrenceCount` |
| `repository/AgentAlertRepository.java` | 新增：按 `(type, agent, qr, status)` 查最新一条；按类型批量关闭；分组查询 |
| `service/AlertService.java` | `createAlert` 按类型 upsert；`resolveOpenByType` / `resolveOpenByKey`；`collapseDuplicates`；`archiveStale` |
| `service/AlertTypeMeta.java`（新） | 类型元数据 + 链接生成 |
| `service/AlertTriageService.java`（新） | 聚合 open 告警为卡片分组 |
| `controller/AlertController.java` | 默认 open；分组模型；归档 informational 的 POST；类型筛选 |
| `templates/alert/list.html` | 卡片区 + 明细新增列 + 去处理按钮 + 删除一键全部 |
| `worker/AlertMaintenanceJob.java`（新） | 每小时 + 启动收敛 |
| `worker/PatrolWorker.java` | 异常归零关闭；traffic_spike 按 DB 差集关闭；池恢复关闭 empty_backup；DLQ 清零关闭 |
| `service/QrCodeService.java` | `replaceAnomalyAgents` 返回/记录本轮命中的告警 key，供批次结算 |
| `service/EmployeeSyncService.java` | 员工恢复在职时关闭 employee_departed_service |
| `service/DeletionReportService.java` | 报告成功时关闭 deletion_report_fail |
| `worker/StreamBacklogMonitor.java` | 每类流恢复时关闭对应告警 |
| `job/InheritanceJob.java` | 两类继承告警归零关闭 |
| `worker/TransferMonitorWorker.java` | 死信清零关闭 |
| `worker/DailyResetWorker.java` | 重置成功关闭 |
| `worker/OutboundMsgWorker.java` | 48002 改系统级 + 新文案 |
| `service/TransferService.java` | 交接欢迎语永久失败时对 48002 计数 +1（只计数） |
| `schema.sql` | 两列 + `idx_type_status_key` |
| 测试 | `AlertServiceTest`（upsert/关闭/收敛）、`AlertTriageServiceTest`（分组/链接）、`AlertControllerTest`（默认 open、归档端点） |

## 6. 验收

1. 上线后首次维护任务完成，`status=open` 从 2,140 降到 < 50；24 小时后新增 open 数显著低于当前 ~20/天/类型（对比 `created_at` 统计）。
2. `/alerts` 首屏只出现真实待办分组；逐类型点击「去处理」都能落到对应页面。
3. 48002 每日最多 1 条，文案不再提“员工权限”；历史 358 条变为 `auto_resolved`。
4. 条件恢复演练：制造一个异常员工 → 出现 `qr_agent_anomaly`；修复后下一轮巡检（≤5 分钟）告警自动变 `auto_resolved`。
5. 不误关：白天增量继承扫描跑完后，已有 `inheritance_*` 告警仍为 `open`；只有夜间全量批次确认列表为空才关闭。
6. 事件类告警行为不变：新增客户欢迎语、继承、打标流程不受影响；`add_fail` / `melt` 仍逐条记录。
7. 48002 当天多次命中后，唯一那条 open 行的“今日 N 人”持续递增，次日归档并新建。
8. 两机同时运行 24h，无重复维护任务、无 `agent_alert` 写入异常。
9. 合并后的存活行：是组内最早创建的那条；`次数` = 组内求和、`最近发生` = 组内最大（“这个问题多久了”不被重置）。
10. 单测覆盖“批次中某活码处理抛异常 → 该活码告警不被关闭”。
11. 48002 卡片的“今日 N 次失败”包含交接欢迎语：跑一次真实交接后计数增长，文案与实际发送失败量同量级。

## 7. 风险与取舍

- **合并会丢失逐次流水**：条件类告警合并为“一条 + 次数 + 最近时间”。每次发生的完整上下文仍在 `/var/log/huoma/application.log` 的 `告警:` 日志里，接受。
- **两机并发 upsert 仍可能产生重复**：由每小时合并兜底，最坏情况下页面短暂多一条，接受。
- **自动关闭依赖产生方回调**：若巡检/批次整体停摆，`qr_agent_anomaly` 有 7 天兜底；其他类型不设超时，避免把真实问题自动抹掉。
- **日报/统计的 total_alert 口径变化**：合并后 `agent_alert` 行数下降，`DailyReport.totalAlert`（按 created_at 计数）会下降。这是预期变化，不是回归；如需保留“事件次数”口径，应另建计数表，本次不做。
- **48002 降为低级别**：页面不再逐员工提示，但每天仍有一条系统级告警，信息不丢失。
- **手动“解决”条件类告警是临时的**：条件不变，下一轮巡检（≤5 分钟）会重新建一条。这是有意为之（否则等于静默真实问题），页面用文案说明。
- **`leak_*` 的次数会约翻倍**：它们按设计在跨机锁外、由两台各自评估（见 [[prod-two-node-deployment]] 的刻意取舍），upsert 后同一行每轮被两台各 +1。只影响“次数”展示，不影响判断，接受。
- **仪表盘“今日告警”也会下降**：`DashboardService` 按 `created_at` 计数，合并后同一条件不再每天新增行。与 `totalAlert` 同因；如需“今日活跃条件数”，应改用 `last_seen_at`，本次不做。
- **回滚安全**：旧版本忽略新列，可随时回滚；但已 `auto_resolved` 的历史不会自动重开，且回滚后噪声重新开始积累。

## 8. 本次拍板（可在评审时推翻）

1. 48002 改“每天一条系统级低级别告警”，不再逐员工建。
2. 删除「一键解决全部」，改为「归档可自动恢复/过期项」。
3. 条件类告警合并为一条 + 次数；事件类保留逐条，按 14/30 天自动归档。
4. 继承类告警本次只深链到活码列表，不新增精确跳转页。
5. 条件恢复一律按「DB 中 open 行 vs 本轮实际异常集合」做差，不用“本轮列表为空”或进程内集合；继承类只在全量扫描（夜间批次）时判空。
6. 真实待办不设超时归档：继承、服务老师异常、替换缺口/受阻、空备用池、非 48002 的同步失败、流积压、DLQ、每日重置失败。
7. `traffic_spike` 合并键只用活码；48002 每次命中都更新同一条 open 行的 detail。
8. 补齐两个此前遗漏的条件类型：`dlq_replay_exhausted`（7 天兜底）、`employee_departed_service`（30 天兜底）。
9. 离职交接欢迎语的 48002 计入同一天计数（只计数，不改发送逻辑）——已确认要做，使“今日 N 人”覆盖全部受影响发送。
10. 条件类告警的“解决”按钮改文案为「标记已处理」，并提示条件仍在时会重新出现。
11. `ALTER TABLE` 一并加 `idx_type_status_key`，避免 upsert/分组全表扫描。
12. 历史 48002 清理按旧文案（“无客户联系权限”）匹配，不按 `errcode=48002`，避免误清自愈告警。

## 9. 上线步骤

1. 用户在 RDS 执行 4.7 的 `ALTER TABLE`（先备份 `agent_alert`）。
2. 用户按常规流程部署两台节点（`deploy/deploy.sh prod`）。
3. 观察启动日志：`告警维护: 合并 M 条、归档 N 条`；核对 `status=open` 数量。
4. 逐类型点验卡片跳转；用一次真实异常验证自动关闭。
