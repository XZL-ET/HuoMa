package com.bookstore.qrcode.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
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
    /** Consumer 名称：当前实例的消费者标识（单线程模式） */
    public static final String CALLBACK_CONSUMER_NAME = "worker-1";
    /** Stream 最大长度（近似），Tag/DataFill 流用；可通过 app.redis-stream.callback-maxlen 配置 */
    public static long STREAM_MAXLEN = 10000;
    /** Tag Stream 最大长度（高吞吐场景扩容），防止高峰期 trim 丢打标事件；可通过 app.redis-stream.tag-maxlen 配置 */
    public static long TAG_STREAM_MAXLEN = 50000;
    /** DataFill Stream 最大长度，缓冲约 1 小时新客户高峰；可通过 app.redis-stream.datafill-maxlen 配置 */
    public static long DATAFILL_STREAM_MAXLEN = 50000;
    /** Transfer Stream 最大长度（高吞吐场景扩容），防止高峰期 trim 丢继承事件；可通过 app.redis-stream.transfer-maxlen 配置 */
    public static long TRANSFER_STREAM_MAXLEN = 100000;

    @Value("${app.redis-stream.callback-maxlen:10000}")
    public void setStreamMaxlen(long val) { RedisConfig.STREAM_MAXLEN = val; }

    @Value("${app.redis-stream.tag-maxlen:50000}")
    public void setTagStreamMaxlen(long val) { RedisConfig.TAG_STREAM_MAXLEN = val; }

    @Value("${app.redis-stream.datafill-maxlen:50000}")
    public void setDatafillStreamMaxlen(long val) { RedisConfig.DATAFILL_STREAM_MAXLEN = val; }

    @Value("${app.redis-stream.transfer-maxlen:100000}")
    public void setTransferStreamMaxlen(long val) { RedisConfig.TRANSFER_STREAM_MAXLEN = val; }

    // ==================== 死信队列 (DLQ) Stream 相关常量 ====================

    /** Redis Stream Key：死信队列，存放重试耗尽的消息 */
    public static final String DLQ_STREAM_KEY = "wecom:dlq:stream";
    /** Consumer Group 名称：死信队列消费组（重放时使用） */
    public static final String DLQ_CONSUMER_GROUP = "dlq-worker-group";
    /** DLQ Stream 最大长度，防止 OOM（死信通常很少，设保守上限）；可通过 app.redis-stream.dlq-maxlen 配置 */
    public static long DLQ_STREAM_MAXLEN = 10000;

    @Value("${app.redis-stream.dlq-maxlen:10000}")
    public void setDlqStreamMaxlen(long val) { RedisConfig.DLQ_STREAM_MAXLEN = val; }

    // ==================== 消息去重 Key 前缀常量 ====================

    /** 回调去重 Key 前缀。完整 Key: callback:dedup:{msgId}，TTL 300s */
    public static final String CALLBACK_DEDUP_KEY_PREFIX = "callback:dedup:";

    // ==================== 重试计数 Key 前缀常量 ====================

    /** 消息重试计数 Key 前缀。完整 Key: dlq:retry:{streamKey}:{messageId}，TTL 3600s */
    public static final String DLQ_RETRY_KEY_PREFIX = "dlq:retry:";

    // ==================== 标签自动打标 Stream 相关常量 ====================

    /** Redis Stream Key：自动打标事件流，TagWorker 从此消费打标事件 */
    public static final String TAG_STREAM_KEY = "wecom:tag:stream";
    /** Consumer Group 名称：标签打标 Worker 消费组 */
    public static final String TAG_CONSUMER_GROUP = "tag-worker-group";
    /** Consumer 名称：当前实例的标签消费者标识 */
    public static final String TAG_CONSUMER_NAME = "tag-worker-1";

    // ==================== 客户信息补全 Stream 相关常量 ====================

    /** Redis Stream Key：客户信息补全事件流，DataFillWorker 从此消费补全事件 */
    public static final String DATAFILL_STREAM_KEY = "wecom:datafill:stream";
    /** Consumer Group 名称：客户信息补全消费组 */
    public static final String DATAFILL_CONSUMER_GROUP = "datafill-worker-group";

    // ==================== 出站消息 Stream 相关常量 ====================

    /** Redis Stream Key：出站事件流，OutboundWorker 从此消费出站事件 */
    public static final String OUTBOUND_STREAM_KEY = "wecom:outbound:stream";
    /** Consumer Group 名称：出站 Worker 消费组 */
    public static final String OUTBOUND_CONSUMER_GROUP = "outbound-worker-group";

    // ==================== 客户转让 Stream 相关常量 ====================

    /** Redis Stream Key：客户转让事件流，TransferWorker 从此消费转让事件 */
    public static final String TRANSFER_STREAM_KEY = "wecom:transfer:stream";
    /** Consumer Group 名称：转让 Worker 消费组 */
    public static final String TRANSFER_CONSUMER_GROUP = "transfer-worker-group";

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
     * QrAgent 日计数 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>agent:daily:qa:{qrAgentId}</code>
     * <br>以 QrAgent 主键 ID 为粒度，记录单个员工在单个活码上的今日添加数。
     * 用于阈值检查与自动轮换。
     * </p>
     */
    public static final String DAILY_COUNT_KEY_PREFIX = "agent:daily:qa:";

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
     * 完整 Key 格式：<code>rotate:lock:{qrCodeId}:rotate</code>
     * <br>在活码员工轮换逻辑（扩容/预激活）中使用分布式锁，
     * 防止并发场景下重复添加同一位员工，保证轮换的原子性。
     * </p>
     */
    public static final String ROTATE_LOCK_PREFIX = "rotate:lock:";

    /**
     * 预激活去重 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>preactivate:done:{qrCodeId}</code>，TTL 到当日午夜。
     * <br>防止 {@code preActivateBackup} 在同一活码上被反复触发，
     * 导致接待员无限堆积。每天每个活码最多触发一次预激活。
     * </p>
     */
    public static final String PREACTIVATE_DONE_PREFIX = "preactivate:done:";

    /**
     * 学校访问限流 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>school_rate:{ip}</code>
     * <br>基于 Redis Sorted Set 实现滑动窗口，用于限制学校自助查询页面的访问频率。
     * </p>
     */
    public static final String SCHOOL_RATE_KEY_PREFIX = "school_rate:";

    /**
     * 累计型异常告警计数 Key 前缀。
     * <p>
     * 完整 Key 格式：<code>alert:count:{alertType}:{userid}</code>
     * <br>基于 Redis Sorted Set 实现 1 小时滑动窗口计数，用于 {@code add_fail}
     * 等累计型异常达到阈值后的告警与自动暂停判定。
     * </p>
     */
    public static final String ALERT_COUNT_KEY_PREFIX = "alert:count:";

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
    @Primary
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
    public String callbackConsumerGroup(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        try {
            // 先 XADD 占位消息确保 Stream 存在（XADD 自动创建 Stream），
            // 否则 Redis < 7.0 时 XGROUP CREATE 对不存在的 key 返回 ERR
            // 使用 XDEL 精确删除占位消息，避免 trim(0) 误删已存在的合法消息
            RecordId initId = redisTemplate.opsForStream()
                .add(CALLBACK_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(CALLBACK_STREAM_KEY,
                ReadOffset.from("0-0"), CALLBACK_CONSUMER_GROUP);
            redisTemplate.opsForStream().delete(CALLBACK_STREAM_KEY, initId);
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
    public String tagConsumerGroup(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        try {
            // 先 XADD 一条占位消息确保 Stream 存在（XADD 会自动创建不存在的 Stream），
            // 否则后续 XGROUP CREATE 会因 Stream 不存在而报 NOGROUP 错误。
            // 使用 XDEL 精确删除占位消息，避免 trim(0) 误删已存在的合法消息。
            RecordId initId = redisTemplate.opsForStream()
                .add(TAG_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(TAG_STREAM_KEY,
                ReadOffset.from("0-0"), TAG_CONSUMER_GROUP);
            redisTemplate.opsForStream().delete(TAG_STREAM_KEY, initId);
        } catch (Exception e) {
            // 消费组已存在时属于正常情况（非首次启动），忽略即可
        }
        return TAG_CONSUMER_GROUP;
    }

    /**
     * 应用启动时自动创建客户信息补全 Redis Stream Consumer Group。
     */
    @Bean
    public String datafillConsumerGroup(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        try {
            RecordId initId = redisTemplate.opsForStream()
                .add(DATAFILL_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(DATAFILL_STREAM_KEY,
                ReadOffset.from("0-0"), DATAFILL_CONSUMER_GROUP);
            redisTemplate.opsForStream().delete(DATAFILL_STREAM_KEY, initId);
        } catch (Exception e) {
            // 消费组已存在，忽略
        }
        return DATAFILL_CONSUMER_GROUP;
    }

    /**
     * 应用启动时自动创建出站消息 Redis Stream Consumer Group。
     * <p>
     * 首次启动时创建消费组 {@value #OUTBOUND_CONSUMER_GROUP}，
     * 绑定到 Stream {@value #OUTBOUND_STREAM_KEY}，从最早消息（0-0）开始消费。
     * 若消费组已存在则静默忽略异常，保证重启不报错。
     * </p>
     *
     * @param redisTemplate 已注入的 StringRedisTemplate
     * @return 消费组名称，供 OutboundWorker 引用
     */
    @Bean
    public String outboundConsumerGroup(
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        try {
            RecordId initId = redisTemplate.opsForStream()
                .add(OUTBOUND_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(OUTBOUND_STREAM_KEY,
                ReadOffset.from("0-0"), OUTBOUND_CONSUMER_GROUP);
            redisTemplate.opsForStream().delete(OUTBOUND_STREAM_KEY, initId);
        } catch (Exception e) {
            // 消费组已存在，忽略
        }
        return OUTBOUND_CONSUMER_GROUP;
    }

    /**
     * 应用启动时自动创建客户转让 Redis Stream Consumer Group。
     * <p>
     * 首次启动时创建消费组 {@value #TRANSFER_CONSUMER_GROUP}，
     * 绑定到 Stream {@value #TRANSFER_STREAM_KEY}，从最早消息（0-0）开始消费。
     * 若消费组已存在则静默忽略异常，保证重启不报错。
     * </p>
     *
     * @param redisTemplate 已注入的 StringRedisTemplate
     * @return 消费组名称，供 TransferWorker 引用
     */
    @Bean
    public String transferConsumerGroup(
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        try {
            RecordId initId = redisTemplate.opsForStream()
                .add(TRANSFER_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(TRANSFER_STREAM_KEY,
                ReadOffset.from("0-0"), TRANSFER_CONSUMER_GROUP);
            redisTemplate.opsForStream().delete(TRANSFER_STREAM_KEY, initId);
        } catch (Exception e) { /* exists */ }
        return TRANSFER_CONSUMER_GROUP;
    }

    // ==================== Lettuce TCP Keepalive ====================

    /**
     * 启用 Lettuce TCP Keepalive，防止长时间阻塞读取（如 XREADGROUP BLOCK）
     * 期间连接被网络中间设备或 Redis 服务器因空闲超时而关闭。
     *
     * <p><b>背景：</b>Worker 线程使用 {@code XREADGROUP BLOCK} 阻塞等待 Redis Stream
     * 消息，阻塞时长 5-10 秒。在此阻塞期间 TCP 连接无数据交互，
     * 云负载均衡器（如阿里云 SLB）、防火墙或 Redis 自身的 {@code timeout} 配置
     * 可能将空闲连接关闭，导致所有 Worker 同时抛出 {@code Connection closed} 异常。</p>
     *
     * <p><b>参数说明：</b>
     * <ul>
     *   <li>{@code idle=5min} — 空闲 5 分钟后开始 keepalive 探测，
     *       早于多数云 SLB 的空闲超时（通常 15min+）</li>
     *   <li>{@code interval=75s} — 探测间隔</li>
     *   <li>{@code count=3} — 连续 3 次无响应判定连接死亡</li>
     * </ul>
     *
     * <p><b>兼容性：</b>自定义时序参数依赖 Netty epoll（Linux 默认启用），
     * 在非 Linux 平台（如 Windows 开发环境）退化为普通 SO_KEEPALIVE，
     * 使用 OS 默认时序。生产环境为阿里云 ECS（Linux），完全有效。</p>
     *
     * @return Lettuce 客户端配置定制器
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceKeepAliveCustomizer() {
        return builder -> builder.clientOptions(
            ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                    .keepAlive(SocketOptions.KeepAliveOptions.builder()
                        .enable()
                        .idle(Duration.ofMinutes(5))
                        .interval(Duration.ofSeconds(75))
                        .count(3)
                        .build())
                    .build())
                .build()
        );
    }

    // ==================== 限流 RedisTemplate ====================

    /**
     * 限流专用 StringRedisTemplate Bean，使用 200ms 短超时防止阻塞。
     * <p>
     * 基于主 Redis 连接工厂的 Standalone 配置构建独立连接工厂，
     * 命令超时仅 200ms，确保限流检查在 Redis 故障时快速失败并降级到本地计数。
     * </p>
     *
     * @param factory 主 Lettuce 连接工厂，由 Spring Boot 自动配置注入
     * @return 限流专用的 StringRedisTemplate 实例
     */
    @Bean
    @Qualifier("rateLimitRedisTemplate")
    public StringRedisTemplate rateLimitRedisTemplate(
            LettuceConnectionFactory factory) {
        LettuceClientConfiguration config = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        LettuceConnectionFactory shortTimeoutFactory =
                new LettuceConnectionFactory(
                        factory.getStandaloneConfiguration(), config);
        shortTimeoutFactory.afterPropertiesSet();
        return new StringRedisTemplate(shortTimeoutFactory);
    }

    // ==================== 分布式锁 Lua 脚本 ====================

    /** 安全释放分布式锁 Lua 脚本：原子化 GET + COMPARE + DEL */
    private static final String SAFE_UNLOCK_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then "
        + "return redis.call('DEL', KEYS[1]) "
        + "else return 0 end";

    public static final DefaultRedisScript<Long> SAFE_UNLOCK_SCRIPT;

    static {
        SAFE_UNLOCK_SCRIPT = new DefaultRedisScript<>();
        SAFE_UNLOCK_SCRIPT.setScriptText(SAFE_UNLOCK_LUA);
        SAFE_UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 为每个消费线程生成唯一的消费者名称，确保 Redis Stream Consumer Group
     * 正确分发消息到不同线程。
     *
     * @param prefix   消费者前缀，如 "callback-worker"
     * @param threadId 线程序号，从 1 开始
     * @return 唯一消费者名称，如 "callback-worker-1"
     */
    public static String consumerName(String prefix, int threadId) {
        return prefix + "-" + threadId;
    }
}
