# 活码系统压力测试

## 安全原理

注入 `event_type=__stress_test__` 的消息到 `wecom:callback:stream`，CallbackWorker 走到 `processEvent()` 的 `default` 分支，只打 debug 日志 → ACK，**不调企微 API、不写数据库、不触发打标**。

## 文件说明

| 文件 | 用途 |
|------|------|
| `inject_noop.lua` | Redis Lua 脚本，注入 noop 测试消息 |
| `inject_rotation.lua` | Redis Lua 脚本，注入模拟 add_external_contact 事件 |
| `monitor.sh` | 实时监控 Stream/JVM/系统指标 |
| `run_test.sh` | 一键分级压测 (Callback Worker 吞吐) |
| `agent_bind_stress.sh` | 活码人员上下码压测（添加/移除/轮转审计） |
| `rotation_stress.sh` | 轮转链路压测（回调→日限→下码→补人→同步） |

## 快速开始

### 1. 把脚本复制到服务器

```bash
scp stress-test/*.sh stress-test/*.lua root@<YOUR_SERVER_IP>:/opt/HuoMa/stress-test/
```

### 2. 开两个终端连上服务器

```bash
ssh root@<YOUR_SERVER_IP>
```

### 3. 终端 1：启动监控

```bash
bash /opt/HuoMa/stress-test/monitor.sh
```

### 4. 终端 2：执行测试

```bash
# L1 轻量级（日常流量）
bash /opt/HuoMa/stress-test/run_test.sh L1

# L2 中等级（高峰）
bash /opt/HuoMa/stress-test/run_test.sh L2

# L3 重压级（突发）
bash /opt/HuoMa/stress-test/run_test.sh L3
```

### 5. 也可以直接用 Lua 脚本自定义

```bash
# 注入 300 条，间隔 5ms
redis-cli --eval /opt/HuoMa/stress-test/inject_noop.lua , 300 5

# 注入 1000 条，无间隔
redis-cli --eval /opt/HuoMa/stress-test/inject_noop.lua , 1000 0
```

## 各级别说明

| 级别 | 注入量 | 间隔 | 模拟场景 | 预期结果 |
|------|--------|------|----------|---------|
| L1 | 100 条 | 100ms | 日常流量 (~10/s) | callback 无积压 |
| L2 | 500 条 | 10ms | 开学高峰 (~100/s) | 轻微积压，1分钟内消化 |
| L3 | 2000 条 | 1ms | 突发流量 (~1000/s) | 测试 CallbackWorker 极限吞吐 |

## 观测重点

1. **callback_stream 长度** — 核心指标，压测前后对比，应回归基线
2. **tag_stream 长度** — 不应受 noop 消息影响（noop 不发布打标事件）
3. **DLQ 长度** — 始终应为 0
4. **Pending 数量** — 持续增长说明 Worker 跟不上
5. **JVM 堆内存** — 不应持续上涨（无内存泄漏）

## 活码人员上下码压测

专门测试 `addAgent` / `removeAgent` / `takeStandby` 的性能和轮转公平性。

**安全设计：**
- **只读分析**（默认）—— 纯 SQL 查询，不调 HTTP 接口，零影响
- **压测模式**（`--execute`）—— 自动创建专用测试活码 `__STRESS_TEST__`，不影响生产活码
- **`--cleanup`** —— 测试完一键清理

### 只读分析（安全）

| 场景 | 命令 | 说明 |
|------|------|------|
| 池健康扫描 | `bash agent_bind_stress.sh pool-scan` | 离职/不可用/封号占比，可用率 |
| 轮转审计 | `bash agent_bind_stress.sh rotation-audit` | 池前30名 sortOrder 分布，队首健康检查 |
| 绑定模拟 | `bash agent_bind_stress.sh bind-sim 20` | 模拟 takeStandby 选 20 人，标记会被跳过的 |
| 活码画像 | `bash agent_bind_stress.sh qr-profile` | 每个活码的人员构成（角色/日限/状态） |
| 全部分析 | `bash agent_bind_stress.sh all-audit` | 依次执行上述全部 |

### 压测场景（需要 --execute）

