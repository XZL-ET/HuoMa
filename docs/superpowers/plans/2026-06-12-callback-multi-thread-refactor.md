# 活码回调消费链路多线程重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 CallbackWorker 从单线程改为 4 线程并发消费，同时修复并发竞态、补齐保护机制、消除瓶颈，整体吞吐从 ~5 条/秒提升至 ~200 条/秒。

**Architecture:** 四阶段递进——Phase 1 补齐安全基础（连接池、PEL恢复、42001重试、原子化频控、Stream限长），Phase 2 开启多线程消费 + 并发保护（消费者命名、upsert 竞态修复、TAG_STREAM 原子写入），Phase 3 移除瓶颈（客户 API 调用移出主链路、独立 DataFillWorker、taskExecutor 扩容），Phase 4 监控兜底（池枯竭预警、健康检查端点）。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data JPA, Redis Stream (Consumer Group), Redis Lua, HikariCP, Undertow

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/resources/application.yml` | 修改 | HikariCP 连接池 + taskExecutor 队列配置 |
| `src/main/resources/schema.sql` | 不变 | customer.external_userid 已有 UNIQUE 约束 |
| `src/main/java/com/bookstore/qrcode/config/RedisConfig.java` | 修改 | 动态消费者名、DataFill Stream 常量、Stream 健康检查 |
| `src/main/java/com/bookstore/qrcode/config/AsyncConfig.java` | 修改 | taskExecutor 队列扩容 + 拒绝策略 |
| `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java` | 修改 | 42001 自动重试、access_token double-check |
| `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java` | 修改 | 多线程消费、PEL 恢复、CALLBACK_STREAM trim、新客户事件发布 |
| `src/main/java/com/bookstore/qrcode/worker/TagWorker.java` | 修改 | TAG_STREAM XADD 原子化 |
| `src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java` | **新建** | 异步补全客户信息 |
| `src/main/java/com/bookstore/qrcode/service/CustomerService.java` | 修改 | upsert 并发安全、新客户不调 API |
| `src/main/java/com/bookstore/qrcode/service/RateLimiterService.java` | 修改 | Lua 脚本原子化、移除 sleep |
| `src/main/java/com/bookstore/qrcode/service/AgentBindService.java` | 不变 | 分布式锁已保护轮换 |
| `src/main/java/com/bookstore/qrcode/controller/HealthController.java` | **新建** | Stream 深度 / PEL / 池余量检查 |

---

## Phase 1：安全基础

### Task 1: HikariCP 连接池显式配置

**原因：** 默认 maximumPoolSize=10，4 线程消费者 + TagWorker + 定时任务 + 管理后台容易耗尽。显式配置消除不确定性。

**Files:**
- Modify: `src/main/resources/application.yml` (dev 和 prod 两个 profile)

- [ ] **Step 1: 在 application.yml dev profile 添加 HikariCP 配置**

在 `spring.datasource` 块下（第 31 行 `password` 之后）追加：

```yaml
      hikari:
        maximum-pool-size: 20         # 连接池上限（4消费线程 + TagWorker + 定时任务 + 后台查询）
        minimum-idle: 4               # 最少空闲连接
        connection-timeout: 5000      # 获取连接超时 5s
        idle-timeout: 600000          # 空闲连接回收 10min
        max-lifetime: 1800000         # 连接最大存活 30min
```

- [ ] **Step 2: 在 prod profile 同样添加**

在 prod profile 的 `spring.datasource` 块下追加相同配置。

- [ ] **Step 3: 验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -i "HikariPool\|maximumPoolSize"
```

确认日志输出连接池大小为 20。

---

### Task 2: 动态消费者名称 + Stream 健康常量

**原因：** 当前 `CALLBACK_CONSUMER_NAME = "worker-1"` 硬编码，多线程必须各有唯一名称。同时为后续 DataFill Stream 和健康检查预留常量。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java:33-47`

- [ ] **Step 1: 替换消费者名称常量为动态生成方法**

删除第 33-37 行的硬编码常量：

```java
// 删除以下 5 行：
// /** Consumer 名称：当前实例的消费者标识 */
// public static final String CALLBACK_CONSUMER_NAME = "worker-1";
// /** Stream 最大长度（近似）... */
// public static final long STREAM_MAXLEN = 10000;
```

在类末尾（第 164 行 `}` 之前）添加：

```java
    // ==================== DataFill Stream 常量 ====================

    /** Redis Stream Key：客户信息补全事件流，DataFillWorker 从此消费 */
    public static final String DATAFILL_STREAM_KEY = "wecom:datafill:stream";
    /** Consumer Group 名称：客户信息补全消费组 */
    public static final String DATAFILL_CONSUMER_GROUP = "datafill-worker-group";

    /** Stream 最大长度（近似），超出后自动删除旧消息 */
    public static final long STREAM_MAXLEN = 10000;

    /**
     * 为每个消费线程生成唯一的消费者名称，确保 Redis Stream Consumer Group
     * 正确分发消息到不同线程。
     *
     * @param prefix  消费者前缀，如 "callback-worker"
     * @param threadId 线程序号，从 1 开始
     * @return 唯一消费者名称，如 "callback-worker-1"
     */
    public static String consumerName(String prefix, int threadId) {
        return prefix + "-" + threadId;
    }
