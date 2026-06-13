# 活码系统压力测试

## 安全原理

注入 `event_type=__stress_test__` 的消息到 `wecom:callback:stream`，CallbackWorker 走到 `processEvent()` 的 `default` 分支，只打 debug 日志 → ACK，**不调企微 API、不写数据库、不触发打标**。

## 文件说明

| 文件 | 用途 |
|------|------|
| `inject_noop.lua` | Redis Lua 脚本，注入测试消息 |
| `monitor.sh` | 实时监控 Stream/JVM/系统指标 |
| `run_test.sh` | 一键分级压测 |

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

## 清理

noop 消息被消费后自动 ACK，Stream 自动 trim，无需手动清理。
如果要去掉残留的测试数据：

```bash
# 查看 callback stream 里还有多少 noop 消息
redis-cli XRANGE wecom:callback:stream - + | grep -c stress_test

# 如果积压，等 Worker 消化即可
```
