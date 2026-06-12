# 消息去重 / 死信队列 / PEL Claim 三个可靠性保护 — 实现方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐当前 Fast Ack + Redis Stream 架构中缺失的 3 个消息可靠性保护：入队前去重、失败消息死信队列、Worker 崩溃后 PEL 消息回收。

**Architecture:**

```
企微 POST → Controller ─XADD→ Redis Stream ─XREADGROUP→ Worker
                │                            │
          ① SETNX 去重             ② 失败不 ACK，重试 3 次
          (MsgId, TTL 300s)        ③ 超限 → 死信队列 (DLQ Stream)
                                   ④ PEL Scanner 30s 回收崩溃消息
```

新增一个轻量级组件 `MessageGuardService`，收敛去重/DLQ/PEL Claim 的 Redis 操作，避免散落在 Controller 和各 Worker 中。Worker 层只改两行：移除 `finally { ACK }`，改为只在成功路径 ACK。DLQ 重放通过 HealthController 新增的 `/api/health/dlq` 端点手工触发。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data Redis, Redis Streams, Lombok, SLF4J

---

## 文件结构

```
Create:  src/main/java/com/bookstore/qrcode/service/MessageGuardService.java   # 去重/DLQ/PEL 收敛
Modify:  src/main/java/com/bookstore/qrcode/config/RedisConfig.java            # 新增 6 个常量
Modify:  src/main/java/com/bookstore/qrcode/wecom/WecomCallbackController.java # 入队前 SETNX 去重
Modify:  src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java         # 移除 finally-ACK，失败走 retry
Modify:  src/main/java/com/bookstore/qrcode/worker/TagWorker.java              # 同上
Modify:  src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java         # 同上
Modify:  src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java           # 新增 PEL 扫描
Modify:  src/main/java/com/bookstore/qrcode/controller/HealthController.java   # 新增 DLQ 统计 & 重放端点
```

---

### Task 1: RedisConfig — 新增 DLQ / Dedup / PEL 常量

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java:40-42`

在现有 `TAG_STREAM_MAXLEN` 和 `DATAFILL_STREAM_MAXLEN` 下方，追加以下常量：

```java
// ==================== 死信队列 (DLQ) Stream 相关常量 ====================

/** Redis Stream Key：死信队列，存放重试耗尽的消息 */
public static final String DLQ_STREAM_KEY = "wecom:dlq:stream";
/** Consumer Group 名称：死信队列消费组（重放时使用） */
public static final String DLQ_CONSUMER_GROUP = "dlq-worker-group";
/** DLQ Stream 最大长度，防止 OOM（死信通常很少，设保守上限） */
public static final long DLQ_STREAM_MAXLEN = 10000;

// ==================== 消息去重 Key 前缀常量 ====================

/** 回调去重 Key 前缀。完整 Key: callback:dedup:{msgId}，TTL 300s */
public static final String CALLBACK_DEDUP_KEY_PREFIX = "callback:dedup:";

// ==================== 重试计数 Key 前缀常量 ====================

/** 消息重试计数 Key 前缀。完整 Key: dlq:retry:{streamKey}:{messageId}，TTL 3600s */
public static final String DLQ_RETRY_KEY_PREFIX = "dlq:retry:";
```

---

### Task 2: MessageGuardService — 去重 / 重试 / DLQ / PEL Claim

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/MessageGuardService.java`

