package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.repository.AgentAlertRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费停摆巡检跑在真实 Redis 上的端到端验证：真实 XINFO 读数 → 判据 → 告警落库。
 *
 * <p>单测里 XINFO 的返回值是手工捏的 mock，证明不了「真实 Redis 报出来的
 * {@code last-generated-id} / {@code last-delivered-id} / {@code pending} 就是巡检假设的形态」。
 * 这里对真实 Stream 断言，堵的就是这个缝。</p>
 */
@DisplayName("消费停摆巡检（真实 Redis）")
class StreamBacklogMonitorIntegrationTest extends BaseIntegrationTest {

    private static final String KEY = RedisConfig.CALLBACK_STREAM_KEY;
    private static final String GROUP = RedisConfig.CALLBACK_CONSUMER_GROUP;
    private static final String ALERT_TYPE = "callback_stream_backlog";
    /** 2000-01-01，用来伪造一条「很久以前写入」的消息 */
    private static final String ANCIENT_ID = "946684800000-0";

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private StreamBacklogMonitor monitor;
    @Autowired private AgentAlertRepository alertRepo;

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate.delete(KEY);
        alertRepo.deleteAll();
        // 限流状态是 static，同 JVM 内跨用例残留，不清会导致后一个用例静默不告警
        Field f = StreamBacklogMonitor.class.getDeclaredField("lastAlertAt");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("最新消息写入很久仍无人取走 → 落库一条「消费停摆」告警")
    void alertsOnRealUndeliveredAncientEntry() {
        // 先塞一条古早消息再建消费组：XADD 的显式 ID 必须大于当前最大 ID，
        // 所以只能反过来（空流上先写古早 ID，再让消费组从 0-0 开始）
        xAddWithId(ANCIENT_ID, "event", "{}");
        redisTemplate.opsForStream().createGroup(KEY, ReadOffset.from("0-0"), GROUP);

        monitor.checkAll();

        List<AgentAlert> alerts = openAlerts(ALERT_TYPE);
        assertThat(alerts).as("消费组指针停在 0-0，最新消息却是 2000 年的，必须告警").hasSize(1);
        assertThat(alerts.get(0).getDetail())
            .as("告警内容要能指向是哪条流、停了多久、该查谁")
            .contains("消费停摆").contains(KEY).contains("CallbackWorker");
    }

    @Test
    @DisplayName("高流量：队头积压已久、队尾却是刚写入的消息 → 仍告警「消费停摆」")
    void alertsWhenHeadIsStaleWhileNewestEntryIsFresh() {
        xAddWithId(ANCIENT_ID, "event", "{}");                            // 队头：2000 年写入，从未被取走
        redisTemplate.opsForStream().add(KEY, Map.of("event", "{}"));      // 队尾：刚刚写入
        redisTemplate.opsForStream().createGroup(KEY, ReadOffset.from("0-0"), GROUP);

        monitor.checkAll();

        // 判据取「最新一条」的话，这里最新消息是 1 秒前的，消费线程全死也不报 —— 正是要防的漏报
        List<AgentAlert> alerts = openAlerts(ALERT_TYPE);
        assertThat(alerts).as("生产不停、消费停摆时必须靠队头报出来").hasSize(1);
        assertThat(alerts.get(0).getDetail()).contains("消费停摆").contains(ANCIENT_ID);
    }

    @Test
    @DisplayName("空转流又来一条新消息：队头那条已消费过 → 不告警")
    void silentWhenDeliveredHeadIsOldButNextEntryIsFresh() {
        xAddWithId(ANCIENT_ID, "event", "{}");
        redisTemplate.opsForStream().createGroup(KEY, ReadOffset.from("0-0"), GROUP);
        consumeAndAck();                                                  // last-delivered-id 停在 2000 年
        redisTemplate.opsForStream().add(KEY, Map.of("event", "{}"));      // 新消息尚未被取走

        monitor.checkAll();

        // XRANGE 下界若不排除 last-delivered-id，这里会拿 2000 年那条已消费的消息报停摆
        assertThat(openAlerts(ALERT_TYPE))
            .as("已消费的队头不能被当成积压，否则低频流上每条新消息落地都会误报")
            .isEmpty();
    }

    @Test
    @DisplayName("消息被取走但一条都没 ACK → 落库一条「消费积压」告警")
    void alertsOnRealPendingBacklog() {
        for (int i = 0; i < 250; i++) {
            redisTemplate.opsForStream().add(KEY, Map.of("event", "{}"));
        }
        redisTemplate.opsForStream().createGroup(KEY, ReadOffset.from("0-0"), GROUP);
        // 取走但不 ACK，制造 PEL 堆积
        redisTemplate.opsForStream().read(
            Consumer.from(GROUP, "test-consumer"),
            StreamReadOptions.empty().count(250),
            StreamOffset.create(KEY, ReadOffset.lastConsumed()));

        monitor.checkAll();

        List<AgentAlert> alerts = openAlerts(ALERT_TYPE);
        assertThat(alerts).as("真实 XINFO GROUPS 的 pending 应被读成 250").hasSize(1);
        assertThat(alerts.get(0).getDetail()).contains("250 条消息未 ACK").contains(KEY);
    }

    @Test
    @DisplayName("消息刚写入且尚未消费（正在追赶）→ 不告警")
    void silentOnFreshUnconsumedEntry() {
        redisTemplate.opsForStream().add(KEY, Map.of("event", "{}"));
        redisTemplate.opsForStream().createGroup(KEY, ReadOffset.from("0-0"), GROUP);

        monitor.checkAll();

        assertThat(openAlerts(ALERT_TYPE)).as("刚落地的消息不该触发停摆告警").isEmpty();
    }

    private void xAddWithId(String id, String field, String value) {
        redisTemplate.execute((RedisCallback<Object>) conn -> conn.execute("XADD",
            KEY.getBytes(StandardCharsets.UTF_8),
            id.getBytes(StandardCharsets.UTF_8),
            field.getBytes(StandardCharsets.UTF_8),
            value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 取走并 ACK 当前所有未投递消息 —— 模拟消费正常的空转流。 */
    private void consumeAndAck() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            Consumer.from(GROUP, "test-consumer"),
            StreamReadOptions.empty().count(100),
            StreamOffset.create(KEY, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> r : records) {
            redisTemplate.opsForStream().acknowledge(KEY, GROUP, r.getId());
        }
    }

    private List<AgentAlert> openAlerts(String alertType) {
        return alertRepo.findByStatusOrderByCreatedAtDesc(
                AgentAlert.AlertStatus.open, PageRequest.of(0, 100))
            .getContent().stream()
            .filter(a -> alertType.equals(a.getAlertType()))
            .toList();
    }
}
