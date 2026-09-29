package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stream 消费健康巡检 —— 发现「消费线程已停摆」这类否则完全静默的故障。
 *
 * <p><b>为什么需要它：</b>所有 Worker 都在 {@code @PostConstruct} 里一次性提交消费线程，
 * 之后没有任何补位机制。线程一旦从消费循环里返回（抛出不在 {@code catch (Exception)}
 * 覆盖范围内的 {@link Throwable}，或被中断后由外层 {@code catch (InterruptedException)}
 * 接住），该线程就永久消失且不留痕迹：{@link StreamBacklogMonitor} 之外的监控看不到任何异常。
 * 表现是 Stream 只涨不消，直到下一次重启进程才恢复。</p>
 *
 * <p><b>判据一 —— 投递停滞（对应「消费线程全死了」）：</b>消费组的
 * {@code last-delivered-id} 落后于 Stream 的 {@code last-generated-id}，且队头那条未投递的消息
 * 已积压超过 {@link #DELIVERY_STALL_MINUTES} 分钟。说明这段时间内没有任何消费者取过消息。</p>
 *
 * <p>量的是<b>队头</b>（最老的未投递消息）而不是最新那条的时间戳。后者量的是「生产有没有停歇」：
 * 生产持续不断时它永远归零，而消费线程全死、消息只涨不消恰恰是最该报的场景。
 * 改用队头后判据与生产速率无关，高流量、低频流（如 DataFill 的补全指令）都成立。</p>
 *
 * <p><b>判据二 —— PEL 积压（对应「还在取消息但 ACK 不掉」）：</b>未 ACK 消息数超过
 * {@link #PENDING_BACKLOG_THRESHOLD}，通常是线程卡在下游 API 上，或消费速度追不上生产速度。</p>
 *
 * <p>两个判据互补：线程全死时消息根本不会被取走（判据一命中、判据二不动），
 * 线程活着但处理不动时消息会被取走但不 ACK（判据二命中、判据一不涨）。</p>
 *
 * <p><b>告警限流：</b>每条流的每种判据每小时最多告警一次，避免积压持续存在时刷屏；
 * 两种判据分开限流，否则先报「积压」后转「停摆」时，更严重的停摆会被压制一小时。</p>
 *
 * <p><b>不受 PEL 重投干扰：</b>{@link PatrolWorker#recoverOrphanedPending()} 每分钟会把 PEL 里
 * 超时未 ACK 的消息重新 XADD 回 Stream，重投的消息带当前时间戳、落在队尾。判据一读的是队头 ——
 * 消费者死掉之后才生产、从未被取走的那批消息，时间戳保持原样 —— 因此重投不会推迟发现。</p>
 *
 * @author Bookstore Dev
 * @since 1.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamBacklogMonitor {

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;

    /**
     * 投递停滞判定阈值（分钟）：队头那条未投递的消息积压超过该时长，视为消费链停摆。
     * 取 15 分钟远大于正常抖动（消费者拉取间隔是秒级，退避重试最长 60s），不会误报。
     */
    private static final long DELIVERY_STALL_MINUTES = 15;

    /**
     * PEL 未 ACK 消息数告警阈值（默认值，可用 {@code app.redis-stream.pending-backlog-threshold} 覆盖）。
     *
     * <p>取值要高于「一次正常退避重投」造成的 pending：带 {@code _retry_at} 的退避消息到期前是
     * 故意不 ACK 的，会短期留在 PEL。callback 流按 50 条/批 × 4 线程算，一次全网退避就能顶到 200 ——
     * 此时确实反常，但未必是故障。偏低的表现是报警偏噪，可按生产 pending 的实际分布上调。</p>
     */
    @Value("${app.redis-stream.pending-backlog-threshold:200}")
    private long pendingBacklogThreshold = 200L;

    /** 每条流、每种判据的告警限流窗口 */
    private static final long ALERT_THROTTLE_MS = 3600_000L;

    /**
     * 各流各判据的上次告警时间，Key 为 {@code alertType:signal}。
     * static 避免 CGLIB 代理实例字段分裂导致限流失效。
     *
     * <p>限流按判据分开计：否则同一条流先报「积压」后转成「停摆」时，
     * 更严重的停摆会被较轻的积压告警压制最多一小时。</p>
     */
    private static final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    /** 巡检目标：一条 Stream + 它的消费组 + 负责消费它的 Worker（仅用于告警文案） */
    private record StreamWatch(String streamKey, String group, String workerName, String alertType) {}

    private static final List<StreamWatch> WATCHES = List.of(
        new StreamWatch(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            "CallbackWorker", "callback_stream_backlog"),
        new StreamWatch(RedisConfig.TAG_STREAM_KEY, RedisConfig.TAG_CONSUMER_GROUP,
            "TagWorker", "tag_stream_backlog"),
        new StreamWatch(RedisConfig.DATAFILL_STREAM_KEY, RedisConfig.DATAFILL_CONSUMER_GROUP,
            "DataFillWorker", "datafill_stream_backlog"),
        new StreamWatch(RedisConfig.OUTBOUND_STREAM_KEY, RedisConfig.OUTBOUND_CONSUMER_GROUP,
            "OutboundMsgWorker", "outbound_stream_backlog"),
        new StreamWatch(RedisConfig.TRANSFER_STREAM_KEY, RedisConfig.TRANSFER_CONSUMER_GROUP,
            "TransferWorker", "transfer_stream_backlog")
    );

    /**
     * 每 5 分钟巡检一轮。错开 30 秒启动，避免与 {@link PatrolWorker} 的整点巡检同时打 Redis。
     */
    @Scheduled(cron = "30 */5 * * * *")
    public void checkAll() {
        for (StreamWatch watch : WATCHES) {
            try {
                check(watch);
            } catch (Exception e) {
                // 单条流检查失败不影响其余流；Stream 不存在时 XINFO 会抛异常，属正常情况
                log.debug("Stream 积压检查跳过: stream={}, {}", watch.streamKey(), e.getMessage());
            }
        }
    }

    private void check(StreamWatch watch) {
        StreamInfo.XInfoStream info = redisTemplate.opsForStream().info(watch.streamKey());
        StreamInfo.XInfoGroup group = findGroup(watch);
        if (info == null || group == null) return;
        String lastGenerated = info.lastGeneratedId();
        if (lastGenerated == null) return;

        // 比较 ID 的 (时间戳, 序列号) 两段：只比时间戳会漏掉「同一毫秒内写入、但只被取走了一部分」
        // 的尾巴（delivered=1000-0 / generated=1000-5 时间戳相等，会被当成已追平）
        if (olderThan(group.lastDeliveredId(), lastGenerated)) {
            String stalledId = firstUndeliveredId(watch.streamKey(), group.lastDeliveredId());
            long[] head = parseId(stalledId);
            if (head != null && head[0] > 0) {
                long stalledMinutes = (System.currentTimeMillis() - head[0]) / 60_000L;
                if (stalledMinutes >= DELIVERY_STALL_MINUTES) {
                    String msg = String.format(
                        "%s 消费停摆: 队头消息(%s)已积压 %d 分钟仍未被任何消费者取走，"
                            + "消费线程可能已全部退出，请检查 %s",
                        watch.streamKey(), stalledId, stalledMinutes, watch.workerName());
                    log.error("{}", msg);
                    raise(watch, "stall", msg);
                    return;
                }
            }
        }

        Long pending = group.pendingCount();
        if (pending != null && pending >= pendingBacklogThreshold) {
            String msg = String.format(
                "%s 消费积压: %d 条消息未 ACK（PEL 堆积），消费速度或异常，请检查 %s",
                watch.streamKey(), pending, watch.workerName());
            log.warn("{}", msg);
            raise(watch, "pending", msg);
        }
    }

    /**
     * 取积压队列的队头 —— {@code lastDeliveredId} 之后的第一条消息 ID，没有则返回 null。
     *
     * <p>必须跳过 {@code lastDeliveredId} 自己：它是「已经投递过」的那条，把它算进来会拿已消费的
     * 老消息的年龄去判停摆，低频流上一条新消息刚落地、还没轮到消费者取走的瞬间就会误报。</p>
     *
     * <p>用「包含下界 + 跳过相等那条」而不是排他下界：本项目的 Lettuce 版本对 Stream 的排他下界
     * 直接抛 {@code RedisSystemException}（{@code Range.Bound.exclusive} 实测不可用）。
     * 因此取 2 条即可 —— 至多只有 {@code lastDeliveredId} 这一条需要跳过。</p>
     *
     * <p>带 {@code COUNT}：这个方法被调用时积压往往正是几万条，不带 COUNT 的 XRANGE
     * 会把整个积压一次性拉进内存。</p>
     */
    private String firstUndeliveredId(String streamKey, String lastDeliveredId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
            streamKey,
            Range.of(Range.Bound.inclusive(lastDeliveredId), Range.Bound.inclusive("+")),
            Limit.limit().count(2));
        if (records == null) return null;
        for (MapRecord<String, Object, Object> r : records) {
            String id = r.getId().getValue();
            if (!id.equals(lastDeliveredId)) return id;
        }
        return null;
    }

    /**
     * 把 Stream ID 解析成 {@code [时间戳 ms, 序列号]}；无法解析（含 null）时返回 null。
     *
     * <p>不能直接比字符串：{@code "1-9"} 字典序大于 {@code "1-10"}，比出来是反的。</p>
     */
    private static long[] parseId(String id) {
        if (id == null) return null;
        int dash = id.indexOf('-');
        try {
            long ts = Long.parseLong(dash < 0 ? id : id.substring(0, dash));
            long seq = dash < 0 ? 0L : Long.parseLong(id.substring(dash + 1));
            return new long[]{ts, seq};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code a} 是否严格早于 {@code b}（先比时间戳，再比序列号）；任一侧无法解析时返回 false。 */
    private static boolean olderThan(String a, String b) {
        long[] x = parseId(a);
        long[] y = parseId(b);
        if (x == null || y == null) return false;
        return x[0] != y[0] ? x[0] < y[0] : x[1] < y[1];
    }

    private StreamInfo.XInfoGroup findGroup(StreamWatch watch) {
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(watch.streamKey());
        if (groups == null || groups.isEmpty()) return null;
        return groups.stream()
            .filter(g -> watch.group().equals(g.groupName()))
            .findFirst().orElse(null);
    }

    /**
     * 发出告警，受「每流每判据每小时一次」的限流约束。
     *
     * @param signal 判据标识（{@code stall} / {@code pending}），参与限流 Key 但不进告警类型，
     *               以保证两种判据各自限流、同时又不改变告警类型的历史聚合
     */
    private void raise(StreamWatch watch, String signal, String message) {
        String throttleKey = watch.alertType() + ":" + signal;
        long now = System.currentTimeMillis();
        Long last = lastAlertAt.get(throttleKey);
        if (last != null && now - last < ALERT_THROTTLE_MS) {
            log.debug("Stream 积压告警限流中，跳过: {}", throttleKey);
            return;
        }
        // 先告警再记限流：createAlert 失败时不抛异常、静默返回 null，
        // 先记限流的话这条告警就彻底没了、还会被压一个小时
        alertService.createAlert(null, watch.alertType(), AgentAlert.AlertSeverity.high,
            message, AgentAlert.AutoAction.none, null);
        lastAlertAt.put(throttleKey, now);
    }
}
