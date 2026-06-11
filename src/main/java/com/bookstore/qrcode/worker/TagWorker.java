package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.TagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis Stream 消费者 —— 异步处理客户自动打标事件。
 *
 * <p><b>工作模式：</b>基于 Redis Stream 的消费者组 (Consumer Group) 模型实现。
 * 在 {@link jakarta.annotation.PostConstruct} 阶段使用 {@code taskExecutor} 线程池
 * 启动一个常驻后台的消费循环 ({@link #consumeLoop()})，持续从
 * {@code wecom:tag:stream} 拉取打标事件并处理。</p>
 *
 * <p><b>事件来源：</b>打标事件由 {@link com.bookstore.qrcode.worker.CallbackWorker CallbackWorker}
 * 在处理完客户入库后通过 XADD 发布到 {@link RedisConfig#TAG_STREAM_KEY}。
 * 本 Worker 独立消费，与回调主链路完全解耦，打标失败不影响客户入库和日计数。</p>
 *
 * <p><b>ACK 机制：</b>每条消息处理完成后（无论成功或失败）都会立即调用
 * {@code acknowledge} 确认消费，避免阻塞 Stream 的 Pending 队列。
 * 处理单条消息的异常被 catch 后不会影响同批次其他消息的消费。</p>
 *
 * <p><b>优雅关闭：</b>通过 volatile {@code running} 标志控制循环退出。
 * 当线程被 {@link InterruptedException} 中断时，退出循环并记录警告日志。</p>
 *
 * <p><b>线程隔离：</b>使用 {@code taskExecutor} 线程池（而非 {@code callbackExecutor}），
 * 避免打标过程中企微 API 调用耗时较长时阻塞回调主消费线程。</p>
 *
 * @author Bookstore Dev
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagWorker {

    private final StringRedisTemplate redisTemplate;
    private final TagService tagService;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;

    private volatile boolean running = true;

    /**
     * 初始化启动方法，在 Spring 依赖注入完成后自动调用。
     *
     * <p>向 {@code taskExecutor}（通用异步线程池）提交消费循环任务，
     * 使打标处理与回调主消费线程解耦。启动时打印 Stream 和消费者组名称以便运维确认。</p>
     */
    @PostConstruct
    public void start() {
        taskExecutor.execute(this::consumeLoop);
        log.info("TagWorker 已启动, Stream={}, Group={}",
            RedisConfig.TAG_STREAM_KEY, RedisConfig.TAG_CONSUMER_GROUP);
    }

    /**
     * Redis Stream 常驻消费循环。
     *
     * <p><b>工作流程：</b>
     * <ol>
     *   <li>使用 {@code XREADGROUP} 以消费者组成员身份从 Stream 读取最多 50 条消息，
     *       阻塞等待最多 5 秒；</li>
     *   <li>如果无消息（超时返回空），短暂休眠 100ms 后继续轮询；</li>
     *   <li>对每条消息，提取 {@code event} 字段 JSON 并调用
     *       {@link #processEvent(String)} 处理；</li>
     *   <li>每条消息处理完成后（无论是否抛出异常）都执行
     *       {@code XACK} 确认，确保消息不会积压在 Pending 列表；</li>
     *   <li>当 {@link #running} 标志为 {@code false} 或线程被中断时退出循环。</li>
     * </ol>
     * </p>
     *
     * <p><b>错误处理：</b>读取 Stream 的网络异常会触发 5 秒休眠后重试；
     * 单条消息的处理异常只影响本条消息，不影响同批次其他消息。</p>
     */
    private void consumeLoop() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        org.springframework.data.redis.connection.stream.Consumer.from(
                            RedisConfig.TAG_CONSUMER_GROUP,
                            RedisConfig.TAG_CONSUMER_NAME),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.TAG_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100); // 无消息时短暂休眠，避免空转
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        Map<Object, Object> value = record.getValue();
                        String eventJson = (String) value.get("event");
                        processEvent(eventJson);
                    } catch (Exception e) {
                        log.error("处理打标事件失败", e);
                    } finally {
                        // ACK 每条消息，确保消息不积压
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TAG_STREAM_KEY,
                            RedisConfig.TAG_CONSUMER_GROUP,
                            record.getId().getValue());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("TagWorker 消费异常, 5s 后重试", e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("TagWorker 已停止");
    }

    /**
     * 解析打标事件并调用 {@link TagService#autoTag} 执行自动打标。
     *
     * <p>事件 JSON 格式（由 {@link com.bookstore.qrcode.worker.CallbackWorker CallbackWorker}
     * 在客户入库后发布）：</p>
     * <pre>
     * {
     *   "external_userid": "wmxxxxxx",
     *   "userid": "zhangsan",
     *   "state": "school_123"
     * }
     * </pre>
     *
     * <p>字段说明：
     * <ul>
     *   <li>{@code external_userid} — 企微客户外部用户ID</li>
     *   <li>{@code userid} — 当前接待员工的企微用户ID（用于企微打标 API 鉴权）</li>
     *   <li>{@code state} — 活码标识（学校ID），用于反查活码和地域标签</li>
     * </ul>
     *
     * @param eventJson 打标事件的 JSON 字符串
     * @throws Exception 当 JSON 解析失败或打标过程发生异常时抛出，
     *                   由调用方 ({@link #consumeLoop()}) 捕获并记录日志
     */
    private void processEvent(String eventJson) throws Exception {
        JsonNode event = objectMapper.readTree(eventJson);
        String externalUserId = event.has("external_userid")
            ? event.get("external_userid").asText() : null;
        String userId = event.has("userid")
            ? event.get("userid").asText() : null;
        String state = event.has("state")
            ? event.get("state").asText() : null;

        if (externalUserId == null || userId == null || state == null) {
            log.warn("打标事件缺少关键字段: external={}, userid={}, state={}",
                externalUserId, userId, state);
            return;
        }

        tagService.autoTag(externalUserId, userId, state);
    }
}
