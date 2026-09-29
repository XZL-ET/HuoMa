package com.bookstore.qrcode.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「静默漏处理」计数器。
 *
 * <p>这些路径原先只写日志或什么都不写，出问题时没有任何可聚合的信号。计数器把
 * 「本该打上标签却没打」「本该处理的消息被丢弃」这类事件变成可抓取的数值，
 * 由 {@code /api/health/streams} 暴露。
 *
 * <p><b>计数是进程内累计值，重启归零</b>，语义为「本次启动以来」。因此不要对
 * 「非零」直接告警，否则每次部署都会误报；需要告警时请比对两次采集之间的增量。
 */
@Component
public class LeakMetrics {

    private final AtomicLong tagSkipNoWecomTagId = new AtomicLong();
    private final AtomicLong tagSkipMissingQrCode = new AtomicLong();
    private final AtomicLong tagPermanentFailure = new AtomicLong();
    private final AtomicLong zombiePendingDropped = new AtomicLong();
    private final AtomicLong dlqReplayExhausted = new AtomicLong();
    private final AtomicLong tagConsumerRestarted = new AtomicLong();
    private final AtomicLong callbackConsumerRestarted = new AtomicLong();

    /** 永久失败按企微 errcode 细分，用于识别未知错误码的集中爆发 */
    private final Map<Integer, AtomicLong> permanentFailureByErrcode = new ConcurrentHashMap<>();

    /** 标签已落本地库、但 wecomTagId 为空，无法同步到企微（漏打标）。 */
    public void tagSkipNoWecomTagId() {
        tagSkipNoWecomTagId.incrementAndGet();
    }

    /** 按 state 反查不到活码，整个自动打标流程被前置跳过（漏打标）。 */
    public void tagSkipMissingQrCode() {
        tagSkipMissingQrCode.incrementAndGet();
    }

    /** 企微打标返回非「客户关系失效」类永久错误，已转死信队列。 */
    public void tagPermanentFailure(int errcode) {
        tagPermanentFailure.incrementAndGet();
        permanentFailureByErrcode.computeIfAbsent(errcode, k -> new AtomicLong()).incrementAndGet();
    }

    /** PEL 里的消息体已被 Stream 裁剪掉，只能丢弃该 pending 条目。 */
    public void zombiePendingDropped() {
        zombiePendingDropped.incrementAndGet();
    }

    /** 死信消息自动重放次数已达上限，留在 DLQ 等待人工介入（停止无意义循环）。 */
    public void dlqReplayExhausted() {
        dlqReplayExhausted.incrementAndGet();
    }

    /** 打标消费线程意外退出后被监督循环重建。非零即代表打标链路曾整体停摆。 */
    public void tagConsumerRestarted() {
        tagConsumerRestarted.incrementAndGet();
    }

    /** 回调消费线程意外退出后被监督循环重建。非零即代表客户入库链路曾整体停摆。 */
    public void callbackConsumerRestarted() {
        callbackConsumerRestarted.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag_skip_no_wecom_tag_id", tagSkipNoWecomTagId.get());
        m.put("tag_skip_missing_qrcode", tagSkipMissingQrCode.get());
        m.put("tag_permanent_failure", tagPermanentFailure.get());
        m.put("zombie_pending_dropped", zombiePendingDropped.get());
        m.put("dlq_replay_exhausted", dlqReplayExhausted.get());
        m.put("tag_consumer_restarted", tagConsumerRestarted.get());
        m.put("callback_consumer_restarted", callbackConsumerRestarted.get());
        Map<Integer, Long> byCode = new TreeMap<>();
        permanentFailureByErrcode.forEach((k, v) -> byCode.put(k, v.get()));
        m.put("tag_permanent_failure_by_errcode", byCode);
        return m;
    }
}