**职责：** 把所有去重、重试计数、DLQ 写入、PEL Claim 的 Redis 操作收敛到一个服务中，Worker 和 Controller 只需调用方法。

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 消息可靠性守护服务 —— 去重 / 重试计数 / 死信队列 / PEL 回收。
 *
 * <p><b>三大保护机制：</b>
 * <ol>
 *   <li><b>入队前去重</b> —— {@link #tryDedup(String)} 基于 Redis SETNX，
 *       防止企微 5 秒内重推同一条回调导致重复处理；</li>
 *   <li><b>失败重试 + 死信队列</b> —— {@link #markRetryOrDead(String, String, String, Map)}
 *       记录重试次数，≤3 次放回 Stream 重试，>3 次移入 DLQ；</li>
 *   <li><b>PEL 崩溃回收</b> —— {@link #recoverOrphanedPending(String, String, String, int)}
 *       扫描 PEL 中 idle > 30s 的消息，XCLAIM 到恢复消费者重试或移入 DLQ。</li>
 * </ol>
 *
 * @author Bookstore Dev
 * @since 1.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageGuardService {

    private final StringRedisTemplate redisTemplate;

    private static final int MAX_RETRIES = 3;
    private static final int DEDUP_TTL_SECONDS = 300;
    private static final int RETRY_TTL_SECONDS = 3600;

    // ================================================================
    // ① 消息去重
    // ================================================================

    /**
     * 尝试对消息做去重标记。首次出现的消息返回 true，重复消息返回 false。
     *
     * <p>使用 Redis SETNX 原子操作，Key TTL 为 300 秒（覆盖企微 5 秒重试窗口 + 安全余量）。
     * Redis 不可用时 fail-open：直接返回 true，允许消息通过，避免阻断正常回调。</p>
     *
     * @param msgId 消息唯一标识（企微回调 XML 中的 MsgId 标签值）
     * @return true = 首次出现，可继续处理；false = 重复消息，应跳过
     */
    public boolean tryDedup(String msgId) {
        if (msgId == null || msgId.isEmpty()) {
            // 无 MsgId 时无法去重，放行（企微回调理论上都有 MsgId）
            log.warn("回调消息缺少 MsgId，跳过去重");
            return true;
        }
        try {
            String key = RedisConfig.CALLBACK_DEDUP_KEY_PREFIX + msgId;
            Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(DEDUP_TTL_SECONDS));
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            // Redis 不可用 → fail-open，不阻塞回调
            log.error("消息去重 Redis 操作失败, 放行消息: msgId={}", msgId, e);
            return true;
        }
    }

    // ================================================================
    // ② 失败重试 + 死信队列
    // ================================================================

    /**
     * 记录一次处理失败，决定重试或移入死信队列。
     *
     * <p><b>决策逻辑：</b>
     * <ol>
     *   <li>INCR 重试计数器 (TTL 1h)</li>
     *   <li>retryCount ≤ 3 → XADD 回原 Stream（尾部重试），ACK 原消息</li>
     *   <li>retryCount > 3 → XADD 到 DLQ Stream，ACK 原消息，ERROR 日志 + 告警</li>
     * </ol>
     *
     * @param streamKey  原消息所在的 Stream Key
     * @param messageId  原消息的 Redis Stream Message ID
     * @param eventJson  原始事件 JSON（用于重试/DLQ）
     * @param errorInfo  失败原因描述
     */
    public void markRetryOrDead(String streamKey, String consumerGroup,
                                 String messageId, Map<String, String> fields,
                                 String errorInfo) {
        String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + streamKey + ":" + messageId;

        try {
            Long retryCount = redisTemplate.opsForValue().increment(retryKey);
            redisTemplate.expire(retryKey, Duration.ofSeconds(RETRY_TTL_SECONDS));

            if (retryCount == null) retryCount = 1L;

            if (retryCount <= MAX_RETRIES) {
                // 放回原 Stream 重试
                redisTemplate.opsForStream().add(streamKey, fields);
                log.warn("消息处理失败，放回重试 ({}次/{}次): stream={}, msgId={}, error={}",
                    retryCount, MAX_RETRIES, streamKey, messageId, errorInfo);
            } else {
                // 移入死信队列
                Map<String, String> dlqFields = new java.util.LinkedHashMap<>(fields);
                dlqFields.put("_dlq_origin_stream", streamKey);
                dlqFields.put("_dlq_origin_msgid", messageId);
                dlqFields.put("_dlq_retry_count", String.valueOf(retryCount));
                dlqFields.put("_dlq_last_error", errorInfo);
                dlqFields.put("_dlq_time", java.time.Instant.now().toString());

                redisTemplate.opsForStream().add(RedisConfig.DLQ_STREAM_KEY, dlqFields);
                redisTemplate.opsForStream().trim(RedisConfig.DLQ_STREAM_KEY,
                    RedisConfig.DLQ_STREAM_MAXLEN, true);

                log.error("消息已移入死信队列: stream={}, msgId={}, retries={}, error={}",
                    streamKey, messageId, retryCount, errorInfo);
            }

            // 无论重试还是 DLQ，ACK 原消息（已移走）
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, messageId);

        } catch (Exception e) {
            // Redis 操作失败，ACK 原消息防止 PEL 堆积，依赖人工兜底
            log.error("重试/DLQ 操作失败，ACK 原消息: stream={}, msgId={}", streamKey, messageId, e);
            try {
                redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, messageId);
            } catch (Exception ackEx) {
                log.error("ACK 也失败了: {}", ackEx.getMessage());
            }
        }
    }

    // ================================================================
    // ③ PEL 崩溃回收
    // ================================================================

    /**
     * 扫描指定 Stream 的 PEL，回收 idle 超时的孤消息。
     *
     * <p>正常流程中 Worker 会在处理成功后 ACK，PEL 应接近空。
     * PEL 有消息意味着：Worker 在 READ 和 ACK 之间崩溃，或正在重试中。
     *
     * <p><b>回收逻辑 (对每条 idle > idleMs 的消息)：</b>
     * <ol>
     *   <li>检查重试计数器</li>
     *   <li>retryCount < 3 → XCLAIM 获取消息体 → XADD 回原 Stream 尾部
     *       （正常 Worker 会重新消费到），然后 ACK 原 PEL 消息</li>
     *   <li>retryCount ≥ 3 → 移入 DLQ + ACK</li>
     * </ol>
     *
     * <p><b>为什么 XCLAIM 后不能等着让 Worker 自然消费：</b>
     * XCLAIM 把消息分配给 recovery consumer 的 PEL，但正常 Worker 的
     * XREADGROUP 用的是 {@code >}（只读新消息），不会读 recovery consumer
     * 的 PEL。因此必须在 claim 成功后把消息体 XADD 回 Stream 尾部，
     * 然后 ACK 原消息——Normal Worker 自然会从 Stream 读到重入队的消息。</p>
     *
     * @param streamKey     Stream Key
     * @param consumerGroup Consumer Group 名称
     * @param recoveryConsumer XCLAIM 时使用的恢复消费者名称
     * @param idleMs        消息 idle 超过此毫秒数才回收（建议 30000ms）
     * @return 回收的消息数量
     */
    public int recoverOrphanedPending(String streamKey, String consumerGroup,
                                       String recoveryConsumer, long idleMs) {
        int recovered = 0;
        try {
            // 获取 PEL 概要
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                .pending(streamKey, consumerGroup);
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return 0;
            }

            // 获取 idle > idleMs 的 pending 消息详情（最多 50 条/次）
            PendingMessages pending = redisTemplate.opsForStream().pending(
                streamKey, consumerGroup,
                org.springframework.data.domain.Range.unbounded(), 50L);

            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery() == null) continue;
                long idle = pm.getElapsedTimeSinceLastDelivery().toMillis();
                if (idle < idleMs) continue;

                String msgId = pm.getIdAsString();
                String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + streamKey + ":" + msgId;

                try {
                    Long retryCount = redisTemplate.opsForValue().increment(retryKey);
                    redisTemplate.expire(retryKey, Duration.ofSeconds(RETRY_TTL_SECONDS));
                    if (retryCount == null) retryCount = 1L;

                    if (retryCount <= MAX_RETRIES) {
                        // XCLAIM 获取消息体，拿到后 XADD 回原 Stream 供正常 Worker 消费
                        // 注意：不能只 claim 不 re-enqueue — recovery consumer 无人消费
                        List<MapRecord<String, Object, Object>> claimed =
                            redisTemplate.opsForStream().claim(
                                streamKey, consumerGroup, recoveryConsumer,
                                Duration.ofMillis(idleMs), msgId);
                        if (claimed != null && !claimed.isEmpty()) {
                            for (MapRecord<String, Object, Object> rec : claimed) {
                                Map<String, String> fields = toStringMap(rec.getValue());
                                redisTemplate.opsForStream().add(streamKey, fields);
                            }
                            redisTemplate.opsForStream().acknowledge(
                                streamKey, consumerGroup, msgId);
                            log.warn("PEL 回收: XCLAIM + re-enqueue, stream={}, msgId={}, idle={}ms, retry={}",
                                streamKey, msgId, idle, retryCount);
                            recovered++;
                        }
                    } else {
                        // 重试耗尽 → 移入 DLQ
                        List<MapRecord<String, Object, Object>> records =
                            redisTemplate.opsForStream().range(streamKey,
                                org.springframework.data.domain.Range.closed(msgId, msgId));
                        if (records != null && !records.isEmpty()) {
                            Map<String, String> fields = toStringMap(records.get(0).getValue());
                            fields.put("_dlq_origin_stream", streamKey);
                            fields.put("_dlq_origin_msgid", msgId);
                            fields.put("_dlq_retry_count", String.valueOf(retryCount));
                            fields.put("_dlq_last_error", "PEL idle timeout " + idle + "ms");
                            fields.put("_dlq_time", java.time.Instant.now().toString());

                            redisTemplate.opsForStream().add(RedisConfig.DLQ_STREAM_KEY, fields);
                            redisTemplate.opsForStream().trim(RedisConfig.DLQ_STREAM_KEY,
                                RedisConfig.DLQ_STREAM_MAXLEN, true);
                            log.error("PEL 回收 → DLQ: stream={}, msgId={}, idle={}ms, retry={}",
                                streamKey, msgId, idle, retryCount);
                        }
                        // ACK 原消息
                        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, msgId);
                        recovered++;
                    }
                } catch (Exception inner) {
                    log.error("PEL 回收单条消息失败: msgId={}", msgId, inner);
                }
            }
        } catch (Exception e) {
            log.error("PEL 回收扫描失败: stream={}, group={}", streamKey, consumerGroup, e);
        }
        return recovered;
    }

    // ================================================================
    // ④ DLQ 统计 & 重放
    // ================================================================

    /**
     * 获取 DLQ Stream 当前长度。
     */
    public long dlqSize() {
        try {
            Long size = redisTemplate.opsForStream().size(RedisConfig.DLQ_STREAM_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    // ================================================================
    // ⑤ 辅助方法
    // ================================================================

    /**
     * 将 Redis Stream 返回的 Map<Object, Object> 转换为 Map<String, String>。
     */
    private Map<String, String> toStringMap(Map<Object, Object> value) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        value.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    /**
     * 将 DLQ 中的消息全部重放到指定 Stream，然后清空 DLQ。
     *
     * @param targetStreamKey 重放目标 Stream（通常是原 Stream）
     * @return 重放的消息数量
     */
    public int replayDlq(String targetStreamKey) {
        int count = 0;
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamReadOptions.empty().count(100),
                    StreamOffset.fromStart(RedisConfig.DLQ_STREAM_KEY));

            if (records != null) {
                for (MapRecord<String, Object, Object> r : records) {
                    Map<Object, Object> val = r.getValue();
                    Map<String, String> fields = new java.util.LinkedHashMap<>();
                    val.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
                    redisTemplate.opsForStream().add(targetStreamKey, fields);
                    count++;
                }
            }

            // 清空 DLQ
            redisTemplate.opsForStream().trim(RedisConfig.DLQ_STREAM_KEY, 0, true);
            log.info("DLQ 重放完成: {} 条 → {}", count, targetStreamKey);
        } catch (Exception e) {
            log.error("DLQ 重放失败", e);
        }
        return count;
    }
}
```

---

### Task 3: WecomCallbackController — 入队前 MsgId 去重

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomCallbackController.java:171-215`

