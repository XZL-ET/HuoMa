package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.bookstore.qrcode.wecom.WecomPermanentException;
import com.bookstore.qrcode.wecom.WecomRateLimitException;
import com.bookstore.qrcode.wecom.WecomTokenExpiredException;
import com.bookstore.qrcode.wecom.WecomTransientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息可靠性守护服务 —— 去重 / 重试计数 / 死信队列 / PEL 回收 / DLQ 重放。
 *
 * <p><b>三大保护机制：</b>
 * <ol>
 *   <li><b>入队前去重</b> —— {@link #tryDedup(String)} 基于 Redis SETNX，
 *       防止企微 5 秒内重推同一条回调导致重复处理；</li>
 *   <li><b>失败重试 + 死信队列</b> —— {@link #markRetryOrDead(String, String, String, Map, String)}
 *       记录重试次数，≤3 次放回 Stream 重试，>3 次移入 DLQ；</li>
 *   <li><b>PEL 崩溃回收</b> —— {@link #recoverOrphanedPending(String, String, String, long)}
 *       扫描 PEL 中 idle > 30s 的消息，XCLAIM 后 XADD 回 Stream 重试或移入 DLQ。</li>
 * </ol>
 *
 * <p><b>设计原则：</b>
 * <ul>
 *   <li>所有 Redis 操作失败时 fail-open，不阻塞正常消息链路</li>
 *   <li>DLQ 是一条独立 Redis Stream，支持通过 API 查询和重放</li>
 *   <li>PEL 回收的 XCLAIM 拿到消息体后直接 XADD 回 Stream 尾部，
 *       避免 recovery consumer 无人消费导致消息卡死</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 1.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageGuardService {

    private final StringRedisTemplate redisTemplate;
    private final LeakMetrics leakMetrics;

    private static final int MAX_RETRIES = 3;
    private static final int DEDUP_TTL_SECONDS = 300;
    private static final int RETRY_TTL_SECONDS = 3600;
    private static final long DEFAULT_IDLE_MS = 30_000;
    /** 指数退避最大延迟（秒），超过此值按此值计算 */
    private static final long MAX_BACKOFF_SECONDS = 60;

    /**
     * 基于消息内容计算确定性逻辑 ID（SHA-256 前 16 字节 → 32 hex chars，128-bit）。
     * <p>
     * 相比 {@code Integer.toHexString(fields.hashCode())}（32-bit, 碰撞概率
     * 约 1/2^16 ≈ 1/65536），128-bit 使碰撞概率降至约 1/2^64，即使
     * 百万级消息量也几乎不可能碰撞。
     * </p>
     * <p>优先使用 external_userid + userid + state 拼接作为种子，避免
     * HashMap 迭代顺序不确定性；若三个字段均为空则退回到 fields.toString()。</p>
     *
     * @param fields 消息字段 Map
     * @return 32 字符十六进制逻辑 ID
     */
    private String computeLogicalId(Map<String, String> fields) {
        String externalUserId = fields.getOrDefault("external_userid", "");
        String userId = fields.getOrDefault("userid", "");
        String state = fields.getOrDefault("state", "");

        String seed = externalUserId + "|" + userId + "|" + state;
        if (seed.equals("||")) {
            seed = fields.toString();
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {  // 128-bit, collision prob ≈ 1/2^64
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 在所有 JVM 上均受支持，此分支仅防御编程
            return Integer.toHexString(seed.hashCode());
        }
    }

    // ================================================================
    // ① 消息去重
    // ================================================================

    /**
     * 尝试对消息做去重标记。首次出现的消息返回 true，重复消息返回 false。
     *
     * <p>使用 Redis SETNX 原子操作，Key TTL 为 300 秒（覆盖企微 5 秒重试窗口 + 安全余量）。
     * Redis 不可用时 fail-open：直接返回 true，允许消息通过，避免阻断正常回调。</p>
     *
     * @param msgId 消息唯一标识（企微回调 XML 中的 MsgId 标签值或降级 hash）
     * @return true = 首次出现，可继续处理；false = 重复消息，应跳过
     */
    public boolean tryDedup(String msgId) {
        if (msgId == null || msgId.isEmpty()) {
            // 无标识时无法去重，放行（企微回调理论上都有 MsgId）
            log.warn("回调消息缺少去重标识，放行");
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
     *   <li>retryCount ≤ 3 → XADD 回原 Stream 尾部重试，ACK 原消息</li>
     *   <li>retryCount > 3 → XADD 到 DLQ Stream，ACK 原消息，ERROR 日志</li>
     * </ol>
     *
     * <p><b>注意：</b>重试放回 Stream 尾部意味着消息顺序可能改变。
     * 当前业务场景中各回调事件相互独立，顺序不重要。</p>
     *
     * @param streamKey     原消息所在的 Stream Key
     * @param consumerGroup  Consumer Group 名称（用于 ACK）
     * @param messageId     原消息的 Redis Stream Message ID
     * @param fields        消息体字段（用于重试/DLQ 时重新写入）
     * @param errorInfo     失败原因描述
     */
    public void markRetryOrDead(String streamKey, String consumerGroup,
                                 String messageId, Map<String, String> fields,
                                 String errorInfo) {
        // 基于消息内容生成逻辑 ID，确保 XADD 重新入队后重试计数器仍能延续
        // （Stream messageId 每次 XADD 都会变化，但同一条逻辑消息的 fields 哈希不变）
        String logicalId = computeLogicalId(fields);
        String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + streamKey + ":" + logicalId;

        try {
            Long retryCount = redisTemplate.opsForValue().increment(retryKey);
            if (retryCount == null) retryCount = 1L;
            redisTemplate.expire(retryKey, Duration.ofSeconds(RETRY_TTL_SECONDS));

            if (retryCount <= MAX_RETRIES) {
                // 指数退避：第 N 次重试延迟 2^N 秒（capped at 60s）
                long delaySec = Math.min((long) Math.pow(2, retryCount), MAX_BACKOFF_SECONDS);
                Map<String, String> delayedFields = new LinkedHashMap<>(fields);
                delayedFields.put("_retry_at", String.valueOf(Instant.now().getEpochSecond() + delaySec));
                redisTemplate.opsForStream().add(streamKey, delayedFields);
                log.warn("消息处理失败，{}s 后重试 ({}/{}): stream={}, msgId={}, error={}",
                    delaySec, retryCount, MAX_RETRIES, streamKey, messageId, errorInfo);
            } else {
                // 移入死信队列
                moveToDlq(streamKey, messageId, fields, retryCount, errorInfo);
            }

            // 无论重试还是 DLQ，ACK 原消息（已移走，不再占 PEL）
            ackSafely(streamKey, consumerGroup, messageId);

        } catch (Exception e) {
            // Redis 操作失败，兜底 ACK 防止 PEL 堆积
            log.error("重试/DLQ 操作失败，兜底 ACK: stream={}, msgId={}", streamKey, messageId, e);
            ackSafely(streamKey, consumerGroup, messageId);
        }
    }

    // ================================================================
    // ③ PEL 崩溃回收
    // ================================================================

    /**
     * 扫描指定 Stream 的 PEL，回收 idle 超时的孤消息。
     *
     * <p>PEL 里有消息意味着两种情况：</p>
     * <ol>
     *   <li>Worker 在 READ 和 ACK 之间崩溃（JVM crash / kill -9）——真孤儿；</li>
     *   <li>带 {@code _retry_at} 的退避重试消息未到期，Worker 故意不 ACK 留在 PEL。</li>
     * </ol>
     *
     * <p><b>回收逻辑（对每条 idle > idleMs 的消息）：</b>
     * <ol>
     *   <li>读 {@code _retry_at}：若仍在未来，跳过（留 PEL 等待到期），不 INCR、不重投；</li>
     *   <li>到期或缺失 → INCR 重试计数器；</li>
     *   <li>retryCount ≤ 3 → XADD 回原 Stream 尾部（不再设置 {@code _retry_at}，让
     *       Worker 立即处理）→ ACK 原消息；</li>
     *   <li>retryCount > 3 → 移入 DLQ → ACK 原消息。</li>
     * </ol>
     *
     * <p><b>为什么 XCLAIM 后必须 re-enqueue：</b>
     * XCLAIM 把消息分配给 recovery consumer，但正常 Worker 的 XREADGROUP
     * 使用 {@code >} 只读新消息，不会读 recovery consumer 的 PEL。
     * 所以 claim 拿到消息体后必须 XADD 回 Stream 让正常 Worker 消费。</p>
     *
     * @param streamKey       Stream Key
     * @param consumerGroup   Consumer Group 名称
     * @param recoveryConsumer XCLAIM 使用的恢复消费者名称
     * @param idleMs          消息 idle 超过此毫秒数才回收（建议 30000ms）
     * @return 回收的消息数量
     */
    public int recoverOrphanedPending(String streamKey, String consumerGroup,
                                       String recoveryConsumer, long idleMs) {
        int recovered = 0;
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                .pending(streamKey, consumerGroup);
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return 0;
            }

            PendingMessages pending = redisTemplate.opsForStream().pending(
                streamKey, consumerGroup, Range.unbounded(), 50L);

            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery() == null) continue;
                long idle = pm.getElapsedTimeSinceLastDelivery().toMillis();
                if (idle < idleMs) continue;

                String msgId = pm.getIdAsString();

                try {
                    // 先 XCLAIM 获取消息体，用内容哈希做重试 key（与 markRetryOrDead 一致）
                    List<MapRecord<String, Object, Object>> claimed;
                    try {
                        claimed = redisTemplate.opsForStream().claim(
                            streamKey, consumerGroup, recoveryConsumer,
                            Duration.ofMillis(idleMs),
                            RecordId.of(msgId));
                    } catch (NullPointerException npe) {
                        // 消息体已被 stream 清理（如 MAXLEN/MINID 裁剪），
                        // 但 PEL 条目还在 — 直接 ACK 删除僵尸 pending。
                        // 这是真正的丢消息：该消息永远不会被处理，故用 ERROR + 计数，
                        // 使「trim 砍掉未消费消息」这件事在监控里可见。
                        leakMetrics.zombiePendingDropped();
                        log.error("PEL 僵尸消息（body 已被 Stream 裁剪），消息永久丢失: "
                            + "stream={}, msgId={}, idle={}ms — 自动 ACK",
                            streamKey, msgId, idle);
                        ackSafely(streamKey, consumerGroup, msgId);
                        continue;
                    }

                    if (claimed == null || claimed.isEmpty()) {
                        continue;
                    }

                    Map<String, String> fields = toStringMap(claimed.get(0).getValue());
                    fields.remove("_dlq_origin_stream");
                    fields.remove("_dlq_origin_msgid");
                    fields.remove("_dlq_retry_count");
                    fields.remove("_dlq_last_error");
                    fields.remove("_dlq_time");

                    // 尊重退避窗口：若 _retry_at 仍在未来，说明这是正常退避重试而非崩溃孤儿，
                    // 跳过（留 PEL）待到期后由下一轮回收重投，避免 INCR 计数器导致消息被误判 DLQ
                    String retryAt = fields.remove("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > Instant.now().getEpochSecond()) {
                                log.debug("PEL 回收跳过（退避未到期）: stream={}, msgId={}, retryAt={}",
                                    streamKey, msgId, retryAt);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    // 基于消息内容生成逻辑 ID，与 markRetryOrDead 共享计数器
                    String logicalId = computeLogicalId(fields);
                    String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + streamKey + ":" + logicalId;

                    Long retryCount = redisTemplate.opsForValue().increment(retryKey);
                    if (retryCount == null) retryCount = 1L;
                    redisTemplate.expire(retryKey, Duration.ofSeconds(RETRY_TTL_SECONDS));

                    if (retryCount <= MAX_RETRIES) {
                        // 退避已到期或本就无 _retry_at（真崩溃孤儿），直接重投（不再设置 _retry_at），
                        // 让 Worker 下次读到后立即处理，避免“退避→回收→再退避”的死循环
                        redisTemplate.opsForStream().add(streamKey, fields);
                        ackSafely(streamKey, consumerGroup, msgId);
                        log.warn("PEL 回收: XCLAIM + re-enqueue, stream={}, msgId={}, idle={}ms, retry={}",
                            streamKey, msgId, idle, retryCount);
                        recovered++;
                    } else {
                        moveToDlq(streamKey, msgId, fields, retryCount,
                            "PEL idle timeout " + idle + "ms");
                        ackSafely(streamKey, consumerGroup, msgId);
                        recovered++;
                    }
                } catch (Exception inner) {
                    log.error("PEL 回收单条消息失败: stream={}, msgId={}", streamKey, msgId, inner);
                }
            }
        } catch (Exception e) {
            log.error("PEL 回收扫描失败: stream={}, group={}", streamKey, consumerGroup, e);
        }
        return recovered;
    }

    // ================================================================
    // ④ 企微异常分类 — 按异常类型决策重试/死信/令牌刷新/限流等待
    // ================================================================

    /**
     * 根据企微异常类型返回建议的处理动作。
     *
     * <p>调用方（如 CallbackWorker、TagWorker）在 catch 到
     * {@link WecomApiException} 后调用此方法获取处理策略：
     * <ul>
     *   <li><b>DLQ</b> — 永久故障，不入重试直接移入死信队列</li>
     *   <li><b>REFRESH_TOKEN_AND_RETRY</b> — Token 过期，刷新后重试一次</li>
     *   <li><b>WAIT_AND_RETRY</b> — 频率限制，等待 Retry-After 后重试一次</li>
     *   <li><b>RETRY</b> — 瞬时故障，走正常重试流程（最多 3 次指数退避）</li>
     * </ul>
     *
     * @param e 捕获到的异常
     * @return 建议的处理动作
     */
    public static ErrorAction classifyWecomError(Throwable e) {
        if (e instanceof WecomPermanentException) {
            return ErrorAction.DLQ;
        }
        if (e instanceof WecomTokenExpiredException) {
            return ErrorAction.REFRESH_TOKEN_AND_RETRY;
        }
        if (e instanceof WecomRateLimitException) {
            return ErrorAction.WAIT_AND_RETRY;
        }
        if (e instanceof WecomTransientException) {
            return ErrorAction.RETRY;
        }
        // 未知异常按瞬时故障处理
        return ErrorAction.RETRY;
    }

    /** 企微异常处理动作枚举 */
    public enum ErrorAction {
        /** 永久故障，直接入 DLQ，不可重试 */
        DLQ,
        /** Token 过期，刷新后重试一次 */
        REFRESH_TOKEN_AND_RETRY,
        /** 频率限制，等待 Retry-After 后重试一次 */
        WAIT_AND_RETRY,
        /** 瞬时故障，走正常重试流程 */
        RETRY
    }

    // ================================================================
    // ⑤ DLQ 统计 & 重放
    // ================================================================

    /**
     * 获取 DLQ Stream 当前长度。
     *
     * @return Stream 中的消息数量，异常时返回 -1
     */
    public long dlqSize() {
        try {
            Long size = redisTemplate.opsForStream().size(RedisConfig.DLQ_STREAM_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("DLQ size 查询失败", e);
            return -1;
        }
    }

    /** 单轮自动重放条数上限：一次性把 DLQ 全部灌回各 Stream 会瞬间压垮下游消费与企微 API */
    private static final int MAX_REPLAY_PER_PASS = 1000;

    /** DLQ 扫描上限相对 {@code DLQ_STREAM_MAXLEN} 的余量（XTRIM 是近似裁剪，实际长度可能略超） */
    private static final long SCAN_SLACK = 1000L;

    /**
     * 一轮 DLQ 重放的结果。
     *
     * @param replayed  本轮实际重放并移出 DLQ 的条数
     * @param exhausted 已达自动重放上限、留在 DLQ 等人工处理的条数（本轮额度用尽后仍如实统计，
     *                  调用方据此告警，不能因为「这轮没轮到重放」就被略过）
     * @param deferred  因本轮额度用尽而推迟到下一轮的条数（非零表示 DLQ 还没排空）
     */
    public record ReplaySummary(int replayed, int exhausted, int deferred) {}

    /**
     * 将 DLQ 中的所有消息重放到指定 Stream，然后逐条删除（防丢消息）。
     *
     * <p>每条消息优先使用 {@code _dlq_origin_stream} 元数据字段作为重放目标，
     * 若该字段缺失或为空则回退到 {@code targetStreamKey}。
     * 与 {@link #replayDlq(String)} 不同，此方法读取全部消息后用 XDEL 逐条删除，
     * 而非截断整个 Stream，避免因 count 限制丢消息。</p>
     *
     * @param targetStreamKey 默认重放目标 Stream（当消息无 _dlq_origin_stream 时使用）
     * @return 本轮重放结果
     */
    public ReplaySummary replayAllDlq(String targetStreamKey) {
        return replayAllDlq(targetStreamKey, false);
    }

    /**
     * 全量重放的实现，区分「自动重放」与「人工重放」两种语义。
     *
     * <p>{@code autoReplay=true}（{@code PatrolWorker} 周期性调用）时套用
     * {@link RedisConfig#DLQ_MAX_AUTO_REPLAYS} 上限，达限的消息留在 DLQ 等人工介入，
     * 避免「重放 → 失败 → 再入 DLQ」每 30 分钟无限循环、持续空耗企微 API 配额。
     * 计数绑定在 {@code dlq:replay:{target}:{logicalId}} 这一独立 Key 上，而非消息字段 ——
     * 消息体里的字段会被反复写回 Stream，而现有的 DLQ 元数据剥离逻辑并不认识它的存在。</p>
     *
     * <p>{@code autoReplay=false}（管理端点人工触发）时不受上限约束，并重置该消息的
     * 自动重放计数，让它重新获得完整的自动重放机会。</p>
     *
     * @param targetStreamKey 默认重放目标 Stream
     * @param autoReplay      是否为周期性自动重放
     * @return 本轮重放结果
     */
    public ReplaySummary replayAllDlq(String targetStreamKey, boolean autoReplay) {
        int replayed = 0;
        int exhausted = 0;
        int deferred = 0;
        boolean scanComplete = false;
        Set<String> presentCounters = new HashSet<>();
        try {
            // 必须扫完整个 DLQ，不能只读队头若干条：达限消息不删除、会永久占住队头，
            // 只读队头时它们会把后面所有可恢复的消息全部挡住，自动重放整体静默失效
            int limit = (int) Math.min(Integer.MAX_VALUE, RedisConfig.DLQ_STREAM_MAXLEN + SCAN_SLACK);
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamReadOptions.empty().count(limit),
                    StreamOffset.fromStart(RedisConfig.DLQ_STREAM_KEY));
            scanComplete = records == null || records.size() < limit;

            if (records != null) {
                for (MapRecord<String, Object, Object> r : records) {
                    Map<String, String> fields = toStringMap(r.getValue());
                    // 读取来源 Stream（剥离前），用于自动路由到正确的目标
                    String originStream = fields.get("_dlq_origin_stream");
                    String target = (originStream != null && !originStream.isBlank())
                        ? originStream : targetStreamKey;
                    // 剥离 DLQ 元数据，避免动态字段参与逻辑 ID 计算
                    fields.remove("_dlq_origin_stream");
                    fields.remove("_dlq_origin_msgid");
                    fields.remove("_dlq_retry_count");
                    fields.remove("_dlq_last_error");
                    fields.remove("_dlq_time");
                    fields.remove("_retry_at");
                    String logicalId = computeLogicalId(fields);
                    presentCounters.add(target + ":" + logicalId);

                    // 先判本轮额度、再判达限：额度用尽时不能推进达限计数，
                    // 否则「本轮没轮到」会被当成「又重放失败一次」，白吃掉消息的重放预算
                    if (replayed >= MAX_REPLAY_PER_PASS) {
                        // 额度用尽也要把「已达限」和「本轮没轮到」分开：PatrolWorker 的
                        // dlq_replay_exhausted 告警只看 exhausted，一律记 deferred 会让这条
                        // 告警在 DLQ 超过一轮额度时静默。只读判断，不推进计数
                        if (autoReplay && autoReplayAlreadyExhausted(target, logicalId)) {
                            exhausted++;
                        } else {
                            deferred++;
                        }
                        continue;
                    }
                    // 自动重放达限：留在 DLQ，不删原消息，等人工介入
                    if (autoReplay && autoReplayExhausted(target, logicalId)) {
                        exhausted++;
                        continue;
                    }
                    if (!autoReplay) {
                        resetAutoReplayCount(target, logicalId);
                    }
                    // 重放前清理旧重试计数器，让消息获得全新重试次数
                    String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + target + ":" + logicalId;
                    redisTemplate.delete(retryKey);
                    redisTemplate.opsForStream().add(target, fields);
                    // 逐条删除，不丢消息
                    redisTemplate.opsForStream().delete(RedisConfig.DLQ_STREAM_KEY, r.getId());
                    replayed++;
                }
                if (replayed > 0 || exhausted > 0 || deferred > 0) {
                    log.info("DLQ 重放完成: 重放 {} 条 → {}，达上限保留 {} 条，本轮额度外顺延 {} 条",
                        replayed, targetStreamKey, exhausted, deferred);
                }
            }
            // 只有完整扫过 DLQ 才能回收：没扫到的消息不等于「已离开 DLQ」
            if (autoReplay && scanComplete) {
                gcReplayCounters(presentCounters);
            }
        } catch (Exception e) {
            log.error("DLQ 全量重放失败: target={}", targetStreamKey, e);
        }
        return new ReplaySummary(replayed, exhausted, deferred);
    }

    /**
     * 回收「消息已离开 DLQ」的自动重放计数 Key。
     *
     * <p>不回收的后果：{@code dlq:replay:{target}:{logicalId}} 会一直残留到 TTL（7 天）到期，
     * 而它记的是「这个内容被重放过几次」。tag 流的 DLQ 字段只有 {@code event}，
     * 事件体又恰好是 {@code external_userid/userid/state} 三个字段，因此同一客户经同一员工
     * 扫同一学校的活码每次都会算出同一个 logicalId —— 某条消息用光 5 次额度后（哪怕它后来成功了），
     * 这位客户之后任何一次新的、本来可恢复的失败都会被直接判为达限，一次自动重放都不给。</p>
     *
     * <p>只在本轮完整扫过 DLQ 时调用（见 {@link #replayAllDlq(String, boolean)}），
     * 否则会把没扫到的消息的计数误删。误删只会让封顶机制少拦几次，方向是安全的。</p>
     */
    private void gcReplayCounters(Set<String> presentCounters) {
        int removed = 0;
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(RedisConfig.DLQ_REPLAY_KEY_PREFIX + "*")
                .count(500)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String suffix = key.substring(RedisConfig.DLQ_REPLAY_KEY_PREFIX.length());
                if (presentCounters.contains(suffix)) continue;
                redisTemplate.delete(key);
                removed++;
            }
        } catch (Exception e) {
            log.warn("自动重放计数 Key 回收失败（不影响本轮重放）", e);
            return;
        }
        if (removed > 0) {
            log.debug("已回收 {} 个失效的自动重放计数 Key", removed);
        }
    }

    /**
     * 累计一次自动重放并判断是否已达上限。
     *
     * <p>Redis 异常时 fail-open 返回 false：宁可多试一次，也不要因计数不可用卡住消息。</p>
     *
     * <p>计数与告警只在「刚好越过上限」的那一次产生。达限消息此后每个重放周期都会被再次
     * 检查到，若每次都记，{@code dlq_replay_exhausted} 会把「1 条卡住」和「1000 条卡住」
     * 抹平成同一个持续上涨的数字，同时每周期刷出一条重复 WARN。</p>
     *
     * <p>计数 Key 的清理见 {@link #gcReplayCounters(Set)}：消息一旦离开 DLQ，
     * 其 Key 会被回收，后续同内容的新消息重新获得完整的重放额度。</p>
     */
    private boolean autoReplayExhausted(String target, String logicalId) {
        String key = RedisConfig.DLQ_REPLAY_KEY_PREFIX + target + ":" + logicalId;
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(RedisConfig.DLQ_REPLAY_TTL_SECONDS));
            if (n == null || n <= RedisConfig.DLQ_MAX_AUTO_REPLAYS) return false;
            if (n == RedisConfig.DLQ_MAX_AUTO_REPLAYS + 1L) {
                leakMetrics.dlqReplayExhausted();
                log.warn("死信自动重放已达 {} 次上限，保留在 DLQ 等待人工处理: target={}, logicalId={}",
                    RedisConfig.DLQ_MAX_AUTO_REPLAYS, target, logicalId);
            }
            return true;
        } catch (Exception e) {
            log.warn("死信自动重放计数失败，按未达上限处理: key={}", key, e);
            return false;
        }
    }

    /**
     * 只读判断该消息是否已达自动重放上限 —— 不推进计数。
     *
     * <p>仅在「本轮重放额度已用尽」时使用：那时消息根本没被重放，用
     * {@link #autoReplayExhausted} 判定会顺带 INCR，等于替这些消息白吃一次重放配额。
     * 用 get 读当前值，把已达限的消息如实计入 exhausted。</p>
     *
     * <p>读失败时 fail-open 返回 false（按未达限处理），与 {@link #autoReplayExhausted}
     * 的降级方向一致；这里不打日志，避免逐条消息刷屏。</p>
     */
    private boolean autoReplayAlreadyExhausted(String target, String logicalId) {
        String key = RedisConfig.DLQ_REPLAY_KEY_PREFIX + target + ":" + logicalId;
        try {
            String n = redisTemplate.opsForValue().get(key);
            return n != null && Long.parseLong(n) > RedisConfig.DLQ_MAX_AUTO_REPLAYS;
        } catch (Exception e) {
            return false;
        }
    }

    /** 人工重放时重置自动重放计数，使该消息重新获得完整的自动重放机会。 */
    private void resetAutoReplayCount(String target, String logicalId) {
        try {
            redisTemplate.delete(RedisConfig.DLQ_REPLAY_KEY_PREFIX + target + ":" + logicalId);
        } catch (Exception e) {
            log.warn("死信自动重放计数重置失败: target={}, logicalId={}", target, logicalId, e);
        }
    }

    /**
     * 将 DLQ 中的消息重放到指定 Stream，逐条删除已重放的消息。
     *
     * <p>每条消息优先使用 {@code _dlq_origin_stream} 元数据字段作为重放目标，
     * 若该字段缺失或为空则回退到 {@code targetStreamKey}。
     * 重放时保留原始消息的所有字段。
     * 一次最多重放 100 条死信，防止一次性压力过大。
     * 使用 XDEL 逐条删除而非截断整个 Stream，避免因 count 限制丢消息。</p>
     *
     * <p>这是人工重放入口，不受 {@link RedisConfig#DLQ_MAX_AUTO_REPLAYS} 约束，
     * 并会重置该消息的自动重放计数。</p>
     *
     * @param targetStreamKey 默认重放目标 Stream（当消息无 _dlq_origin_stream 时使用）
     * @return 重放的消息数量
     */
    public int replayDlq(String targetStreamKey) {
        int count = 0;
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamReadOptions.empty().count(100),
                    StreamOffset.fromStart(RedisConfig.DLQ_STREAM_KEY));

            if (records != null && !records.isEmpty()) {
                for (MapRecord<String, Object, Object> r : records) {
                    Map<String, String> fields = toStringMap(r.getValue());
                    // 读取来源 Stream（剥离前），用于自动路由到正确的目标
                    String originStream = fields.get("_dlq_origin_stream");
                    String target = (originStream != null && !originStream.isBlank())
                        ? originStream : targetStreamKey;
                    // 剥离 DLQ 元数据，避免动态字段参与逻辑 ID 计算
                    fields.remove("_dlq_origin_stream");
                    fields.remove("_dlq_origin_msgid");
                    fields.remove("_dlq_retry_count");
                    fields.remove("_dlq_last_error");
                    fields.remove("_dlq_time");
                    fields.remove("_retry_at");
                    // 重放前清理旧重试计数器，让消息获得全新重试次数
                    String logicalId = computeLogicalId(fields);
                    String retryKey = RedisConfig.DLQ_RETRY_KEY_PREFIX + target + ":" + logicalId;
                    redisTemplate.delete(retryKey);
                    // 人工重放：重置自动重放计数，使该消息重新获得完整的自动重放机会
                    resetAutoReplayCount(target, logicalId);
                    redisTemplate.opsForStream().add(target, fields);
                    // 逐条删除，不丢消息
                    redisTemplate.opsForStream().delete(RedisConfig.DLQ_STREAM_KEY, r.getId());
                    count++;
                }
                log.info("DLQ 重放完成: {} 条 → {}", count, targetStreamKey);
            }
        } catch (Exception e) {
            log.error("DLQ 重放失败: target={}", targetStreamKey, e);
        }
        return count;
    }

    // ================================================================
    // ⑥ 内部辅助方法
    // ================================================================

    /**
     * 直接将消息写入死信队列（不入重试流程）。
     *
     * <p>用于 {@link CustomerService#upsertFromCallback} 等场景：
     * Redis 锁竞争超限后直接降级到 DLQ，不经过 markRetryOrDead 的重试逻辑。</p>
     *
     * @param originStreamKey 来源 Stream Key（标记消息来源，如 {@link RedisConfig#CALLBACK_STREAM_KEY}）
     * @param fields          消息字段（会追加 _dlq_origin_stream / _dlq_time 等元数据）
     */
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
            // fail-open：DLQ 写入失败时不抛异常，消息留在 PEL 待 PEL 回收机制处理
            log.error("DLQ 写入失败（fail-open，消息留 PEL 待回收）: originStream={}", originStreamKey, e);
        }
    }

    /**
     * 将消息移入死信队列。
     */
    private void moveToDlq(String streamKey, String messageId,
                            Map<String, String> fields, long retryCount,
                            String errorInfo) {
        Map<String, String> dlqFields = new LinkedHashMap<>(fields);
        dlqFields.put("_dlq_origin_stream", streamKey);
        dlqFields.put("_dlq_origin_msgid", messageId);
        dlqFields.put("_dlq_retry_count", String.valueOf(retryCount));
        dlqFields.put("_dlq_last_error", errorInfo);
        dlqFields.put("_dlq_time", Instant.now().toString());

        redisTemplate.opsForStream().add(RedisConfig.DLQ_STREAM_KEY, dlqFields);
        redisTemplate.opsForStream().trim(RedisConfig.DLQ_STREAM_KEY,
            RedisConfig.DLQ_STREAM_MAXLEN, true);

        log.error("消息已移入死信队列: originStream={}, msgId={}, retries={}, error={}",
            streamKey, messageId, retryCount, errorInfo);
    }

    /**
     * 安全的 ACK 操作，失败时仅记录日志不抛异常。
     */
    private void ackSafely(String streamKey, String consumerGroup, String messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, messageId);
        } catch (Exception e) {
            log.error("ACK 失败: stream={}, group={}, msgId={}", streamKey, consumerGroup, messageId, e);
        }
    }

    /**
     * 将 Redis Stream 返回的 {@code Map<Object, Object>} 转换为 {@code Map<String, String>}。
     *
     * <p>Spring Data Redis Stream 的返回值中 key/value 可能是 byte[] 或 String，
     * 本方法统一转换为 String 类型，方便后续处理。</p>
     *
     * @param value 原始 Map（key/value 为 Object 类型）
     * @return 转换后的 String→String Map
     */
    private Map<String, String> toStringMap(Map<Object, Object> value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        }
        return result;
    }
}
