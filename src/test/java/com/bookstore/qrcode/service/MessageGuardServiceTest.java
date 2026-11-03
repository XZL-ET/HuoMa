package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageGuardService 死信重放")
class MessageGuardServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock LeakMetrics leakMetrics;
    @InjectMocks MessageGuardService service;

    @Test
    @DisplayName("读取 DLQ 抛异常 → scanComplete=false，exhausted=0 不代表已无达限消息")
    void shouldMarkScanIncompleteWhenReadFails() {
        when(redisTemplate.opsForStream())
            .thenThrow(new RedisConnectionFailureException("redis down"));

        MessageGuardService.ReplaySummary summary =
            service.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);

        assertThat(summary.scanComplete()).isFalse();
        assertThat(summary.exhausted()).isZero();
    }

    @Test
    @DisplayName("读取完整但逐条处理抛异常 → catch 必须重置 scanComplete=false")
    @SuppressWarnings("unchecked")
    void shouldResetScanCompleteWhenProcessingFails() {
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("external_userid", "wm1"));
        when(redisTemplate.opsForStream()).thenReturn(ops);
        when(ops.read(any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(List.of(record));
        when(ops.add(anyString(), anyMap()))
            .thenThrow(new RedisConnectionFailureException("redis down"));

        MessageGuardService.ReplaySummary summary =
            service.replayAllDlq(RedisConfig.CALLBACK_STREAM_KEY, true);

        // 读取阶段已置 true（1 < limit），处理阶段抛异常后必须回置 false
        assertThat(summary.scanComplete()).isFalse();
    }
}
