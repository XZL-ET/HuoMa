# 活码全流程风险审查报告

> 审查日期：2026-06-13
> 审查范围：活码创建 → 企微回调 → Stream 消费 → 定时任务 → 企微 API 集成 全链路

---

## 🔴 严重风险 (Critical)

### 1. NOGROUP 自愈逻辑会删除 Stream 中所有未处理消息

**位置**: `CallbackWorker.java:171-179`, `TagWorker.java:155-163`, `DataFillWorker.java:146-153`

**问题**: 消费者组被意外删除时，自愈逻辑执行 `add("_init") → createGroup → trim(stream, 0, true)`。`trim(streamKey, 0, true)` 会把 Stream 里**所有未消费的合法消息也一并删除**。

**影响**: 生产环境误删 Consumer Group → 所有积压回调/打标/补全事件永久丢失，无法恢复。

**修复**: 改为仅在 Stream 长度 ≤ 1（只有 `_init`）时才 trim；否则只 XDEL `_init` 消息。

---

### 2. 企微活码同步结果未校验（静默失败）

**位置**: `AgentBindService.java:339-348`, `QrCodeService.java:366-391`

**问题**: `syncQrCodeToWechatAsync` 和 `syncQrUsersToWechat` 调用 `wecomApi.updateContactWay(json)` 后完全不检查返回的 `errcode`。企微返回 `40003`（invalid userid）或 `60011`（no privilege）时，系统只打日志但当作成功。

**影响**: 轮换后员工列表实际未同步到企微，客户扫码仍看到旧名单。系统监控无法发现此异常。

**修复**: 调用方检查返回的 `JsonNode` 中 `errcode` 字段，非 0 时记录 ERROR 日志并触发告警。

---

### 3. Redis 全部不可用时，回调消息静默丢失

**位置**: `WecomCallbackController.java:242-249`, `MessageGuardService.java:66-82`

**问题**: 
- Fast Ack 的 try-catch 在 XADD 失败时返回 "success"，企微认为推送成功不重试，回调事件永久丢失
- `tryDedup()` Redis 不可用时 fail-open（返回 true），恢复后同一回调可能被重复处理

**影响**: Redis 故障期间所有回调事件（客户添加/删除/标签变更）静默丢失。

**修复**: 
- 增加 Redis 健康检查，故障时触发告警
- XADD 失败时写本地日志文件作为最后兜底
- 在 tryDedup 中记录 Redis 不可用次数，超过阈值时触发告警

---

## 🟠 高风险 (High)

### 4. 预留上码人数不足时静默少绑

**位置**: `QrCodeService.java:919-931`

**问题**: 管理员设置 `initialAgentCount=5`，全局池只有 2 人。活码创建成功但只绑了 2 人，系统无告警，返回值无差异。

**修复**: `needCount > 0` 时记录 WARN 日志并通过 AlertService 告警。

### 5. expandQrCodeUsers 和 preActivateBackup 使用不同锁

**位置**: `AgentBindService.java:172, 255`

**问题**: 两个方法使用不同的 Redis 锁 key（`:expand` vs `:preactivate`）。同一活码在短时间内先触发紧急阈值再满员时，两个操作可能并发执行，导致同一员工被重复加入 QrAgent。

**修复**: 统一使用 `rotate:lock:{qrCodeId}:rotate` 单一锁 key。

### 6. DailyResetWorker 恢复同步是同步阻塞的

**位置**: `DailyResetWorker.java:140-147`

**问题**: `syncQrUsersToWechat` 逐个串行同步，100 个活码需 ~50 秒。方法有 `@Transactional`，长时间占用 DB 连接。

**修复**: 改为异步批量同步。

### 7. access_token 的 synchronized 是系统级瓶颈

**位置**: `WecomApiClient.java:93`

**问题**: 16+ Worker 线程所有企微 API 调用都通过 `synchronized getAccessToken()`。Token 刷新时（100-500ms HTTP 调用），所有线程阻塞。

**修复**: 改用 `ReentrantReadWriteLock`，缓存命中时读锁不互斥。

---

## 🟡 中风险 (Medium)

### 8. Stream Trim 可能截断未消费消息

**位置**: `CallbackWorker.java:158-160`（生产者侧 trim TAG_STREAM）

**问题**: CallbackWorker 每发一条打标事件就 trim 一次。trim 从 Stream 头部删消息，如果 TagWorker 消费慢，未消费消息可能被删。

**修复**: trim 移到消费者侧（TagWorker 消费后执行）。

### 9. PEL 崩溃回收只扫描前 50 条

**位置**: `MessageGuardService.java:179`

**修复**: 循环回收直到 PEL 为空。

### 10. 企微 API 无应用层限流保护

**位置**: TagWorker (8 线程) 无速率控制

**修复**: TagWorker 消费循环中加入最小间隔。

### 11. 企微孤儿活码

**位置**: `QrCodeService.java:158-207`

**问题**: 企微 API 成功但 DB 写入失败回滚 → 企微端残留孤儿活码，无补偿清理。

### 12. 批导入进度残留

**位置**: `QrCodeService.java:245-301`

**问题**: JVM 崩溃时 Redis 进度 Key 残留 `status: processing` 最长 30 分钟。

---

## 🟢 低风险 (Low)

| # | 问题 | 位置 |
|---|------|------|
| 13 | 去重降级逻辑可能漏重（300s TTL 外的重试） | WecomCallbackController |
| 14 | DataFillWorker sleep(200) visibility race hack | DataFillWorker.java:213 |
| 15 | Dev 环境企微凭证明文硬编码 | application.yml |
| 16 | 全局事务超时 30s 可能不够（DailyResetWorker） | application.yml / DailyResetWorker |
| 17 | QrCodeService.delete 中 Pageable.unpaged() 可能 OOM | QrCodeService.java:349 |

---

## 修复优先级

1. **立即修复**: #1 (NOGROUP 删消息), #2 (企微同步静默失败), #3 (Redis 不可用丢回调)
2. **本周内**: #4-#7 (High 级别)
3. **下个迭代**: #8-#12 (Medium 级别)
4. **持续改进**: #13-#17 (Low 级别)
