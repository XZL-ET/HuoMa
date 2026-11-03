package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
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
}