**改动点：** 在 XADD 之前，从 `decryptedXml` 提取唯一标识去重。

**MsgId 降级方案：** 企微回调 XML 中 `MsgId` 标签在大多数事件类型中存在，但部分类型不保证。当 `MsgId` 缺失时，使用 `CreateTime + Event + ExternalUserID + UserID` 组合作为降级 hash：

```java
// 在 Controller 字段注入中添加：
private final MessageGuardService messageGuardService;

// 在 receive() 方法中，步骤3（line 184 附近）之后，步骤5（line 213 的 XADD）之前，插入去重逻辑：

// ================================================================
// 步骤 3.5: 消息去重（防企微 5s 内重推）
// ================================================================
String msgId = extractXmlTag(decryptedXml, "MsgId");
if (msgId == null || msgId.isEmpty()) {
    // 降级：用关键字段组合去重（部分事件类型无 MsgId）
    String raw = String.format("%s|%s|%s|%s",
        extractXmlTag(decryptedXml, "CreateTime"),
        eventType, externalUserId, userId);
    msgId = Integer.toHexString(raw.hashCode());
}
if (!messageGuardService.tryDedup(msgId)) {
    log.info("重复回调消息，跳过处理: msgId={}", msgId);
    return "success";
}
```

**同时在 `@RequiredArgsConstructor` 中会由 Lombok 自动生成包含 `messageGuardService` 的构造函数。**