```

- [ ] **Step 2: 在 `callbackConsumerGroup` Bean 中初始化 DataFill Stream Consumer Group**

在现有的 `tagConsumerGroup` Bean 之后（第 165 行之后）添加：

```java
    @Bean
    public String datafillConsumerGroup(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.opsForStream()
                .add(DATAFILL_STREAM_KEY, Map.of("_init", "1"));
            redisTemplate.opsForStream().createGroup(DATAFILL_STREAM_KEY,
                ReadOffset.from("0-0"), DATAFILL_CONSUMER_GROUP);
            redisTemplate.opsForStream().trim(DATAFILL_STREAM_KEY, 0, true);
        } catch (Exception e) {
            // 消费组已存在，忽略
        }
        return DATAFILL_CONSUMER_GROUP;
    }
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 3: PEL 僵死消息自动恢复

**原因：** 线程崩溃（OOM / kill -9）时，已分发但未 ACK 的消息卡在 Pending Entries List 永远不被处理。多线程后概率放大 4 倍。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:100-143` (consumeLoop 开头新增 XAUTOCLAIM 逻辑)

- [ ] **Step 1: 在 consumeLoop 开头添加 PEL 恢复逻辑**

在 `consumeLoop()` 方法的 `while (running)` 循环开头，`XREADGROUP` 之前插入：

```java
    private void consumeLoop() {
        while (running) {
            try {
                // ===== PEL 僵死消息恢复：认领超过 30 秒未 ACK 的消息 =====
                // 场景：上一轮消费线程崩溃导致消息卡在 Pending 列表
                recoverStalePending();

                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    // ... 原有 XREADGROUP 代码不变
```

- [ ] **Step 2: 添加 recoverStalePending 方法**

在 `CallbackWorker` 类末尾（第 353 行 `}` 之前）添加：

```java
    /**
     * 认领 Pending 列表中超过 30 秒未 ACK 的僵死消息，重新投递给自己处理。
     *
     * <p>使用 XAUTOCLAIM 命令自动完成「查找僵死消息 → 变更 owner → 返回消息」三步。
     * 只在 PEL 中有消息时才执行，避免空转消耗。</p>
     *
     * <p>XAUTOCLAIM 参数说明：
     * <ul>
     *   <li>min-idle-time = 30_000ms — 消息未被 ACK 超过 30 秒才认领</li>
     *   <li>count = 10 — 每次最多认领 10 条，防止一次性全部拿回导致雪崩</li>
     * </ul>
     */
    private void recoverStalePending() {
        try {
            // 只在有 Pending 消息时才执行
            PendingMessagesSummary pending = redisTemplate.opsForStream()
                .pending(RedisConfig.CALLBACK_STREAM_KEY,
                         RedisConfig.CALLBACK_CONSUMER_GROUP);
            if (pending == null || pending.getTotalPendingMessages() == 0) {
                return;
            }

            // XAUTOCLAIM: 自动认领并转移所有权
            var claimed = redisTemplate.opsForStream()
                .claim(RedisConfig.CALLBACK_STREAM_KEY,
                       RedisConfig.CALLBACK_CONSUMER_GROUP,
                       RedisConfig.CALLBACK_CONSUMER_NAME,  // 当前实例名
                       java.time.Duration.ofSeconds(30),     // min-idle-time
                       org.springframework.data.domain.Range.unbounded(),
                       10);  // 每次最多 10 条

            if (claimed != null && !claimed.isEmpty()) {
                log.warn("XAUTOCLAIM 认领 {} 条僵死消息", claimed.size());
                for (MapRecord<String, Object, Object> record : claimed) {
                    try {
                        Map<Object, Object> value = record.getValue();
                        String eventJson = (String) value.get("event");
                        processEvent(eventJson);
                    } catch (Exception e) {
                        log.error("处理认领的僵死消息失败", e);
                    } finally {
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.CALLBACK_STREAM_KEY,
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            record.getId().getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PEL 恢复检查跳过（Stream 可能为空）: {}", e.getMessage());
        }
    }
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 4: WecomApiClient 42001 自动重试 + 避免双重刷新

**原因：** Token 刷新瞬间旧 token 立即失效，其他线程正在执行的 API 调用返回 42001 后静默丢失。同时需要防止两个线程同时刷新 token 导致第一个 token 立即失效。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java:79-109` (getAccessToken)
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java:573-587` (parseOrThrow)

- [ ] **Step 1: access_token 改为 double-check 模式**

替换 `getAccessToken()` 方法（第 79-109 行）：

```java
    private final Object tokenLock = new Object();

    /**
     * 获取 access_token（double-check 刷新，线程安全）。
     *
     * <p>使用 synchronized + double-check 避免多个线程同时触发 token 刷新。
     * 企微机制：同 corpSecret 新 token 会使旧 token 立即失效，所以必须确保
     * 同一时刻只有一个线程在执行刷新。</p>
     */
    public String getAccessToken() {
        // 快速路径：token 有效，无锁读取
        if (config.getAccessToken() != null
                && Instant.now().getEpochSecond() < config.getAccessTokenExpireAt()) {
            return config.getAccessToken();
        }

        // 慢路径：需要刷新，拿锁
        synchronized (tokenLock) {
            // Double-check：可能另一个线程已经刷新过了
            if (config.getAccessToken() != null
                    && Instant.now().getEpochSecond() < config.getAccessTokenExpireAt()) {
                return config.getAccessToken();
            }

            try {
                String url = String.format(TOKEN_URL, config.getCorpId(), config.getCorpSecret());
                String resp = restTemplate.getForObject(url, String.class);
                JsonNode node = objectMapper.readTree(resp);

                int errcode = node.get("errcode").asInt();
                if (errcode != 0) {
                    String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                    throw new RuntimeException("获取 access_token 失败: errcode=" + errcode + " " + errmsg);
                }

                String token = node.get("access_token").asText();
                long expiresIn = node.get("expires_in").asLong();

                config.setAccessToken(token);
                config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + expiresIn - 200);
                log.info("access_token 已刷新，过期时间: {}", config.getAccessTokenExpireAt());
                return token;
            } catch (Exception e) {
                log.error("获取 access_token 异常", e);
                throw new RuntimeException("获取 access_token 失败: " + e.getMessage(), e);
            }
        }
    }
```

- [ ] **Step 2: 替换 parseOrThrow 为带 42001 自动重试的版本**

注意：由于所有 API 方法的 token 拼接逻辑都在调用处，需要在调用链加一层重试。最简方式：给 `WecomApiClient` 加一个内部重试包装方法。

在类末尾 `}` 之前添加：

```java
    /**
     * 执行带 42001 自动重试的 API 调用。
     *
     * <p>当企微返回 42001（access_token 无效）时，强制刷新 token 并重试一次。
     * 场景：token 在 A 线程刷新后，B 线程持有的旧 token 立即失效。</p>
     *
     * @param action  操作名称（日志用）
     * @param callable API 调用逻辑（返回 JSON 响应字符串）
     * @return 解析后的 JsonNode
     */
    private JsonNode executeWithTokenRetry(String action,
                                           java.util.function.Supplier<String> callable) {
        String resp = callable.get();
        try {
            JsonNode node = objectMapper.readTree(resp);
            int code = node.has("errcode") ? node.get("errcode").asInt() : -1;
            if (code == 42001) {
                // Token 无效，强制过期缓存并刷新
                log.warn("{} 遇到 42001，刷新 token 后重试", action);
                synchronized (tokenLock) {
                    config.setAccessTokenExpireAt(0);  // 强制过期
                }
                getAccessToken();  // 触发刷新
                resp = callable.get();  // 重试
                node = objectMapper.readTree(resp);
                code = node.has("errcode") ? node.get("errcode").asInt() : -1;
            }
            if (code != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                log.error("{} 失败: errcode={} errmsg={}", action, code, errmsg);
            }
            return node;
        } catch (Exception e) {
            log.error("{} 解析响应异常: {}", action, resp, e);
            throw new RuntimeException(action + " 失败: " + resp, e);
        }
    }
```

- [ ] **Step 3: 改造 `getExternalContact` 方法使用重试包装**

替换 `getExternalContact` 方法（第 441-446 行）：

```java
    public JsonNode getExternalContact(String externalUserid) {
        return executeWithTokenRetry("获取客户详情", () -> {
            String url = BASE_URL + "/externalcontact/get?access_token=" + getAccessToken()
                         + "&external_userid=" + externalUserid;
            return restTemplate.getForObject(url, String.class);
        });
    }
```

- [ ] **Step 4: 改造 `updateContactWay` 方法使用重试包装**

替换 `updateContactWay` 方法（第 159-163 行）：

```java
    public JsonNode updateContactWay(String requestJson) {
        return executeWithTokenRetry("更新活码", () -> {
            String url = BASE_URL + "/externalcontact/update_contact_way?access_token="
                         + getAccessToken();
            return restTemplate.postForObject(url, requestJson, String.class);
        });
    }
```

- [ ] **Step 5: 改造 `markTag` 方法使用重试包装**

替换 `markTag` 方法（第 314-326 行）：

```java
    public void markTag(String externalUserId, String userId, List<String> tagIds) {
        executeWithTokenRetry("打标签", () -> {
            try {
                String url = BASE_URL + "/externalcontact/mark_tag?access_token="
                             + getAccessToken();
                String tagIdsJson = objectMapper.writeValueAsString(tagIds);
                String body = String.format(
                    "{\"userid\":\"%s\",\"external_userid\":\"%s\",\"add_tag\":%s}",
                    userId, externalUserId, tagIdsJson);
                return restTemplate.postForObject(url, body, String.class);
            } catch (Exception e) {
                throw new RuntimeException("打标签失败: " + e.getMessage(), e);
            }
        });
    }
```

- [ ] **Step 6: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 5: RateLimiterService Lua 脚本原子化 + 移除 sleep

**原因：** ZADD + ZREMRANGE + ZCARD 三命令非原子，多线程时计数漏判。Thread.sleep(100) 阻塞消费线程。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/RateLimiterService.java:65-109`

- [ ] **Step 1: 替换 recordAdd 为 Lua 原子版本**

替换整个 `recordAdd` 方法（第 65-109 行）：

```java
    /** 原子化滑动窗口 Lua 脚本 */
    private static final String RATE_CHECK_LUA =
        "local key = KEYS[1]\n"
        + "local now = tonumber(ARGV[1])\n"
        + "local window = tonumber(ARGV[2])\n"
        + "local member = ARGV[3]\n"
        + "local maxCount = tonumber(ARGV[4])\n"
        + "local ttl = tonumber(ARGV[5])\n"
        + "redis.call('ZADD', key, now, member)\n"
        + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
        + "redis.call('EXPIRE', key, ttl)\n"
        + "local count = redis.call('ZCARD', key)\n"
        + "return count";

    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> rateCheckScript;

    {
        rateCheckScript = new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        rateCheckScript.setScriptText(RATE_CHECK_LUA);
        rateCheckScript.setResultType(Long.class);
    }

    /**
     * 记录员工一次好友添加操作，原子化检测速率。
     *
     * <p>使用 Redis Lua 脚本确保 ZADD + ZREMRANGE + ZCARD 原子执行，
     * 多线程并发时计数准确。</p>
     */
    public void recordAdd(String userId) {
        long now = Instant.now().getEpochSecond();
        String member = now + ":" + System.nanoTime();

        // 15 秒窗口原子检查
        Long count15s = redisTemplate.execute(
            rateCheckScript,
            java.util.List.of(RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":15s"),
            String.valueOf(now), "15", member, String.valueOf(WINDOW_15S_MAX), "30");

        // 60 秒窗口原子检查
        Long count60s = redisTemplate.execute(
            rateCheckScript,
            java.util.List.of(RedisConfig.RATE_WINDOW_KEY_PREFIX + userId + ":60s"),
            String.valueOf(now), "60", member, String.valueOf(WINDOW_60S_MAX), "120");

        int c60 = count60s != null ? count60s.intValue() : 0;
        int c15 = count15s != null ? count15s.intValue() : 0;

        if (c60 > WINDOW_60S_MAX) {
            log.error("员工 {} 1分钟内添加 {} 人，触发熔断！", userId, c60);
            alertService.meltAgent(userId, null,
                String.format("1分钟内添加 %d 人，超过阈值 %d", c60, WINDOW_60S_MAX));
        } else if (c15 > WINDOW_15S_MAX) {
            // 不再阻塞线程，仅记录告警。降速由频控熔断层的 60s 窗口兜底
            log.warn("员工 {} 15秒内添加 {} 人（阈值 {}），建议关注",
                userId, c15, WINDOW_15S_MAX);
        }
    }
```

- [ ] **Step 2: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 6: CALLBACK_STREAM trim 在消费后执行

**原因：** CALLBACK_STREAM 无长度限制，多线程后消息产出速率 ×4，需消费后裁剪防 OOM。不在 Controller 做 trim 因为 trim 不是免费的（约 1ms），且可能误删未消费消息。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:118-132` (在 ACK 循环后加 trim)

- [ ] **Step 1: 在每批消息处理完成后加 trim**

在 `consumeLoop` 的 `for` 循环结束之后、`catch` 之前（原第 132 行位置），插入 trim：

```java
                }  // for 循环结束

                // 每批消费完成后裁剪 Stream，防止无限增长
                // trim 在 ACK 后进行，只删已消费的消息
                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.CALLBACK_STREAM_KEY,
                        RedisConfig.STREAM_MAXLEN, true);
                } catch (Exception e) {
                    log.debug("CALLBACK_STREAM trim 跳过: {}", e.getMessage());
                }

            } catch (InterruptedException e) {
```

- [ ] **Step 2: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

## Phase 2：多线程核心

### Task 7: CallbackWorker 4 线程并行消费

**原因：** 单线程是吞吐瓶颈，Redis Stream Consumer Group 原生支持多消费者并行。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:56-79` (start 方法)

- [ ] **Step 1: 修改 start 方法启动 4 个消费线程**

替换 `start()` 方法和相关字段：

```java
    private volatile boolean running = true;
    private static final int CONSUMER_THREADS = 4;
    private static final String CONSUMER_PREFIX = "callback-worker";

    /**
     * 启动 4 个并行消费线程，每个线程作为独立的 Consumer Group 成员。
     * Redis Stream 自动将消息分发到不同消费者，无需额外分片逻辑。
     */
    @PostConstruct
    public void start() {
        for (int i = 1; i <= CONSUMER_THREADS; i++) {
            final int threadId = i;
            final String consumerName = RedisConfig.consumerName(CONSUMER_PREFIX, threadId);
            callbackExecutor.execute(() -> consumeLoop(consumerName, threadId));
        }
        log.info("CallbackWorker 已启动 {} 个消费线程, Stream={}, Group={}",
            CONSUMER_THREADS, RedisConfig.CALLBACK_STREAM_KEY,
            RedisConfig.CALLBACK_CONSUMER_GROUP);
    }
```

- [ ] **Step 2: 修改 consumeLoop 接收消费者名称参数**

将 `private void consumeLoop()` 改为：

```java
    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                recoverStalePending();

                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        org.springframework.data.redis.connection.stream.Consumer.from(
                            RedisConfig.CALLBACK_CONSUMER_GROUP,
                            consumerName),  // 使用线程独立的消费者名称
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.CALLBACK_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                // ... 后续代码不变

                log.debug("Consumer-{} 本批处理 {} 条", threadId, records.size());
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 8: CustomerService upsert 并发安全

**原因：** 4 线程可能同时处理同一 externalUserId 的两次回调，DB 虽已有 UNIQUE 约束，但需要优雅处理竞态而非抛异常。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerService.java:62-139`

- [ ] **Step 1: 用 Redis 轻量锁保护同客户并发 upsert**

替换 `upsertFromCallback` 方法的新客户创建部分（第 101-138 行）。完整替换整个方法：

```java
    @Transactional
    public Long upsertFromCallback(String externalUserId, String userId, String state) {
        // 先查已有客户（老客户走快速路径）
        Customer existing = customerRepo.findByExternalUserid(externalUserId).orElse(null);

        // state 解析活码
        Long qrCodeId = null;
        String schoolId = null;
        if (state != null && !state.isBlank()) {
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr != null) {
                qrCodeId = qr.getId();
                schoolId = state;
            } else {
                log.warn("回调 state 未匹配到活码 school_id: state={}, external={}",
                    state, externalUserId);
            }
        }

        if (existing != null) {
            // 老客户：快速更新
            existing.setCurrentAgent(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            if (qrCodeId != null) {
                existing.setSourceQrId(qrCodeId);
                existing.setSchoolId(schoolId);
            }
            if (existing.getStatus() == Customer.CustomerStatus.deleted) {
                existing.setStatus(Customer.CustomerStatus.active);
                log.info("重新激活已删除客户: external={}", externalUserId);
            }
            customerRepo.save(existing);
            return existing.getId();
        }

        // ===== 新客户：轻量锁防并发重复插入 =====
        String lockKey = "customer:lock:" + externalUserId;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) {
            // 另一个线程正在创建同一客户，短暂等待后查库
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            existing = customerRepo.findByExternalUserid(externalUserId).orElse(null);
            if (existing != null) {
                // 另一个线程已创建，更新字段
                existing.setCurrentAgent(userId);
                existing.setUpdatedAt(LocalDateTime.now());
                if (qrCodeId != null) {
                    existing.setSourceQrId(qrCodeId);
                    existing.setSchoolId(schoolId);
                }
                customerRepo.save(existing);
                return existing.getId();
            }
            // 锁被占用但客户仍未创建（异常情况），继续往下走
        }

        try {
            // 不再调企微 API，直接写入稀疏记录，由 DataFillWorker 异步补全
            Customer customer = Customer.builder()
                .externalUserid(externalUserId)
                .name("未知")        // 占位，DataFillWorker 补全
                .type(1)
                .addedAgent(userId)
                .currentAgent(userId)
                .sourceQrId(qrCodeId)
                .schoolId(schoolId)
                .status(Customer.CustomerStatus.active)
                .addTime(LocalDateTime.now())
                .build();
            customer = customerRepo.save(customer);

            // 发布数据补全事件（仅新客户）
            try {
                Map<String, Object> fillEvent = new java.util.LinkedHashMap<>();
                fillEvent.put("external_userid", externalUserId);
                fillEvent.put("customer_id", customer.getId());
                redisTemplate.opsForStream().add(
                    RedisConfig.DATAFILL_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(fillEvent)));
            } catch (Exception e) {
                log.warn("发布数据补全事件失败: external={}", externalUserId, e);
            }

            return customer.getId();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 终极兜底：Redis 锁未拦住，DB UNIQUE 约束触发
            log.warn("客户并发插入冲突，回退查库: external={}", externalUserId);
            existing = customerRepo.findByExternalUserid(externalUserId).orElseThrow();
            existing.setCurrentAgent(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            customerRepo.save(existing);
            return existing.getId();
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                redisTemplate.delete(lockKey);
            }
        }
    }
```

- [ ] **Step 2: 注入缺少的依赖**

在 CustomerService 的字段声明区添加：

```java
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
```

替换构造器注入（Lombok `@RequiredArgsConstructor` 会自动处理，但要确认 `StringRedisTemplate` 和 `ObjectMapper` 在 Spring 容器中可用——它们已经分别由 `RedisConfig` 和 Spring Boot 自动配置注入）。

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 9: TAG_STREAM XADD + trim 原子化

**原因：** 4 个消费线程各自 XADD + trim 到 TAG_STREAM，trim 可能误删另一个线程刚写入的消息。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:277-282` (handleAddSuccess 的 step ③)

- [ ] **Step 1: 移除 CallbackWorker 中的 TAG_STREAM trim**

在 `handleAddSuccess` 方法中，删除 trim 行（第 281-282 行），只保留 XADD：

```java
            // ③ 发布自动打标事件 → TagWorker 异步消费
            if (state != null) {
                try {
                    Map<String, Object> tagEvent = new java.util.LinkedHashMap<>();
                    tagEvent.put("external_userid", externalUserId);
                    tagEvent.put("userid", userId);
                    tagEvent.put("state", state);
                    redisTemplate.opsForStream().add(
                        RedisConfig.TAG_STREAM_KEY,
                        Map.of("event", objectMapper.writeValueAsString(tagEvent)));
                    // trim 移至 TagWorker 消费后统一执行，避免多线程竞态
                } catch (Exception e) {
                    log.error("发布打标事件失败: external={}", externalUserId, e);
                }
            }
```

- [ ] **Step 2: 在 TagWorker 的消费循环中加 trim**

在 `TagWorker.consumeLoop()` 的 for 循环结束后（第 121 行 `}` 之后），加 trim：

```java
                }  // for 循环结束

                // 统一裁剪：只在消费线程做 trim，避免生产者多线程竞态
                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.TAG_STREAM_KEY,
                        RedisConfig.STREAM_MAXLEN, true);
                } catch (Exception e) {
                    log.debug("TAG_STREAM trim 跳过: {}", e.getMessage());
                }

            } catch (InterruptedException e) {
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

## Phase 3：瓶颈移除

### Task 10: handleAddSuccess 调整步骤顺序 + 新客户事件分离

**原因：** 新客户不再调企微 API（已在 Task 8 实现），handleAddSuccess 链路缩短。调整步骤顺序使打标事件在日限之前发布（打标更轻量，不依赖日限结果）。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java:243-297` (handleAddSuccess)

- [ ] **Step 1: 确认 handleAddSuccess 逻辑已适配**

Task 8 之后 `customerService.upsertFromCallback` 已经不再调企微 API，返回的是 customerId。handleAddSuccess 的步骤 ② 会变快（~5ms vs ~200ms）。当前 4 步顺序无需改变，只需确认编译通过。此任务为验证性任务。

- [ ] **Step 2: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 11: DataFillWorker 异步补全客户信息

**原因：** Task 8 把 `getExternalContact` 从主链路移除后，需要有独立消费者补全 name/avatar/unionid。

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java`

- [ ] **Step 1: 创建 DataFillWorker**

```java
package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis Stream 消费者 —— 异步补全新客户的企微信息。
 *
 * <p><b>背景：</b>CallbackWorker 新增客户时不调企微 API（Fast Ack 设计），
 * 只写入 name="未知" 的稀疏记录。本 Worker 独立消费补全事件，
 * 调用 GET /externalcontact/get 获取真实 name/avatar/unionid 并回填。</p>
 *
 * <p><b>设计原则：</b>
 * <ul>
 *   <li>独立 Stream (wecom:datafill:stream)，不限长（不 trim），数据不可丢失</li>
 *   <li>单线程消费，避免对企微 API 造成并发限流压力</li>
 *   <li>补全失败不 ACK 而是记日志后 ACK（避免 PEL 堆积），
 *       失败记录由定时 repairCustomerData 兜底</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFillWorker {

    private final StringRedisTemplate redisTemplate;
    private final CustomerRepository customerRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;

    private volatile boolean running = true;
    private static final String CONSUMER_NAME = "datafill-worker-1";

    @PostConstruct
    public void start() {
        taskExecutor.execute(this::consumeLoop);
        log.info("DataFillWorker 已启动, Stream={}, Group={}",
            RedisConfig.DATAFILL_STREAM_KEY, RedisConfig.DATAFILL_CONSUMER_GROUP);
    }

    private void consumeLoop() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.DATAFILL_CONSUMER_GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(10)),
                        StreamOffset.create(RedisConfig.DATAFILL_STREAM_KEY,
                            ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        Map<Object, Object> value = record.getValue();
                        String eventJson = (String) value.get("event");
                        processEvent(eventJson);
                    } catch (Exception e) {
                        log.error("补全客户信息失败", e);
                    } finally {
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.DATAFILL_STREAM_KEY,
                            RedisConfig.DATAFILL_CONSUMER_GROUP,
                            record.getId().getValue());
                    }
                }

                // DataFill Stream 不 trim —— 补全指令不可丢失

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("DataFillWorker 消费异常, 10s 后重试", e);
                try { Thread.sleep(10000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("DataFillWorker 已停止");
    }

    private void processEvent(String eventJson) throws Exception {
        JsonNode event = objectMapper.readTree(eventJson);
        String externalUserId = event.has("external_userid")
            ? event.get("external_userid").asText() : null;
        Long customerId = event.has("customer_id")
            ? event.get("customer_id").asLong() : null;

        if (externalUserId == null) return;

        // 查当前客户记录，确认仍需补全
        Customer customer;
        if (customerId != null) {
            customer = customerRepo.findById(customerId).orElse(null);
        } else {
            customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        }

        if (customer == null) {
            log.warn("DataFill 客户不存在: external={}", externalUserId);
            return;
        }

        // 只补全仍为默认值的字段（幂等）
        if (!"未知".equals(customer.getName()) && customer.getAvatar() != null
                && customer.getUnionid() != null) {
            return;  // 已补全，跳过
        }

        try {
            JsonNode detail = wecomApi.getExternalContact(externalUserId);
            if (detail.has("external_contact")) {
                JsonNode ec = detail.get("external_contact");
                boolean changed = false;

                if ("未知".equals(customer.getName()) && ec.has("name")) {
                    customer.setName(ec.get("name").asText());
                    changed = true;
                }
                if (customer.getAvatar() == null && ec.has("avatar")
                        && !ec.get("avatar").isNull()) {
                    customer.setAvatar(ec.get("avatar").asText());
                    changed = true;
                }
                if (customer.getUnionid() == null && ec.has("unionid")
                        && !ec.get("unionid").isNull()) {
                    customer.setUnionid(ec.get("unionid").asText());
                    changed = true;
                }
                if (ec.has("type")) {
                    customer.setType(ec.get("type").asInt());
                    changed = true;
                }

                if (changed) {
                    customerRepo.save(customer);
                    log.debug("客户信息补全完成: external={}, name={}", externalUserId,
                        customer.getName());
                }
            }
        } catch (Exception e) {
            // 企微 API 可能限流或超时，记日志后跳过，依赖 repairCustomerData 兜底
            log.warn("DataFill API 调用失败（将依赖定时修复兜底）: external={}",
                externalUserId, e);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 12: taskExecutor 队列扩容 + DiscardOldestPolicy

**原因：** 4 线程消费触发大量 `syncQrCodeToWechatAsync`，taskExecutor 队列 1000 不够，CallerRunsPolicy 反噬消费者。

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/AsyncConfig.java:77-87` (taskExecutor Bean)

- [ ] **Step 1: 修改 taskExecutor 配置**

替换 `taskExecutor()` 方法（第 77-87 行）：

```java
    /**
     * 通用异步任务线程池 — TagWorker、DataFillWorker、企微活码异步同步。
     *
     * <p><b>拒绝策略说明：</b>DiscardOldestPolicy 丢弃队列中最旧的任务，
     * 防止 syncQrCodeToWechatAsync 堆积反噬消费者线程。
     * 活码同步是幂等操作（最新一次覆盖之前的），丢旧任务无影响。</p>
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);          // 扩容到 6，应对 sync 峰值
        executor.setQueueCapacity(2000);     // 扩容到 2000，缓冲更多 sync 任务
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.DiscardOldestPolicy());  // 丢弃最旧任务
        executor.initialize();
        return executor;
    }
```

- [ ] **Step 2: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 13: RateLimiterService 移除 Thread.sleep 残留

**原因：** Task 5 已移除 sleep，此任务确认无遗漏。

- [ ] **Step 1: 确认 RateLimiterService 中无 Thread.sleep 调用**

```bash
cd D:\ClaudeCode\HuoMa && grep -n "Thread.sleep\|Thread\.sleep" src/main/java/com/bookstore/qrcode/service/RateLimiterService.java
```

预期无输出。

---

## Phase 4：监控兜底

### Task 14: 全局池枯竭快速预警 + PEL 深度监控端点

**原因：** 4 线程可能导致全局池瞬间枯竭（见分析），需要暴露实时指标供运维监控。

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/HealthController.java`

- [ ] **Step 1: 创建健康检查 Controller**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维健康检查端点 —— 暴露 Stream 深度、PEL 积压、全局池余量等关键指标。
 *
 * <p>路径：GET /api/health/streams —— 供 Prometheus / 云监控抓取。</p>
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate redisTemplate;
    private final GlobalAgentPoolRepository poolRepo;

    @GetMapping("/api/health/streams")
    public Map<String, Object> streamHealth() {
        Map<String, Object> health = new LinkedHashMap<>();

        // CALLBACK_STREAM 深度
        try {
            Long callbackLen = redisTemplate.opsForStream()
                .size(RedisConfig.CALLBACK_STREAM_KEY);
            health.put("callback_stream_length", callbackLen != null ? callbackLen : 0);
        } catch (Exception e) {
            health.put("callback_stream_length", "ERROR: " + e.getMessage());
        }

        // CALLBACK_STREAM PEL 积压
        try {
            PendingMessagesSummary callbackPending = redisTemplate.opsForStream()
                .pending(RedisConfig.CALLBACK_STREAM_KEY,
                         RedisConfig.CALLBACK_CONSUMER_GROUP);
            health.put("callback_pel_pending",
                callbackPending != null ? callbackPending.getTotalPendingMessages() : 0);
        } catch (Exception e) {
            health.put("callback_pel_pending", "ERROR: " + e.getMessage());
        }

        // TAG_STREAM 深度
        try {
            Long tagLen = redisTemplate.opsForStream()
                .size(RedisConfig.TAG_STREAM_KEY);
            health.put("tag_stream_length", tagLen != null ? tagLen : 0);
        } catch (Exception e) {
            health.put("tag_stream_length", "ERROR: " + e.getMessage());
        }

        // DATAFILL_STREAM 深度
        try {
            Long datafillLen = redisTemplate.opsForStream()
                .size(RedisConfig.DATAFILL_STREAM_KEY);
            health.put("datafill_stream_length", datafillLen != null ? datafillLen : 0);
        } catch (Exception e) {
            health.put("datafill_stream_length", "ERROR: " + e.getMessage());
        }

        // 全局池 standby 余量
        try {
            long standbyCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
            health.put("global_pool_standby", standbyCount);
            health.put("global_pool_warning", standbyCount < 5);
        } catch (Exception e) {
            health.put("global_pool_standby", "ERROR: " + e.getMessage());
        }

        health.put("timestamp", java.time.Instant.now().toString());
        return health;
    }
}
```

- [ ] **Step 2: 确认 GlobalAgentPoolRepository 有 countByStatus 方法**

如果不存在，需要在 `GlobalAgentPoolRepository` 中添加：

```java
    long countByStatus(GlobalAgentPool.PoolStatus status);
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q compile
```

---

### Task 15: 全局池 PatrolWorker 告警阈值调整

**原因：** 4 线程轮换速度快，5 分钟巡逻间隔可能太慢。降低 standby 告警阈值并缩短检查间隔。

**Files:**
- Modify: 检查现有 `PatrolWorker.java` 的 `@Scheduled` 配置和阈值
- 此任务取决于实际 PatrolWorker 代码，留为验证性任务

- [ ] **Step 1: 确认 PatrolWorker 已有空池告警**

检查 `PatrolWorker` 中是否已调用 `alertService.alertEmptyBackup`。如果已有，确保 threshold 从 `< 5` 改为 `< 10`（4 线程下需更多缓冲）。

- [ ] **Step 2: 确认即可（现有 PatrolWorker 已有相关逻辑）**

---

### Task 16: 集成验证 — 启动应用确认所有 Worker 正常

**原因：** 16 个任务改动后需要端到端验证所有 Worker 启动且无异常日志。

- [ ] **Step 1: 启动应用**

```bash
cd D:\ClaudeCode\HuoMa && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -E "Worker|ERROR|WARN"
```

预期日志：
```
CallbackWorker 已启动 4 个消费线程, Stream=wecom:callback:stream, Group=callback-worker-group
TagWorker 已启动, Stream=wecom:tag:stream, Group=tag-worker-group
DataFillWorker 已启动, Stream=wecom:datafill:stream, Group=datafill-worker-group
```

不应有 ERROR 级别日志。

- [ ] **Step 2: 调用健康检查端点**

```bash
curl -s http://localhost:8080/api/health/streams | python -m json.tool
```

预期返回 6 个字段均为正常值。

- [ ] **Step 3: 测试回调入队（如本地有企微沙箱）**

```bash
curl -X POST "http://localhost:8080/api/wecom/callback?msg_signature=test&timestamp=123&nonce=abc" \
  -H "Content-Type: application/xml" \
  -d '<xml>...加密测试数据...</xml>'
```

预期：返回 `"success"`，Redis 中 `wecom:callback:stream` 长度 +1，几秒后被某一个消费线程处理。

---

## 自检清单

| # | 检查项 | 状态 |
|---|--------|:----:|
| 1 | DB unique 约束已存在（schema.sql 第 136 行） | ✅ 无需改动 |
| 2 | 消费者名称动态生成不重复 | ✅ Task 2 + 7 |
| 3 | PEL 30s 自动恢复 | ✅ Task 3 |
| 4 | 42001 自动重试一次 | ✅ Task 4 |
| 5 | access_token double-check 防双重刷新 | ✅ Task 4 |
| 6 | 频控 Lua 原子化 | ✅ Task 5 |
| 7 | 频控不再 sleep 阻塞 | ✅ Task 5 |
| 8 | CALLBACK_STREAM 消费后 trim | ✅ Task 6 |
| 9 | 4 线程各自独立 consumerName | ✅ Task 7 |
| 10 | 客户并发 upsert Redis 锁 + DB 兜底 | ✅ Task 8 |
| 11 | 新客户不再调企微 API | ✅ Task 8 |
| 12 | TAG_STREAM trim 集中到 TagWorker | ✅ Task 9 |
| 13 | DataFillWorker 独立补全客户信息 | ✅ Task 11 |
| 14 | taskExecutor 队列扩容 + DiscardOldestPolicy | ✅ Task 12 |
| 15 | 健康检查端点暴露关键指标 | ✅ Task 14 |
| 16 | 全局池 standby < 10 告警 | ✅ Task 15 |
