package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 异常监控与告警服务。
 *
 * <p>按严重程度分级记录并触发自动处置动作，覆盖两类真实失败信号：</p>
 * <ul>
 *   <li><b>客户添加失败</b> — 下游企微 API（发欢迎语、打标）返回 25002/84073/84061 等错误码时，
 *       通过 {@link #handleCustomerApiError} 累计计数，达到阈值后告警并暂停员工。</li>
 *   <li><b>客户接替失败</b> — 企微推送 {@code transfer_fail} 事件时，通过
 *       {@link #handleTransferFail} 对 {@code customer_limit_exceed} 告警；
 *       {@code customer_refused} 仅记日志（客户拒绝由轮询落库 rejected）。</li>
 * </ul>
 *
 * <h3>企微错误码分级处理策略</h3>
 * <table>
 *   <tr><th>错误码</th><th>含义</th><th>处理方式</th></tr>
 *   <tr><td>25002</td><td>拒绝添加（客户主动拒绝/操作被拦截）</td><td>累计型异常，阈值 10 个客户/小时触发暂停</td></tr>
 *   <tr><td>84073</td><td>客户已删除员工或被删</td><td>累计型异常，阈值 5 个客户/小时触发暂停</td></tr>
 *   <tr><td>84061</td><td>客户关系不存在(not external contact)</td><td>累计型异常，默认阈值 5 个客户/小时触发暂停</td></tr>
 *   <tr><td>其他</td><td>其他失败原因</td><td>累计型异常，默认阈值 5 个客户/小时触发暂停</td></tr>
 * </table>
 *
 * <p>累计型异常采用 1 小时滑动窗口计数，达到阈值后自动暂停员工({@link #pauseAgent})，避免高频率失败影响用户体验。</p>
 *
 * @author 书店技术团队
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AgentAlertRepository alertRepo;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final PlatformTransactionManager transactionManager;

    /** 累计型告警 1 小时滑动窗口计数 Lua 脚本：ZADD + ZREMRANGE + ZCARD + EXPIRE 原子执行 */
    private static final String ALERT_COUNT_LUA =
        "local key = KEYS[1]\n"
        + "local now = tonumber(ARGV[1])\n"
        + "local window = tonumber(ARGV[2])\n"
        + "local member = ARGV[3]\n"
        + "local ttl = tonumber(ARGV[4])\n"
        + "redis.call('ZADD', key, now, member)\n"
        + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
        + "redis.call('EXPIRE', key, ttl)\n"
        + "local count = redis.call('ZCARD', key)\n"
        + "return count";

    private static final DefaultRedisScript<Long> ALERT_COUNT_SCRIPT;

    static {
        ALERT_COUNT_SCRIPT = new DefaultRedisScript<>();
        ALERT_COUNT_SCRIPT.setScriptText(ALERT_COUNT_LUA);
        ALERT_COUNT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 处理客户添加失败相关的企微 API 错误码。
     *
     * <p>这些错误码并非来自回调事件，而是发欢迎语 / 打标等下游企微 API 调用返回：
     * <ul>
     *   <li><b>25002</b> — 客户拒绝添加好友请求</li>
     *   <li><b>84073</b> — 客户已删除该服务人员</li>
     *   <li><b>84061</b> — 客户关系不存在（not external contact）</li>
     * </ul>
     * 采用 1 小时滑动窗口累计：按错误码分桶（各错误码独立计数），
     * 且同一客户(external_userid)在窗口内只计一次，避免同一根因在发欢迎语/打标等多个
     * 下游 API 点被重复放大。达到阈值后暂停员工({@link #pauseAgent})，
     * 阈值一半时记录中等告警作为提前预警。</p>
     *
     * @param userId         接待员工 userid
     * @param externalUserId 客户 external_userid（可为 null）
     * @param errcode        企微返回的错误码
     * @param reason         失败原因描述（企微 errmsg 或本地文案）
     * @param state          活码标识（用于反查 qrCodeId，可为 null）
     */
    @Transactional
    public void handleCustomerApiError(String userId, String externalUserId,
                                       int errcode, String reason, String state) {
        if (userId == null) return;

        Long qrCodeId = findQrCodeId(state);
        // 计数维度：按错误码分桶（25002/84073/84061 各自独立累计），
        // 且同一客户(external_userid)在窗口内只计一次，避免同一根因在多个下游 API 点被放大
        String dimension = "add_fail:" + errcode;
        long count = incrementAlertCount(dimension, userId, externalUserId);
        int threshold = WecomErrorCodes.ACCUMULATE_THRESHOLD.getOrDefault(errcode, 5);

        if (count >= threshold) {
            // 达到阈值 → 重置计数（防止后续每次失败重复告警）+ 高级告警 + 自动暂停
            resetAlertCount(dimension, userId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errcode", errcode);
            detail.put("count", count);
            detail.put("reason", reason);
            detail.put("external_userid", externalUserId);
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.high,
                detail, AgentAlert.AutoAction.paused, qrCodeId);
            pauseAgent(userId);
        } else if (count == threshold / 2) {
            // 恰好跨过阈值一半 → 中级告警作为提前预警（仅触发一次，不自动处置）
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errcode", errcode);
            detail.put("count", count);
            detail.put("reason", reason);
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.medium,
                detail, AgentAlert.AutoAction.none, qrCodeId);
        }
    }

    /**
     * 处理客户接替失败事件(transfer_fail)。
     *
     * <p>企微在客户拒绝接替或接替成员客户数达上限时，推送 {@code change_external_contact}
     * 且 {@code ChangeType=transfer_fail} 的事件，携带 {@code FailReason} 枚举：
     * <ul>
     *   <li>{@code customer_refused} — 客户拒绝接替，仅记日志不告警，拒绝结果由
     *       {@code get_transfer_result} 轮询落库为 rejected；</li>
     *   <li>{@code customer_limit_exceed} — 接替成员客户数达上限，告警(high)；</li>
     *   <li>其他 — 未知失败原因，告警(medium)。</li>
     * </ul>
     * 接替失败多为客户侧行为，不做自动暂停/熔断。</p>
     *
     * @param event 企微回调事件 JSON 节点，包含 userid、external_userid、fail_reason 字段
     */
    @Transactional
    public void handleTransferFail(JsonNode event) {
        String userId = getStr(event, "userid");
        String externalUserId = getStr(event, "external_userid");
        String failReason = getStr(event, "fail_reason");

        if ("customer_refused".equals(failReason)) {
            // 客户拒绝接替是客户侧既定事实，告警无法改变；拒绝结果由
            // get_transfer_result 轮询落库为 CustomerTransfer.rejected，此处仅记日志不告警。
            log.info("客户拒绝接替(不告警): userid={}, external_userid={}",
                userId, externalUserId);
            return;
        }

        String reason;
        AgentAlert.AlertSeverity severity;
        if ("customer_limit_exceed".equals(failReason)) {
            reason = "接替成员客户数达上限";
            severity = AgentAlert.AlertSeverity.high;
        } else {
            reason = failReason != null ? failReason : "未知接替失败原因";
            severity = AgentAlert.AlertSeverity.medium;
        }

        createAlert(userId, "transfer_fail", severity,
            Map.of("fail_reason", failReason, "reason", reason,
                   "external_userid", externalUserId),
            AgentAlert.AutoAction.none, null);
    }

    /**
     * 熔断员工 — 暂停其服务能力，从所有活码移除。
     *
     * <p>熔断是最高级别的自动处置动作，触发条件：</p>
     * <ul>
     *   <li>{@link com.bookstore.qrcode.service.RateLimiterService} 检测到 1 分钟内添加超过 60 人</li>
     * </ul>
     *
     * <p>熔断逻辑：</p>
     * <ul>
     *   <li>将员工状态设为熔断(melted)或封禁(blocked)，取决于 24 小时内熔断次数</li>
     *   <li>24 小时内累计熔断 >= 3 次 → 升级为 blocked，需要人工介入</li>
     *   <li>&lt; 3 次 → melted，30 分钟后可自动恢复（由定时任务处理）</li>
     *   <li>记录告警，通知运营</li>
     * </ul>
     *
     * @param userId   员工 userid
     * @param qrCodeId 触发熔断的活码 ID（可为 null，如全局速率触发时不关联特定活码）
     * @param reason   熔断原因描述
     */
    @Transactional
    public void meltAgent(String userId, Long qrCodeId, String reason) {
        // 使用悲观写锁（SELECT ... FOR UPDATE）避免高并发下多线程同时更新同一行导致死锁
        Agent agent = agentRepo.findByIdForUpdate(userId).orElse(null);
        if (agent != null) {
            // 已封禁的不再重复更新，避免死锁和无谓的 DB 写
            if (agent.getOverallStatus() == Agent.OverallStatus.blocked) {
                log.debug("员工 {} 已封禁，跳过熔断", userId);
                return;
            }

            int meltCount = agent.getMeltedCount24h() + 1;
            boolean upgradedToBlocked = meltCount >= 3;
            agent.setOverallStatus(upgradedToBlocked
                ? Agent.OverallStatus.blocked
                : Agent.OverallStatus.melted);
            agent.setMeltedCount24h(meltCount);
            // status_reason 列类型为 JSON，需序列化而非存裸字符串
            Map<String, Object> reasonMap = new HashMap<>();
            reasonMap.put("reason", reason);
            reasonMap.put("melted_at", LocalDateTime.now().toString());
            reasonMap.put("melt_count_24h", meltCount);
            agent.setStatusReason(objectMapper.valueToTree(reasonMap).toString());
            agentRepo.save(agent);

            // 升级为封禁时，同步清理全局池（参照 blockAgentForWechatIssue）
            if (upgradedToBlocked) {
                poolRepo.findByAgentUserid(userId).ifPresent(pool -> {
                    poolRepo.delete(pool);
                    log.warn("熔断升级为封禁，全局池已移除: userid={}", userId);
                });
            }

            createAlert(userId, "melt", AgentAlert.AlertSeverity.high,
                Map.of("reason", reason, "melt_count_24h", meltCount),
                AgentAlert.AutoAction.melted, qrCodeId);

            log.warn("员工 {} 已熔断: {}", userId, reason);
        }
    }

    /**
     * 创建异常告警记录并持久化。
     *
     * <p>告警分两类，行为不同（由 {@link AlertTypeMeta#isConditionType(String)} 判定）：</p>
     * <ul>
     *   <li><b>条件类</b> — 同一条件持续存在时合并为一条：按归一化键
     *       （类型 + 员工 + 活码）查找 open 行，命中则更新 detail / lastSeenAt /
     *       occurrenceCount / severity，不再新增；未命中才插入。</li>
     *   <li><b>事件类</b> — 每次发生都独立记录，始终插入新行。</li>
     * </ul>
     *
     * <p>告警记录包含以下关键信息：</p>
     * <ul>
     *   <li>关联员工(agentUserid)和活码(qrCodeId)，均按类型归一化后写入</li>
     *   <li>告警类型(alertType)：见 {@link AlertTypeMeta}</li>
     *   <li>严重程度(severity)：low / medium / high</li>
     *   <li>详细内容(detail)：JSON 格式，包含错误码、次数等上下文</li>
     *   <li>自动处置动作(autoAction)：none / paused / melted</li>
     *   <li>初始状态为 open（待处理）</li>
     * </ul>
     *
     * @param agentUserid 关联的员工 userid
     * @param alertType   告警类型
     * @param severity    严重等级
     * @param detail      详细内容（String 或 Map，自动序列化为 JSON）
     * @param autoAction  已执行的自动处置动作
     * @param qrCodeId    关联的活码 ID（可为 null）
     * @return 持久化后的 {@link AgentAlert} 实体，序列化或写入失败时返回 null
     */
    public AgentAlert createAlert(String agentUserid, String alertType,
                                   AgentAlert.AlertSeverity severity,
                                   Object detail,
                                   AgentAlert.AutoAction autoAction,
                                   Long qrCodeId) {
        final String detailJson;
        try {
            // 统一经 Jackson 序列化，确保字符串也会被 JSON 编码（加引号），
            // 否则 MySQL JSON 列会拒绝裸中文字符串（Invalid JSON text）
            detailJson = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.error("创建告警失败: detail 序列化异常", e);
            return null;
        }
        // 归一化键：traffic_spike 丢弃员工、全局类型丢弃员工与活码，
        // 查找与写入必须使用同一套归一化值，否则永远命中不了已有的 open 行。
        final String keyAgent = AlertTypeMeta.normalizeAgent(alertType, agentUserid);
        final Long keyQr = AlertTypeMeta.normalizeQr(alertType, qrCodeId);
        final boolean conditionType = AlertTypeMeta.isConditionType(alertType);
        // 用 TransactionTemplate 而非 @Transactional(REQUIRES_NEW)：@Transactional 在自调用时被代理绕过，
        // 且提交异常发生在方法体返回之后、try-catch 之外，仍会抛 UnexpectedRollbackException 冒泡给调用方。
        // TransactionTemplate 把事务边界移进方法内，写入/提交失败都能被下方 catch 接住，告警保持「尽力而为」。
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                if (conditionType) {
                    List<AgentAlert> existing = alertRepo.findOpenByKey(
                        alertType, keyAgent, keyQr, AgentAlert.AlertStatus.open);
                    if (existing != null && !existing.isEmpty()) {
                        // 取最早一条（findOpenByKey 按 id 升序），合并本次命中
                        AgentAlert alert = existing.get(0);
                        alert.setAgentUserid(keyAgent);
                        alert.setQrCodeId(keyQr);
                        alert.setDetail(detailJson);
                        alert.setLastSeenAt(now);
                        int previous = alert.getOccurrenceCount();
                        alert.setOccurrenceCount((previous <= 0 ? 1 : previous) + 1);
                        if (severity != null
                            && (alert.getSeverity() == null
                                || severity.ordinal() > alert.getSeverity().ordinal())) {
                            alert.setSeverity(severity);
                        }
                        alert.setAutoAction(autoAction);
                        AgentAlert saved = alertRepo.save(alert);
                        log.debug("告警合并(upsert): id={}, user={}, type={}, severity={}, count={}",
                            alert.getId(), keyAgent, alertType, alert.getSeverity(),
                            alert.getOccurrenceCount());
                        return saved;
                    }
                }
                AgentAlert alert = AgentAlert.builder()
                    .agentUserid(keyAgent)
                    .alertType(alertType)
                    .severity(severity)
                    .detail(detailJson)
                    .autoAction(autoAction)
                    .status(AgentAlert.AlertStatus.open)
                    .qrCodeId(keyQr)
                    .lastSeenAt(now)
                    .occurrenceCount(1)
                    .build();
                AgentAlert saved = alertRepo.save(alert);
                log.warn("告警: user={}, type={}, severity={}, detail={}",
                    keyAgent, alertType, severity, detailJson);
                return saved;
            });
        } catch (Exception e) {
            log.error("创建告警失败", e);
            return null;
        }
    }

    // ==================== 条件清除后的自动关闭 ====================

    /**
     * 关闭某类型下所有 open 告警（条件已整体清除）。
     *
     * @param alertType 告警类型
     * @return 关闭的数量
     */
    @Transactional
    public int resolveOpenByType(String alertType) {
        return resolveWhere(List.of(alertType), alert -> true);
    }

    /**
     * 关闭某类型下匹配归一化键（员工 + 活码）的 open 告警。
     *
     * @param alertType   告警类型
     * @param agentUserid 员工 userid（按类型归一化，traffic_spike/全局类型会忽略）
     * @param qrCodeId    活码 ID（按类型归一化）
     * @return 关闭的数量
     */
    @Transactional
    public int resolveOpenByKey(String alertType, String agentUserid, Long qrCodeId) {
        String keyAgent = AlertTypeMeta.normalizeAgent(alertType, agentUserid);
        Long keyQr = AlertTypeMeta.normalizeQr(alertType, qrCodeId);
        return resolveWhere(List.of(alertType), alert ->
            Objects.equals(alert.getAgentUserid(), keyAgent)
                && Objects.equals(alert.getQrCodeId(), keyQr));
    }

    /**
     * 关闭某类型下活码不在保留集合内的 open 告警。
     *
     * @param alertType 告警类型
     * @param keepQrIds 仍需保持 open 的活码 ID 集合
     * @return 关闭的数量
     */
    @Transactional
    public int resolveOpenByTypeExceptQr(String alertType, Collection<Long> keepQrIds) {
        return resolveWhere(List.of(alertType), alert ->
            alert.getQrCodeId() == null || !keepQrIds.contains(alert.getQrCodeId()));
    }

    /**
     * 关闭某类型下员工不在保留集合内的 open 告警。
     *
     * @param alertType  告警类型
     * @param keepAgents 仍需保持 open 的员工集合
     * @return 关闭的数量
     */
    @Transactional
    public int resolveOpenByTypeExceptAgents(String alertType, Collection<String> keepAgents) {
        return resolveWhere(List.of(alertType), alert ->
            alert.getAgentUserid() == null || !keepAgents.contains(alert.getAgentUserid()));
    }

    /**
     * 跨类型关闭：给定这些类型下合并键不在保留集合内的 open 告警。
     *
     * @param alertTypes 告警类型集合
     * @param keepKeys   仍需保持 open 的合并键集合（{@link AlertTypeMeta#keyOf} 生成）
     * @return 关闭的数量
     */
    @Transactional
    public int resolveOpenByTypesExceptKeys(Collection<String> alertTypes,
                                            Collection<String> keepKeys) {
        return resolveWhere(alertTypes, alert ->
            !keepKeys.contains(AlertTypeMeta.keyOf(
                alert.getAlertType(), alert.getAgentUserid(), alert.getQrCodeId())));
    }

    /**
     * 关闭助手：查询给定类型下的 open 告警，按 {@code keepOut} 过滤出需要关闭的行，
     * 批量置为 {@code auto_resolved}。没有需要关闭的行时直接返回 0，避免发出空的
     * {@code IN ()} SQL。
     *
     * @param alertTypes 告警类型集合
     * @param keepOut    返回 {@code true} 表示该行应被关闭（从 open 中剔除）
     * @return 关闭的数量
     */
    private int resolveWhere(Collection<String> alertTypes, Predicate<AgentAlert> keepOut) {
        // 空集合会生成非法 SQL `IN ()`，直接短路（顺带防护 null）
        if (alertTypes == null || alertTypes.isEmpty()) {
            return 0;
        }
        List<AgentAlert> open = alertRepo.findByAlertTypeInAndStatus(
            alertTypes, AgentAlert.AlertStatus.open);
        List<Long> ids = open.stream()
            .filter(keepOut)
            .map(AgentAlert::getId)
            .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        int closed = alertRepo.closeByIds(ids, AgentAlert.AlertStatus.auto_resolved,
            LocalDateTime.now(), "auto:condition-cleared");
        log.info("自动关闭告警 {} 条（命中 {} 条）: types={}, ids={}",
            closed, ids.size(), alertTypes, ids);
        return ids.size();
    }

    // ==================== 每小时安全网：去重与过期归档 ====================

    /**
     * 合并重复的 open 条件类告警。
     *
     * <p>多节点并发或旧代码可能为同一条件留下多条 open 行；本方法按
     * {@link AlertTypeMeta#keyOf} 分组，组内保留 id 最小（最早）的一条作为存活行，
     * 汇总 {@code occurrenceCount}（null/0 视作 1）、取最大 {@code lastSeenAt}
     * （为空时回退 {@code createdAt}）、取更高 severity、取最近命中行的 {@code detail}；
     * 其余行批量置为 {@code auto_resolved}，解决人记为 {@code auto:dedup}。</p>
     *
     * <p>事件类告警不做合并。任何异常都在方法内吞掉并记日志，返回 0，
     * 避免每小时任务因单次失败中断。</p>
     *
     * @return 被合并关闭的条数
     */
    @Transactional
    public int collapseDuplicates() {
        try {
            List<AgentAlert> open = alertRepo.findByStatus(AgentAlert.AlertStatus.open);
            if (open == null || open.isEmpty()) {
                return 0;
            }
            Map<String, List<AgentAlert>> groups = new LinkedHashMap<>();
            for (AgentAlert alert : open) {
                String type = alert.getAlertType();
                if (type == null || !AlertTypeMeta.isConditionType(type)) {
                    continue;
                }
                String key = AlertTypeMeta.keyOf(type, alert.getAgentUserid(), alert.getQrCodeId());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(alert);
            }

            List<Long> toClose = new ArrayList<>();
            List<AgentAlert> changed = new ArrayList<>();
            for (List<AgentAlert> group : groups.values()) {
                if (group.size() < 2) {
                    continue;
                }
                // 存活行 = id 最小（最早）的一条
                group.sort(Comparator.comparing(AgentAlert::getId,
                    Comparator.nullsLast(Comparator.naturalOrder())));
                AgentAlert survivor = group.get(0);

                int total = 0;
                LocalDateTime maxLastSeen = null;
                AgentAlert.AlertSeverity maxSeverity = survivor.getSeverity();
                AgentAlert detailSource = survivor;
                for (AgentAlert alert : group) {
                    int count = alert.getOccurrenceCount();
                    total += count <= 0 ? 1 : count;

                    LocalDateTime seen = lastSeenOf(alert);
                    if (seen != null && (maxLastSeen == null || seen.isAfter(maxLastSeen))) {
                        maxLastSeen = seen;
                    }
                    // detail 取「最近命中」的一条：lastSeen 最大，并列时取 id 最大
                    if (isLater(alert, detailSource)) {
                        detailSource = alert;
                    }
                    if (alert.getSeverity() != null
                        && (maxSeverity == null
                            || alert.getSeverity().ordinal() > maxSeverity.ordinal())) {
                        maxSeverity = alert.getSeverity();
                    }
                }

                boolean rowChanged = survivor.getOccurrenceCount() != total
                    || !Objects.equals(survivor.getLastSeenAt(), maxLastSeen)
                    || survivor.getSeverity() != maxSeverity
                    || !Objects.equals(survivor.getDetail(), detailSource.getDetail());
                survivor.setOccurrenceCount(total);
                if (maxLastSeen != null) {
                    survivor.setLastSeenAt(maxLastSeen);
                }
                survivor.setSeverity(maxSeverity);
                survivor.setDetail(detailSource.getDetail());
                if (rowChanged) {
                    changed.add(survivor);
                }
                for (int i = 1; i < group.size(); i++) {
                    Long id = group.get(i).getId();
                    if (id != null) {
                        toClose.add(id);
                    }
                }
            }

            if (!changed.isEmpty()) {
                alertRepo.saveAll(changed);
            }
            if (toClose.isEmpty()) {
                return 0;
            }
            alertRepo.closeByIds(toClose, AgentAlert.AlertStatus.auto_resolved,
                LocalDateTime.now(), "auto:dedup");
            log.info("合并重复告警 {} 条（存活行有变化 {} 条）", toClose.size(), changed.size());
            return toClose.size();
        } catch (Exception e) {
            log.error("合并重复告警失败", e);
            return 0;
        }
    }

    /**
     * 归档已无法再被处理的过期 open 告警。
     *
     * <p>按类型与阈值判定（{@code lastSeen = lastSeenAt != null ? lastSeenAt : createdAt}，
     * 兼容滚动部署期间遗留的 NULL 行）：</p>
     * <ul>
     *   <li>{@code daily_anomaly_heal} — {@code createdAt} 早于 24 小时前</li>
     *   <li>{@code traffic_spike} / {@code wechat_48002} — {@code lastSeen} 早于 24 小时前</li>
     *   <li>{@code qr_agent_anomaly} / {@code dlq_replay_exhausted} /
     *       {@code deletion_report_fail} — {@code lastSeen} 早于 7 天前</li>
     *   <li>{@code employee_departed_service} — {@code lastSeen} 早于 30 天前</li>
     *   <li>{@code wechat_unavailable} 且 {@code detail} 含「无客户联系权限」— 无条件</li>
     *   <li>{@code transfer_retry_exhausted_svc} — {@code lastSeen} 早于 14 天前</li>
     *   <li>{@code add_fail} / {@code melt} / {@code transfer_fail} — {@code createdAt} 早于 30 天前</li>
     *   <li>其他类型不做归档（真实待办永不自动过期）</li>
     * </ul>
     *
     * <p>归档为 {@code auto_resolved}，解决人记为 {@code auto:stale}。
     * 任何异常都在方法内吞掉并记日志，返回 0。</p>
     *
     * @return 被归档的条数
     */
    @Transactional
    public int archiveStale() {
        try {
            List<AgentAlert> open = alertRepo.findByStatus(AgentAlert.AlertStatus.open);
            if (open == null || open.isEmpty()) {
                return 0;
            }
            LocalDateTime now = LocalDateTime.now();
            List<Long> ids = new ArrayList<>();
            for (AgentAlert alert : open) {
                if (alert.getId() != null && isStale(alert, now)) {
                    ids.add(alert.getId());
                }
            }
            if (ids.isEmpty()) {
                return 0;
            }
            alertRepo.closeByIds(ids, AgentAlert.AlertStatus.auto_resolved, now, "auto:stale");
            log.info("归档过期告警 {} 条", ids.size());
            return ids.size();
        } catch (Exception e) {
            log.error("归档过期告警失败", e);
            return 0;
        }
    }

    /** 最近命中时间：优先 {@code lastSeenAt}，滚动部署遗留的 NULL 回退 {@code createdAt}。 */
    private static LocalDateTime lastSeenOf(AgentAlert alert) {
        return alert.getLastSeenAt() != null ? alert.getLastSeenAt() : alert.getCreatedAt();
    }

    /** 比较两行的「最近命中」先后：lastSeen 大者在后；并列时 id 大者在后。 */
    private static boolean isLater(AgentAlert candidate, AgentAlert current) {
        LocalDateTime ct = lastSeenOf(candidate);
        LocalDateTime ot = lastSeenOf(current);
        int cmp;
        if (ct == null && ot == null) {
            cmp = 0;
        } else if (ct == null) {
            cmp = -1;
        } else if (ot == null) {
            cmp = 1;
        } else {
            cmp = ct.compareTo(ot);
        }
        if (cmp != 0) {
            return cmp > 0;
        }
        Long ci = candidate.getId();
        Long oi = current.getId();
        if (ci == null) {
            return false;
        }
        return oi == null || ci > oi;
    }

    /** 按类型与阈值判定单条 open 告警是否已过期，规则见 {@link #archiveStale()}。 */
    private static boolean isStale(AgentAlert alert, LocalDateTime now) {
        String type = alert.getAlertType();
        if (type == null) {
            return false;
        }
        LocalDateTime lastSeen = lastSeenOf(alert);
        LocalDateTime createdAt = alert.getCreatedAt();
        return switch (type) {
            case "daily_anomaly_heal" -> before(createdAt, now.minusHours(24));
            case "traffic_spike", "wechat_48002" -> before(lastSeen, now.minusHours(24));
            case "qr_agent_anomaly", "dlq_replay_exhausted", "deletion_report_fail" ->
                before(lastSeen, now.minusDays(7));
            case "employee_departed_service" -> before(lastSeen, now.minusDays(30));
            // 仅归档旧文案的无权限告警；自愈告警 detail 也含 errcode=48002，绝不能匹配错误码
            case "wechat_unavailable" ->
                alert.getDetail() != null && alert.getDetail().contains("无客户联系权限");
            case "transfer_retry_exhausted_svc" -> before(lastSeen, now.minusDays(14));
            case "add_fail", "melt", "transfer_fail" -> before(createdAt, now.minusDays(30));
            default -> false;
        };
    }

    private static boolean before(LocalDateTime time, LocalDateTime threshold) {
        return time != null && time.isBefore(threshold);
    }

    /**
     * 后备池为空告警 — 活码需要扩容但后备池无可用接待员。
     *
     * <p>此告警表示运营人员需要尽快为活码补充后备接待员，
     * 否则当服务老师满员时将无法自动扩容，客户添加可能受阻。</p>
     *
     * @param qrCodeId  活码 ID
     * @param schoolName 学校名称（用于告警展示，便于快速定位）
     */
    @Transactional
    public void alertEmptyBackup(Long qrCodeId, String schoolName) {
        createAlert(null, "empty_backup", AgentAlert.AlertSeverity.high,
            Map.of("qr_code_id", qrCodeId, "school_name", schoolName),
            AgentAlert.AutoAction.none, qrCodeId);
    }

    /**
     * 暂停员工 — 将员工状态设为 warning，标记为自动暂停。
     *
     * <p>暂停是较轻的处置动作，与 {@link #meltAgent} 的区别：</p>
     * <ul>
     *   <li>暂停(paused / warning)：不影响已有绑定关系，仅暂停新客户分配</li>
     *   <li>熔断(melted)：从所有活码移除，冷却 30 分钟</li>
     * </ul>
     *
     * <p>暂停原因记录在 statusReason 字段，格式为 JSON，包含自动暂停标记和时间戳，
     * 便于运营人员判断是否为系统自动操作。</p>
     *
     * @param userId 员工 userid
     */
    private void pauseAgent(String userId) {
        Agent agent = agentRepo.findByIdForUpdate(userId).orElse(null);
        if (agent != null) {
            // 已熔断/封禁是更高处置级别，不降级为 warning（否则会绕过熔断）
            if (agent.getOverallStatus() == Agent.OverallStatus.blocked
                || agent.getOverallStatus() == Agent.OverallStatus.melted) {
                log.debug("员工 {} 已熔断/封禁，跳过暂停降级", userId);
                return;
            }
            agent.setOverallStatus(Agent.OverallStatus.warning);
            Map<String, Object> reason = new HashMap<>();
            reason.put("auto_paused", true);
            reason.put("time", LocalDateTime.now().toString());
            agent.setStatusReason(objectMapper.valueToTree(reason).toString());
            agentRepo.save(agent);
        }
    }

    /**
     * 累计型异常计数 +1，返回 1 小时滑动窗口内的累计次数（去重后）。
     * <p>
     * 使用 Redis Sorted Set 滑动窗口（与 {@link RateLimiterService} 同模式），
     * 以 {@code dedupMember}(external_userid) 作为 member 写入，同一 member 在窗口内
     * 只占一个槽位（ZADD 同 member 仅刷新 score），因此 {@code ZCARD} 返回的是
     * 「窗口内不同客户数」而非「失败次数」，实现同一客户同一错误码只计一次的去重。
     * 当 {@code dedupMember} 为 null 时退化为时间戳 member（每次唯一，去重失效但至少计数）。
     * 计数与 {@link AgentAlert} 告警记录解耦，避免「告警记录数 = 失败次数」的死循环。
     * </p>
     *
     * @param dimension  计数维度（如 add_fail:25002，含错误码分桶）
     * @param userId     员工 userid
     * @param dedupMember 去重成员（客户 external_userid，可为 null）
     * @return 窗口内累计数（去重后的不同客户数，含本次）
     */
    private long incrementAlertCount(String dimension, String userId, String dedupMember) {
        long now = Instant.now().getEpochSecond();
        String member = (dedupMember != null && !dedupMember.isBlank())
            ? dedupMember : (now + ":" + System.nanoTime());
        String key = RedisConfig.ALERT_COUNT_KEY_PREFIX + dimension + ":" + userId;
        Long count = redisTemplate.execute(ALERT_COUNT_SCRIPT, List.of(key),
            String.valueOf(now), "3600", member, "7200");
        return count == null ? 0L : count;
    }

    /** 重置累计型异常计数（达到阈值触发告警后调用，防止后续失败重复告警） */
    private void resetAlertCount(String dimension, String userId) {
        String key = RedisConfig.ALERT_COUNT_KEY_PREFIX + dimension + ":" + userId;
        redisTemplate.delete(key);
    }

    /**
     * 根据活码 state 参数反查活码 ID。
     *
     * <p>企微回调事件中的 state 参数对应创建活码时传入的自定义标识（此处为学校标识 schoolId），
     * 通过 {@link QrCodeRepository#findBySchoolId} 反查出系统内的活码 ID。</p>
     *
     * @param state 活码 state 参数
     * @return 活码 ID，如果未找到则返回 null
     */
    private Long findQrCodeId(String state) {
        if (state == null) return null;
        return qrCodeRepo.findBySchoolId(state)
            .map(QrCode::getId).orElse(null);
    }

    /**
     * 从 JSON 节点中安全获取字符串字段值。
     *
     * <p>避免直接调用 {@code event.get(field).asText()} 导致的 NullPointerException，
     * 同时过滤 null 类型的 JSON 值。</p>
     *
     * @param event JSON 节点
     * @param field 字段名
     * @return 字段的字符串值，如果字段不存在或为 null 则返回 null
     */
    private String getStr(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }

    // ==================== 告警中心页面查询 ====================

    /**
     * 多条件分页搜索告警，供告警中心列表页使用。
     *
     * @param status      告警状态筛选，可为 {@code null}
     * @param severity    严重程度筛选，可为 {@code null}
     * @param alertType   告警类型筛选，可为 {@code null}
     * @param agentUserid 员工账号模糊搜索，可为 {@code null}
     * @param qrCodeId    关联活码 ID，可为 {@code null}
     * @param startDate   起始日期（含），可为 {@code null}
     * @param endDate     结束日期（含），可为 {@code null}
     * @param pageable    分页参数
     * @return 满足条件的告警分页数据
     */
    public Page<AgentAlert> findAlerts(AgentAlert.AlertStatus status,
                                       AgentAlert.AlertSeverity severity,
                                       String alertType,
                                       String agentUserid,
                                       Long qrCodeId,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       Pageable pageable) {
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        return alertRepo.search(status, severity, alertType, agentUserid,
            qrCodeId, start, end, pageable);
    }

    /**
     * 获取告警统计摘要 — 按状态和严重程度分组计数。
     *
     * @return Map 包含 openCount / resolvedCount / autoResolvedCount / totalCount
     *         以及 highCount / mediumCount / lowCount
     */
    public Map<String, Long> getAlertStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalCount", alertRepo.count());
        stats.put("openCount", alertRepo.countByStatus(AgentAlert.AlertStatus.open));
        stats.put("resolvedCount", alertRepo.countByStatus(AgentAlert.AlertStatus.resolved));
        stats.put("autoResolvedCount", alertRepo.countByStatus(AgentAlert.AlertStatus.auto_resolved));
        stats.put("highCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.high));
        stats.put("mediumCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.medium));
        stats.put("lowCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.low));
        stats.put("todayCount", alertRepo.countByCreatedAtBetween(todayStart, todayEnd));
        stats.put("weekCount", alertRepo.countByCreatedAtBetween(weekStart, todayEnd));
        return stats;
    }

    /**
     * 管理员手动解决告警。
     *
     * <p>将告警状态从 open 置为 resolved，记录操作人和时间。
     * 已关闭的告警不会重复操作。</p>
     *
     * @param alertId   告警 ID
     * @param adminUser 操作的管理员账号
     */
    @Transactional
    public void resolveAlert(Long alertId, String adminUser) {
        alertRepo.findById(alertId).ifPresent(alert -> {
            if (alert.getStatus() == AgentAlert.AlertStatus.open) {
                alert.setStatus(AgentAlert.AlertStatus.resolved);
                alert.setResolvedBy(adminUser);
                alert.setResolvedAt(LocalDateTime.now());
                alertRepo.save(alert);
                log.info("告警 #{} 已由 {} 手动解决", alertId, adminUser);
            }
        });
    }

    /**
     * 一键解决所有未处理告警，分页遍历直到全部处理完。
     * <p>
     * 每批处理 500 条并立即 flush，确保下一轮查询不会重复捞出已解决的记录。
     * 超大数量（万级）场景下有性能考虑，需注意单次事务时长。
     * </p>
     *
     * @param adminUser 操作的管理员账号
     * @return 实际解决的告警数量
     */
    @Transactional
    public int resolveAllAlerts(String adminUser) {
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        int maxIterations = 200; // 安全上限：最多 200 轮 × 500 = 100,000 条
        Page<AgentAlert> page;
        do {
            page = alertRepo.findByStatus(AgentAlert.AlertStatus.open,
                PageRequest.of(0, 500));
            for (AgentAlert alert : page.getContent()) {
                alert.setStatus(AgentAlert.AlertStatus.resolved);
                alert.setResolvedBy(adminUser);
                alert.setResolvedAt(now);
                alertRepo.save(alert);
                count++;
            }
            alertRepo.flush(); // 确保下一轮查询能看到本轮的更新
        } while (page.hasNext() && --maxIterations > 0);
        if (count > 0) {
            log.info("批量解决告警: {} 条，操作人: {}", count, adminUser);
        }
        return count;
    }
}