---

### Task 4: CallbackWorker — 移除 finally-ACK，失败走 MessageGuardService

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:108-173`

**核心改动：** 把 `finally { ACK }` 改为只在成功路径 ACK。失败时调用 `messageGuardService.markRetryOrDead()`。

```java
// 新增依赖注入：
private final MessageGuardService messageGuardService;

// 修改 consumeLoop() 的内层 for 循环 (line 126-138)：
for (MapRecord<String, Object, Object> record : records) {
    String msgId = record.getId().getValue();
    Map<Object, Object> value = record.getValue();
    String eventJson = (String) value.get("event");

    boolean success = false;
    try {
        processEvent(eventJson);
        success = true;
    } catch (Exception e) {
        log.error("处理回调事件失败: consumer={}, msgId={}", consumerName, msgId, e);
    }

    if (success) {
        // 成功 → ACK
        redisTemplate.opsForStream().acknowledge(
            RedisConfig.CALLBACK_STREAM_KEY,
            RedisConfig.CALLBACK_CONSUMER_GROUP,
            msgId);
    } else {
        // 失败 → 重试或移入 DLQ
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("event", eventJson);
        messageGuardService.markRetryOrDead(
            RedisConfig.CALLBACK_STREAM_KEY,
            RedisConfig.CALLBACK_CONSUMER_GROUP,
            msgId, fields,
            "CallbackWorker 处理失败");
    }
}
```

---

### Task 5: TagWorker — 同上改动

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/TagWorker.java:93-155`

