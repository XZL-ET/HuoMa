package com.bookstore.qrcode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    public static final String CALLBACK_STREAM_KEY = "wecom:callback:stream";
    public static final String CALLBACK_CONSUMER_GROUP = "callback-worker-group";
    public static final String CALLBACK_CONSUMER_NAME = "worker-1";

    /** 员工日添加计数 key: agent:daily:{userid}:{qrCodeId} */
    public static final String AGENT_DAILY_KEY_PREFIX = "agent:daily:";
    /** 员工日总添加 key: agent:daily:total:{userid} */
    public static final String AGENT_DAILY_TOTAL_PREFIX = "agent:daily:total:";
    /** 速率滑窗 key: rate:{userid} */
    public static final String RATE_WINDOW_KEY_PREFIX = "rate:";
    /** 轮换锁 key: rotate:lock:{qrCodeId}:{userid} */
    public static final String ROTATE_LOCK_PREFIX = "rotate:lock:";

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        return template;
    }

    /**
     * 初始化 Consumer Group（如果不存在则创建）
     */
    @Bean
    public String callbackConsumerGroup(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.opsForStream().createGroup(CALLBACK_STREAM_KEY,
                ReadOffset.from("0-0"), CALLBACK_CONSUMER_GROUP);
        } catch (Exception e) {
            // GROUP 已存在，忽略
        }
        return CALLBACK_CONSUMER_GROUP;
    }
}
