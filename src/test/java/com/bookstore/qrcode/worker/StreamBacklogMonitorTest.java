package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.ScheduledJobGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 消费停摆/积压巡检的判据与限流。
 *
 * <p>重点是防误报：空转的低频流（最新消息很旧、但已被消费组取走过）绝不能告警，
 * 否则每次巡检都会刷告警。
 */
@DisplayName("Stream 消费健康巡检")
class StreamBacklogMonitorTest {

    private static final long MIN = 60_000L;

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, Object, Object> ops;
    private AlertService alertService;
    private ScheduledJobGuard jobGuard;
    private StreamBacklogMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(StreamOperations.class);
        alertService = mock(AlertService.class);
        when(redisTemplate.opsForStream()).thenReturn(ops);

        jobGuard = mock(ScheduledJobGuard.class);
        // 锁：默认放行，直接执行任务体（真实的跨机互斥另有 ScheduledJobGuardIntegrationTest）
        when(jobGuard.runExclusive(anyString(), any(Duration.class), any(Runnable.class)))
            .thenAnswer(inv -> {
                inv.getArgument(2, Runnable.class).run();
                return true;
            });
        // 限流：默认「窗口内首次」，即允许告警；被限流的用例单独打桩
        when(jobGuard.firstWithin(anyString(), any(Duration.class))).thenReturn(true);

        monitor = new StreamBacklogMonitor(redisTemplate, alertService, jobGuard);