完全相同的模式。新增 `MessageGuardService` 注入，for 循环中改为条件 ACK：

```java
// 新增依赖注入：
private final MessageGuardService messageGuardService;

// 修改内层 for 循环 (line 111-124)：
for (MapRecord<String, Object, Object> record : records) {
    String msgId = record.getId().getValue();
    Map<Object, Object> value = record.getValue();
    String eventJson = (String) value.get("event");

    boolean success = false;
    try {
        processEvent(eventJson);
        success = true;
    } catch (Exception e) {
        log.error("处理打标事件失败: consumer={}, msgId={}", consumerName, msgId, e);
    }

    if (success) {
        redisTemplate.opsForStream().acknowledge(
            RedisConfig.TAG_STREAM_KEY,
            RedisConfig.TAG_CONSUMER_GROUP,
            msgId);
    } else {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("event", eventJson);
        messageGuardService.markRetryOrDead(
            RedisConfig.TAG_STREAM_KEY,
            RedisConfig.TAG_CONSUMER_GROUP,
            msgId, fields,
            "TagWorker 处理失败");
    }
}
```

---

### Task 6: DataFillWorker — 同上改动

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java:82-148`

同样的模式：

```java
// 新增依赖注入：
private final MessageGuardService messageGuardService;

// 修改内层 for 循环 (line 99-112)：
for (MapRecord<String, Object, Object> record : records) {
    String msgId = record.getId().getValue();
    Map<Object, Object> value = record.getValue();
    String eventJson = (String) value.get("event");

    boolean success = false;
    try {
        processEvent(eventJson);
        success = true;
    } catch (Exception e) {
        log.error("补全客户信息失败: msgId={}", msgId, e);
    }

    if (success) {
        redisTemplate.opsForStream().acknowledge(
            RedisConfig.DATAFILL_STREAM_KEY,
            RedisConfig.DATAFILL_CONSUMER_GROUP,
            msgId);
    } else {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("event", eventJson);
        messageGuardService.markRetryOrDead(
            RedisConfig.DATAFILL_STREAM_KEY,
            RedisConfig.DATAFILL_CONSUMER_GROUP,
            msgId, fields,
            "DataFillWorker 处理失败");
    }
}
```

---

### Task 7: PatrolWorker — 新增 PEL Crash 回收巡检

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java`

