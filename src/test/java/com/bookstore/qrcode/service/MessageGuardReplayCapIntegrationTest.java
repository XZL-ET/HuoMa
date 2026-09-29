package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 坐实死信自动重放的上限语义：确定性失败的消息不会被 PatrolWorker 无限重放，
 * 但人工重放不受限且会重置计数。
 *
 * <p>用内嵌 Redis 真实读写 Stream —— 重放是否真的 XDEL 了 DLQ 原消息、
 * 达限时是否真的保留，只有对真实 Stream 断言才作数。
 */
@DisplayName("死信自动重放上限")
class MessageGuardReplayCapIntegrationTest extends BaseIntegrationTest {

    private static final String ORIGIN = RedisConfig.TAG_STREAM_KEY;

    @Autowired private MessageGuardService messageGuardService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private LeakMetrics leakMetrics;

    @BeforeEach
    void cleanUp() {
        redisTemplate.delete(RedisConfig.DLQ_STREAM_KEY);
        redisTemplate.delete(ORIGIN);
        // 清理可能残留的重放/重试计数，避免跨用例互相干扰
        Set<String> keys = redisTemplate.keys(RedisConfig.DLQ_REPLAY_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        Set<String> retryKeys = redisTemplate.keys(RedisConfig.DLQ_RETRY_KEY_PREFIX + "*");
        if (retryKeys != null && !retryKeys.isEmpty()) redisTemplate.delete(retryKeys);
    }

    private void sendMessageToDlq() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("external_userid", "wmXXX");
        fields.put("userid", "user1");
        fields.put("state", "SCH-1");
        messageGuardService.sendToDlq(ORIGIN, fields);
    }

    @Test
    @DisplayName("自动重放达上限后消息保留在 DLQ，不再无限循环")
    void autoReplayStopsAtCap() {
        int cap = RedisConfig.DLQ_MAX_AUTO_REPLAYS;

        for (int i = 1; i <= cap; i++) {
            sendMessageToDlq();
            int replayed = messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed();
            assertThat(replayed).as("第 %d 次自动重放应成功", i).isEqualTo(1);
        }

        // 第 cap+1 次：计数已超上限，消息必须留在 DLQ
        sendMessageToDlq();
        int replayed = messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed();

        assertThat(replayed).as("达上限后不应再重放").isZero();
        assertThat(messageGuardService.dlqSize())
            .as("消息必须保留在 DLQ 等待人工介入，而不是被丢弃")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("人工重放不受上限约束，并重置自动重放计数")
    void manualReplayIgnoresCapAndResetsCounter() {
        int cap = RedisConfig.DLQ_MAX_AUTO_REPLAYS;

        // 先耗尽自动重放配额（每轮 DLQ 里始终只有一条消息，避免计数含义歧义）
        for (int i = 1; i <= cap; i++) {
            sendMessageToDlq();
            assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed())
                .as("第 %d 次自动重放应成功", i).isEqualTo(1);
        }

        sendMessageToDlq();
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed())
            .as("自动重放此时应已停摆").isZero();
        assertThat(messageGuardService.dlqSize()).isEqualTo(1);