        // 默认五个流全都健康：最新消息 20 分钟前、已被取走、无 PEL 积压
        String[][] streams = {
            {RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP},
            {RedisConfig.TAG_STREAM_KEY, RedisConfig.TAG_CONSUMER_GROUP},
            {RedisConfig.DATAFILL_STREAM_KEY, RedisConfig.DATAFILL_CONSUMER_GROUP},
            {RedisConfig.OUTBOUND_STREAM_KEY, RedisConfig.OUTBOUND_CONSUMER_GROUP},
            {RedisConfig.TRANSFER_STREAM_KEY, RedisConfig.TRANSFER_CONSUMER_GROUP},
        };
        for (String[] s : streams) {
            stubStream(s[0], s[1], 20 * MIN, 20 * MIN, 20 * MIN, 0L);
        }
    }

    /**
     * 设定某条流的 XINFO 读数与积压队头。注意必须先构造好 helper 返回值再交给 {@code when(...)}：
     * 在未完成的 stubbing 里调 {@code mock()}/{@code when()} 会抛 UnfinishedStubbing。
     *
     * @param newestAgoMs    XINFO 的 {@code last-generated-id} 有多旧
     * @param deliveredAgoMs XINFO 的 {@code last-delivered-id} 有多旧
     * @param headAgoMs      队头未投递消息（XRANGE 第一条）有多旧 —— 停摆判据看的是它
     */
    private void stubStream(String streamKey, String group, long newestAgoMs, long deliveredAgoMs,
                            long headAgoMs, long pending) {
        StreamInfo.XInfoStream info = xInfoStream(id(newestAgoMs));
        StreamInfo.XInfoGroups groups = xInfoGroups(xInfoGroup(group, id(deliveredAgoMs), pending));
        MapRecord<String, Object, Object> head = mock(MapRecord.class);
        when(head.getId()).thenReturn(RecordId.of(id(headAgoMs)));
        when(ops.info(streamKey)).thenReturn(info);
        when(ops.groups(streamKey)).thenReturn(groups);
        when(ops.range(eq(streamKey), any(Range.class), any(Limit.class))).thenReturn(List.of(head));
    }

    @Test
    @DisplayName("消费组不再投递且消息已滞留 → 告警「消费停摆」")
    void alertsWhenGroupStopsDelivering() {
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            20 * MIN, 60 * MIN, 20 * MIN, 0L);

        monitor.checkAll();

        verify(alertService).createAlert(any(), eq("callback_stream_backlog"),
            eq(AgentAlert.AlertSeverity.high), contains("消费停摆"), any(), any());
    }

    @Test
    @DisplayName("高流量下队头积压已久（最新消息却是刚刚写入）→ 仍告警「消费停摆」")
    void alertsWhenHeadIsStaleEvenIfNewestEntryIsFresh() {
        // 消费线程全死时生产照常进行：若判据取最新一条的时间戳，这里就会被判成「正在追赶」而不报
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            1_000L, 60 * MIN, 20 * MIN, 0L);

        monitor.checkAll();

        verify(alertService).createAlert(any(), eq("callback_stream_backlog"),
            eq(AgentAlert.AlertSeverity.high), contains("消费停摆"), any(), any());
    }

    @Test
    @DisplayName("消息很旧但已被取走（空转流）→ 不告警")
    void silentWhenIdleStreamWasAlreadyConsumed() {
        // 默认 stub 就是这个形态：最新消息 20 分钟前，消费组指针也停在那里
        monitor.checkAll();

        verify(alertService, never()).createAlert(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("消费组落后但队头消息刚写入（正在追赶）→ 不告警")
    void silentWhenNewestEntryIsFresh() {
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            1_000L, 60 * MIN, 1_000L, 0L);

        monitor.checkAll();

        verify(alertService, never()).createAlert(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("消息被取走但 ACK 不掉，PEL 越阈值 → 告警「消费积压」")
    void alertsOnPendingBacklog() {
        stubStream(RedisConfig.TAG_STREAM_KEY, RedisConfig.TAG_CONSUMER_GROUP,
            20 * MIN, 20 * MIN, 20 * MIN, 250L);

        monitor.checkAll();

        verify(alertService).createAlert(any(), eq("tag_stream_backlog"),
            eq(AgentAlert.AlertSeverity.high), contains("250 条消息未 ACK"), any(), any());
    }

    @Test
    @DisplayName("限流窗口已被占用（guard 判定非首次）→ 不再告警")
    void skipsAlertWhenWindowAlreadyUsed() {
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            20 * MIN, 60 * MIN, 20 * MIN, 0L);
        when(jobGuard.firstWithin(anyString(), any(Duration.class))).thenReturn(false);

        monitor.checkAll();

        verify(alertService, never()).createAlert(any(), any(), any(), any(), any(), any());
        // 被限流时并未真的告警，不该去撤销窗口键
        verify(jobGuard, never()).clearThrottle(anyString());
    }

    @Test
    @DisplayName("限流键按「流 + 判据」分开，两种判据互不压制")
    void throttleKeyIsPerStreamAndSignal() {
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            20 * MIN, 60 * MIN, 20 * MIN, 0L);

        monitor.checkAll();

        verify(jobGuard).firstWithin(
            eq("throttle:alert:callback_stream_backlog:stall"), any(Duration.class));
    }

    @Test
    @DisplayName("告警落库失败（createAlert 返回 null）→ 撤销限流键，下一轮仍可告警")
    void clearsThrottleWhenAlertPersistenceFails() {
        stubStream(RedisConfig.CALLBACK_STREAM_KEY, RedisConfig.CALLBACK_CONSUMER_GROUP,
            20 * MIN, 60 * MIN, 20 * MIN, 0L);
        when(alertService.createAlert(any(), any(), any(), any(), any(), any())).thenReturn(null);

        monitor.checkAll();

        verify(jobGuard).clearThrottle("throttle:alert:callback_stream_backlog:stall");
    }

    // ==================== 构造 mock 的小工具 ====================

    /** 构造一个「写入于 agoMs 毫秒前」的 Stream ID（Redis 生成的 ID 前缀即写入毫秒） */
    private static String id(long agoMs) {
        return (System.currentTimeMillis() - agoMs) + "-0";
    }

    private static StreamInfo.XInfoStream xInfoStream(String lastGeneratedId) {
        StreamInfo.XInfoStream s = mock(StreamInfo.XInfoStream.class);
        when(s.lastGeneratedId()).thenReturn(lastGeneratedId);
        return s;
    }

    private static StreamInfo.XInfoGroup xInfoGroup(String name, String lastDeliveredId, long pending) {
        StreamInfo.XInfoGroup g = mock(StreamInfo.XInfoGroup.class);
        when(g.groupName()).thenReturn(name);
        when(g.lastDeliveredId()).thenReturn(lastDeliveredId);
        when(g.pendingCount()).thenReturn(pending);
        return g;
    }

    private static StreamInfo.XInfoGroups xInfoGroups(StreamInfo.XInfoGroup group) {
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        when(groups.isEmpty()).thenReturn(false);
        when(groups.stream()).thenReturn(Stream.of(group));
        return groups;
    }
}