新增 `MessageGuardService` 注入，在 `patrol()` 方法末尾增加第 4 项巡检：

```java
// 新增依赖注入：
private final MessageGuardService messageGuardService;

// 在 patrol() 方法中，countTodayAlerts() 之后追加：
@Scheduled(cron = "30 */1 * * * *")  // 每分钟第 30 秒执行
public void recoverOrphanedPending() {
    log.debug("PEL 崩溃回收巡检开始");
    long idleMs = 30_000; // idle 超过 30 秒视为崩溃

    int cb = messageGuardService.recoverOrphanedPending(
        RedisConfig.CALLBACK_STREAM_KEY,
        RedisConfig.CALLBACK_CONSUMER_GROUP,
        "callback-recovery", idleMs);

    int tag = messageGuardService.recoverOrphanedPending(
        RedisConfig.TAG_STREAM_KEY,
        RedisConfig.TAG_CONSUMER_GROUP,
        "tag-recovery", idleMs);

    int df = messageGuardService.recoverOrphanedPending(
        RedisConfig.DATAFILL_STREAM_KEY,
        RedisConfig.DATAFILL_CONSUMER_GROUP,
        "datafill-recovery", idleMs);

    if (cb + tag + df > 0) {
        log.warn("PEL 崩溃回收: callback={}, tag={}, datafill={}", cb, tag, df);
    }

    // 同时检查 DLQ 是否有积压
    long dlq = messageGuardService.dlqSize();
    if (dlq > 0) {
        log.warn("死信队列积压: {} 条", dlq);
    }
}
```

注意：这与现有的 `@Scheduled(cron = "0 */5 * * * *")` 是**独立定时任务**，PEL 扫描需要更高频率（1 分钟），因为崩溃恢复越快越好。

---

### Task 8: HealthController — 新增 DLQ 统计与重放端点

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/HealthController.java`

在 `streamHealth()` 方法返回的 Map 中追加 DLQ 统计：

```java
// 新增依赖注入：
private final MessageGuardService messageGuardService;

// 在 streamHealth() 方法中，datafill_pel_pending 之后追加：
h.put("dlq_length", messageGuardService.dlqSize());
```

新增重放端点：

```java
/**
 * POST /api/health/dlq/replay — 将死信队列中的所有消息重放到指定 Stream。
 *
 * <p>重放后 DLQ 被清空。目标 Stream 默认为 CALLBACK_STREAM，可通过
 * target 参数指定 tag/datafill/callback。</p>
 */
@PostMapping("/api/health/dlq/replay")
public Map<String, Object> replayDlq(@RequestParam(defaultValue = "callback") String target) {
    String targetKey;
    switch (target) {
        case "tag":
            targetKey = RedisConfig.TAG_STREAM_KEY;
            break;
        case "datafill":
            targetKey = RedisConfig.DATAFILL_STREAM_KEY;
            break;
        default:
            targetKey = RedisConfig.CALLBACK_STREAM_KEY;
    }
    int count = messageGuardService.replayDlq(targetKey);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("replayed", count);
    result.put("target", targetKey);
    result.put("timestamp", java.time.Instant.now().toString());
    log.info("DLQ 重放完成: {} 条 → {}", count, targetKey);
    return result;
}
```

---

## 影响范围总结

| 组件 | 改动量 | 风险 |
|------|--------|------|
| RedisConfig | +20 行（常量） | 无 |
| MessageGuardService | 新建 ~220 行 | 低（纯 Redis 操作，独立服务） |
| WecomCallbackController | +5 行 | 低（XADD 前插入去重检查，失败不影响主流程） |
| CallbackWorker | ~15 行改动 | 中（ACK 时机从 finally 改为条件） |
| TagWorker | ~15 行改动 | 中（同上） |
| DataFillWorker | ~15 行改动 | 中（同上） |
| PatrolWorker | +30 行（新定时任务） | 低（独立 cron，不影响现有巡检） |
| HealthController | +30 行（新端点 + DLQ 统计） | 低（新增端点，不影响现有接口） |

**总改动量：约 350 行新增代码 + 45 行修改。**