        // 人工重放：无视上限，并清空该条消息
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY).replayed())
            .as("人工重放必须始终可用").isEqualTo(1);
        assertThat(messageGuardService.dlqSize()).isZero();

        // 计数被重置 → 自动重放重新获得完整配额
        sendMessageToDlq();
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed())
            .as("重置后自动重放应恢复").isEqualTo(1);
    }

    @Test
    @DisplayName("达限计数只在越过上限的那一刻记一次，不随每个重放周期持续上涨")
    void exhaustedCounterIncrementsOnlyOnTransition() {
        long before = exhaustedCount();

        int cap = RedisConfig.DLQ_MAX_AUTO_REPLAYS;
        for (int i = 1; i <= cap; i++) {
            sendMessageToDlq();
            messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
        }

        // 第 cap+1 次尝试：刚好越过上限，应记一次
        sendMessageToDlq();
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed()).isZero();
        assertThat(exhaustedCount() - before).as("越限那一次应记数").isEqualTo(1);

        // 消息仍卡在 DLQ，后续每个周期都会再检查到它 —— 计数不应再涨
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed()).isZero();
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed()).isZero();

        assertThat(exhaustedCount() - before)
            .as("重复记数会把「1 条卡住」和「1000 条卡住」抹平")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("队头全被达限消息占住时，队尾新消息仍能被重放（不再只扫队头 1000 条）")
    void exhaustedHeadDoesNotBlockTail() {
        int cap = RedisConfig.DLQ_MAX_AUTO_REPLAYS;
        // 与单轮重放上限同量级：旧实现每次只读队头 1000 条，这 1000 条恰好把它读满
        int headSize = 1000;

        // 让 headSize 条消息各自用满自动重放额度：每轮重放出去后再投回 DLQ 时，
        // 计数 Key 因为「消息仍在扫描结果里」而躲过 GC，所以 6 轮就能全部顶到上限
        for (int round = 1; round <= cap + 1; round++) {
            sendDistinctMessagesToDlq(headSize, "head");
            MessageGuardService.ReplaySummary summary =
                messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
            if (round <= cap) {
                assertThat(summary.replayed()).as("第 %d 轮应全部重放", round).isEqualTo(headSize);
            } else {
                assertThat(summary.replayed()).as("第 %d 轮应全部达限停摆", round).isZero();
                assertThat(summary.exhausted()).as("第 %d 轮应全部达限", round).isEqualTo(headSize);
            }
        }
        assertThat(messageGuardService.dlqSize()).as("达限消息应留在 DLQ").isEqualTo(headSize);

        // 队尾追加一条全新消息：计数从 0 开始，本应可以重放
        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put("external_userid", "wm-tail-fresh");
        fresh.put("userid", "user1");
        fresh.put("state", "SCH-1");
        messageGuardService.sendToDlq(ORIGIN, fresh);

        MessageGuardService.ReplaySummary pass =
            messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
        assertThat(pass.exhausted()).as("队头 %d 条仍达限", headSize).isEqualTo(headSize);
        assertThat(pass.replayed()).as("队尾新消息不能被队头的达限消息挡住").isEqualTo(1);
        assertThat(messageGuardService.dlqSize()).as("只剩达限消息").isEqualTo(headSize);

        // 达限消息还留在 DLQ，其计数 Key 必须活着 —— 被 GC 误删的话下一轮会把它当新消息重放
        MessageGuardService.ReplaySummary again =
            messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
        assertThat(again.exhausted()).as("仍在 DLQ 的达限消息计数不能被 GC 掉").isEqualTo(headSize);
        assertThat(again.replayed()).isZero();
    }

    @Test
    @DisplayName("本轮额度被占满时，排在后面的达限消息仍被如实计入 exhausted（不再一律算顺延）")
    void alreadyExhaustedTailStillCountedWhenBudgetIsFull() {
        int cap = RedisConfig.DLQ_MAX_AUTO_REPLAYS;
        // 同一条消息（同 external_userid/userid/state → 同 logicalId）反复失败重投，
        // 与 tag 流上「同一客户+同一老师+同一活码」的真实重投形态一致
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("external_userid", "wm-recur");
        fields.put("userid", "user1");
        fields.put("state", "SCH-1");

        for (int i = 1; i <= cap; i++) {
            messageGuardService.sendToDlq(ORIGIN, fields);
            assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed())
                .as("第 %d 次自动重放应成功", i).isEqualTo(1);
        }
        // 第 cap+1 次：越过上限，这条消息从此留在 DLQ
        messageGuardService.sendToDlq(ORIGIN, fields);
        MessageGuardService.ReplaySummary reachedCap =
            messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
        assertThat(reachedCap.exhausted()).as("刚越过上限就应计入 exhausted").isEqualTo(1);

        // 队头达限消息不变，队中塞满 1000 条可重放消息把本轮额度吃干，队尾再投一条同内容消息
        sendDistinctMessagesToDlq(1000, "budget");
        messageGuardService.sendToDlq(ORIGIN, fields);

        MessageGuardService.ReplaySummary pass =
            messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);
        assertThat(pass.replayed()).as("1000 条新消息吃掉整轮额度").isEqualTo(1000);
        assertThat(pass.exhausted())
            .as("达限消息在队头和队尾各有一条，额度用尽也都要数出来")
            .isEqualTo(2);
        assertThat(pass.deferred())
            .as("额度用尽但已达限的消息不算「本轮没轮到」，否则 PatrolWorker 的达限告警会静默")
            .isZero();
    }

    @Test
    @DisplayName("消息离开 DLQ 后其自动重放计数 Key 被回收，同内容新消息重新获得完整配额")
    void replayCounterGcAfterMessageLeavesDlq() {
        sendMessageToDlq();
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed()).isEqualTo(1);
        assertThat(replayCounterKeys()).as("消息刚离开 DLQ 的这一轮，计数 Key 尚在（本轮扫描见过它）")
            .isNotEmpty();

        // 第二轮 DLQ 已空：扫描不到任何消息 → 上一轮的计数 Key 应被回收
        assertThat(messageGuardService.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true).replayed()).isZero();
        assertThat(replayCounterKeys())
            .as("DLQ 里已没有这条消息，残留计数会让同内容的新消息永远拿不到重放额度")
            .isEmpty();
    }

    private Set<String> replayCounterKeys() {
        Set<String> keys = redisTemplate.keys(RedisConfig.DLQ_REPLAY_KEY_PREFIX + "*");
        return keys == null ? Set.of() : keys;
    }

    private void sendDistinctMessagesToDlq(int count, String tag) {
        for (int i = 0; i < count; i++) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("external_userid", "wm-" + tag + "-" + i);
            fields.put("userid", "user1");
            fields.put("state", "SCH-1");
            messageGuardService.sendToDlq(ORIGIN, fields);
        }
    }

    private long exhaustedCount() {
        return ((Number) leakMetrics.snapshot().get("dlq_replay_exhausted")).longValue();
    }
}
