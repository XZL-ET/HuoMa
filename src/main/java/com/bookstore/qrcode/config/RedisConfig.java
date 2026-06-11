package com.bookstore.qrcode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Map;

/**
 * Redis 配置类。
 * <p>
 * 负责以下内容：
 * <ul>
 *   <li>定义全局 Redis Key 常量（回调 Stream Key、员工日上限、速率限制、轮换锁等）</li>
 *   <li>注入 {@link StringRedisTemplate}，统一设置 Key / Value / Hash 序列化方式为 UTF-8</li>
 *   <li>应用启动时自动创建 Redis Stream Consumer Group，确保回调消息可被消费</li>
 * </ul>
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
@Configuration
public class RedisConfig {

    // ==================== 回调事件 Stream 相关常量 ====================

    /** Redis Stream Key：企业微信回调事件流，Worker 从此消费回调消息 */
    public static final String CALLBACK_STREAM_KEY = "wecom:callback:stream";
    /** Consumer Group 名称：回调 Worker 消费组 */
    public static final String CALLBACK_CONSUMER_GROUP = "callback-worker-group";
    /** Consumer 名称：当前实例的消费者标识 */
    public static final String CALLBACK_CONSUMER_NAME = "worker-1";
    /** Stream 最大长度（近似），超出后自动删除旧消息，防止内存无限增长 */
    public static final long STREAM_MAXLEN = 10000;

    // ==================== 标签自动打标 Stream 相关常量 ====================

    /** Redis Stream Key：自动打标事件流，TagWorker 从此消费打标事件 */
    public static final String TAG_STREAM_KEY = "wecom:tag:stream";
    /** Consumer Group 名称：标签打标 Worker 消费组 */
    public static final String TAG_CONSUMER_GROUP = "tag-worker-group";
    /** Consumer 名称：当前实例的标签消费者标识 */
    public static final String TAG_CONSUMER_NAME = "tag-worker-1";

    // ==================== 业务 Key 前缀常量 ====================

    /**
     * 员工日添加计数 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>agent:daily:{userid}:{qrCodeId}</code>
     * <br>记录某个员工（userid）在某个活码（qrCodeId）上当天的客户添加次数。
     * 用于实现"每个员工每日添加上限"功能。
     * </p>
     */
    public static final String AGENT_DAILY_KEY_PREFIX = "agent:daily:";

    /**
     * 员工日总添加 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>agent:daily:total:{userid}</code>
     * <br>记录某个员工（userid）当天在所有活码上累计的添加总次数。
     * 用于全局维度的日上限控制，防止跨活码绕过限制。
     * </p>
     */
    public static final String AGENT_DAILY_TOTAL_PREFIX = "agent:daily:total:";

    /**
     * 速率限制滑窗 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>rate:{userid}</code>
     * <br>基于 Redis Sorted Set 实现滑动窗口，用于限制对某个员工的请求频率，
     * 防止短时间内大量请求涌入。
     * </p>
     */
    public static final String RATE_WINDOW_KEY_PREFIX = "rate:";

    /**
     * 员工轮换分布式锁 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>rotate:lock:{qrCodeId}:{userid}</code>
     * <br>在活码员工轮换逻辑中使用分布式锁，
     * 防止并发场景下多次选中同一位员工，保证轮换的原子性。
     * </p>
     */
    public static final String ROTATE_LOCK_PREFIX = "rotate:lock:";

    /**
     * 配置并注入 StringRedisTemplate Bean。
     * <p>
     * 显式设置所有序列化器为 UTF-8 字符串序列化，确保：
     * <ul>
     *   <li>Key 使用 UTF-8 序列化 &mdash; 避免默认 JdkSerialization 导致的二进制乱码</li>
     *   <li>Value 使用 UTF-8 序列化 &mdash; 保证存取的字符串内容一致</li>
     *   <li>Hash Key / Hash Value 同样使用 UTF-8 序列化 &mdash; Hash 结构操作兼容</li>
     * </ul>
     * 统一字符串序列化后，可通过 redis-cli 直接查看和操作数据，方便运维调试。
     * </p>
     *
     * @param factory Redis 连接工厂，由 Spring Boot 自动配置注入
     * @return 配置好的 StringRedisTemplate 实例
     */
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
     * 应用启动时自动初始化 Redis Stream Consumer Group。
     * <p>
     * 首次启动时会创建消费组 {@value #CALLBACK_CONSUMER_GROUP}，
     * 绑定到 Stream {@value #CALLBACK_STREAM_KEY}，从最早消息（0-0）开始消费。
     * 如果消费组已存在（非首次启动），则抛出的异常被捕获并静默忽略，
     * 保证应用重启不会报错。
     * </p>
     *
     * @param redisTemplate 已注入的 StringRedisTemplate
     * @return 消费组名称，供其他组件引用
     */
    @Bean
    public String callbackConsumerGroup(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.opsForStream().createGroup(CALLBACK_STREAM_KEY,
                ReadOffset.from("0-0"), CALLBACK_CONSUMER_GROUP);
        } catch (Exception e) {
            // 消费组已存在时抛出 RedisCommandExecutionException，属于正常情况，忽略即可
        }
        return CALLBACK_CONSUMER_GROUP;
    }

    /**
     * 应用启动时自动创建标签打标 Redis Stream Consumer Group。
     * <p>
     * 首次启动时创建消费组 {@value #TAG_CONSUMER_GROUP}，
     * 绑定到 Stream {@value #TAG_STREAM_KEY}，从最早消息（0-0）开始消费。
     * 若消费组已存在则静默忽略异常，保证重启不报错。
     * </p>
     *
     * @param redisTemplate 已注入的 StringRedisTemplate
     * @return 消费组名称，供 TagWorker 引用
     */
    @Bean
    public String tagConsumerGroup(StringRedisTemplate redisTemplate) {
        try {
            // 先 XADD 一条占位消息确保 Stream 存在（XADD 会自动创建不存在的 Stream），
            // 否则后续 XGROUP CREATE 会因 Stream 不存在而报 NOGROUP 错误
            redisTemplate.opsForStream()
                .add(TAG_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(TAG_STREAM_KEY,
                ReadOffset.from("0-0"), TAG_CONSUMER_GROUP);
            // 用 trim 清理占位消息（XDEL API 存在兼容性问题，改用 trim 近似清理）
            redisTemplate.opsForStream().trim(TAG_STREAM_KEY, 0, true);
        } catch (Exception e) {
            // 消费组已存在时属于正常情况（非首次启动），忽略即可
        }
        return TAG_CONSUMER_GROUP;
    }
}