| 场景 | 命令 | 说明 |
|------|------|------|
| 串行添加 | `bash agent_bind_stress.sh http-add --execute 20` | 逐个添加 20 人，测单次耗时 |
| 并发添加 | `bash agent_bind_stress.sh http-add-para --execute 10` | 10 并发添加，测事务竞争 |
| 串行移除 | `bash agent_bind_stress.sh http-remove --execute 10` | 逐个移除 10 人 |
| 混合负载 | `bash agent_bind_stress.sh http-mixed --execute 5` | 添加+移除交替 |
| 全部压测 | `bash agent_bind_stress.sh bench-all --execute` | 依次执行所有压测场景 |

### 管理命令

| 命令 | 说明 |
|------|------|
| `bash agent_bind_stress.sh --init` | 预创建测试活码 |
| `bash agent_bind_stress.sh --status` | 查看测试活码状态 |
| `bash agent_bind_stress.sh --cleanup` | 清理测试活码及所有关联数据 |

### 观测重点

1. **添加延迟** — 串行添加应在 100ms 以内
2. **并发冲突** — http-add-para 应 0 失败（事务隔离正常）
3. **轮转公平** — 被取走的代理 sortOrder 应移至队尾最大值
4. **池可用率** — pool-scan 低于 80% 需触发员工同步
5. **队首健康** — rotation-audit 队首不应有离职/封号（懒清理卡住）
6. **DB 连接池** — 并发时不应耗尽连接

## 轮转链路压测

模拟大批用户扫码涌入 → 员工日限满 → 自动下码 → 全局池补人上码 → 企微同步的完整链路。

**链路**: 注入 `add_external_contact` → CallbackWorker → incrementDailyCount → checkAndRotate → expandQrCodeUsers → takeStandby → afterCommit 同步企微

**安全设计**:
- 所有操作仅针对专用测试活码 (schoolId=`STRESS_TEST_000`)，与生产活码隔离
- 注入的 `external_userid` 使用 `stress_` 前缀，与真实客户隔离
- 测试代理 dailyMax 设为 5（小值快速触发轮转），测试完可清理恢复

### 命令

| 命令 | 说明 |
|------|------|
| `bash rotation_stress.sh init 5` | 创建测试活码 + 绑定 5 个代理 (dailyMax=5) |
| `bash rotation_stress.sh inject 6` | 每代理注入 6 个模拟客户 (超过 dailyMax → 触发轮转) |
| `bash rotation_stress.sh watch` | 实时监控 Redis Stream / 代理状态 / 轮转日志 |
| `bash rotation_stress.sh report` | 生成轮转报告 (轮转次数/上码分布/公平性检查) |
| `bash rotation_stress.sh full 10 6` | 完整流程: init(10代理) → inject → report |
| `bash rotation_stress.sh cleanup` | 清理测试活码及全部关联数据 |

### 级别

| 级别 | 命令 | 代理 | 事件 | 预计轮转 |
|------|------|:--:|:--:|:--:|
| L1 | `bash rotation_stress.sh full 5 6` | 5 | 30 | ~5 |
| L2 | `bash rotation_stress.sh full 10 6` | 10 | 60 | ~10 |
| L3 | `bash rotation_stress.sh full 20 6` | 20 | 120 | ~20 |

### 观测重点

1. **轮转触发** — 每个代理满 dailyMax=5 后是否自动触发 expandQrCodeUsers
2. **公平轮转** — 上码员工不应重复（被取后 sortOrder 移至队尾）
3. **Redis 锁** — 并发轮转时 `qrCodeId:rotate` 锁是否防住了重复
4. **企微同步** — afterCommit 同步是否成功（journalctl 查 `syncQrCodeToWechatAsync` 日志）
5. **CallbackWorker** — Pending 是否归零，Stream 不应积压
6. **池不枯竭** — 1798 个 standby 足够支撑大量轮转

## 清理

noop 消息被消费后自动 ACK，Stream 自动 trim，无需手动清理。
如果要去掉残留的测试数据：

```bash
# 查看 callback stream 里还有多少 noop 消息
redis-cli XRANGE wecom:callback:stream - + | grep -c stress_test

# 如果积压，等 Worker 消化即可
```
