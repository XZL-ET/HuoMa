package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 员工绑定与日限额管理服务。
 *
 * <p>核心职责：管理企微员工与活码的绑定关系，实现日计数 -> 阈值检查 -> 自动轮换 -> 全局池扩容 -> 企微同步 -> 每日重置 的完整业务闭环。</p>
 *
 * <h3>业务链路说明</h3>
 * <ul>
 *   <li><b>日计数：</b>客户扫码添加好友时，通过 {@code Redis INCR} 原子递增该员工的全局今日添加数</li>
 *   <li><b>阈值检查：</b>按 预警阈值(warn) / 紧急阈值(urgent) / 日限(full) 三级判定，使用全局计数</li>
 *   <li><b>自动轮换：</b>员工满员后标记为 {@link QrAgent.AgentStatus#full}，从企微活码暂时移除</li>
 *   <li><b>全局池扩容：</b>从 {@link GlobalAgentPool} 全局池中取待命的接待员加入活码，分担流量</li>
 *   <li><b>企微同步：</b>通过 {@link com.bookstore.qrcode.wecom.WecomApiClient} 同步活码用户列表到企业微信</li>
 *   <li><b>每日重置：</b>由 {@link com.bookstore.qrcode.worker.DailyResetWorker} 凌晨 00:00 执行全局池 full→standby 恢复及活码重同步</li>
 * </ul>
 *
 * <p>轮换模式支持两种：自动模式({@link QrCode.RotateMode#auto})和人工审核模式({@link QrCode.RotateMode#manual})，
 * 人工模式下仅告警不执行自动扩容。</p>
 *
 * @author 书店技术团队
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBindService {

    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final StringRedisTemplate redisTemplate;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final GlobalAgentPoolRepository poolRepo;
    private final GlobalAgentPoolService poolService;
    private final AlertService alertService;
    /** 自身代理引用 — 通过 @Lazy 延迟注入打破循环依赖，确保 @Async/@Transactional 走 AOP 代理 */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private AgentBindService self;

    // ==================== 日计数 ====================

    /**
     * 员工全局日计数 +1。
     *
     * <p>使用 Redis INCR 命令实现原子递增，避免并发场景下的计数丢失。
     * 同时维护活码级别(key)和全局级别(totalKey)两个计数器。</p>
     *
     * <p>执行流程：Redis 递增 -> 设置 TTL 至午夜过期 -> 同步到 DB 持久化 -> 检查全局阈值触发轮换。</p>
     *
     * @param userId  企微员工 userid（唯一标识）
     * @param state   活码 state 参数，用于反查 {@link QrCode} 记录
     */
    public void incrementDailyCount(String userId, String state) {
        // state 是创建活码时传入的自定义参数，此处用于根据学校标识反查活码
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) return;

        // 活码维度 key：统计该员工在该活码下的今日添加量
        String key = RedisConfig.AGENT_DAILY_KEY_PREFIX + userId + ":" + qr.getId();
        // 员工全局维度 key：该员工在所有活码下的今日总添加量（用于后台全局监管）
        String totalKey = RedisConfig.AGENT_DAILY_TOTAL_PREFIX + userId;

        long ttl = Math.max(getSecondsUntilMidnight(), 60);
        // Lua 脚本保证 INCR + EXPIRE 原子执行，防止进程在 INCR 成功后、
        // EXPIRE 执行前崩溃导致 key 永不过期
        String lua = "local v = redis.call('INCR', KEYS[1]) "
                   + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                   + "return v";
        DefaultRedisScript<Long> incrScript = new DefaultRedisScript<>(lua, Long.class);
        long newCount = redisTemplate.execute(incrScript, List.of(key), String.valueOf(ttl));
        long totalNew = redisTemplate.execute(incrScript, List.of(totalKey), String.valueOf(ttl));

        // 同步到 DB（best-effort，失败不影响阈值判断）
        // Redis 是阈值判断的唯一数据源，DB 仅用于重启后恢复计数
        try {
            QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);
            if (qa != null) {
                qa.setDailyCurrent((int) newCount);
                qrAgentRepo.save(qa);
            }
        } catch (Exception e) {
            log.error("同步 qr_agent.daily_current 失败: userId={}, qrId={}, count={}",
                userId, qr.getId(), newCount, e);
        }
        try {
            poolService.updateDailyCurrent(userId, (int) totalNew);
        } catch (Exception e) {
            log.error("同步 global_agent_pool.daily_current 失败: userId={}, count={}",
                userId, totalNew, e);
        }

        // 递增后立即检查全局阈值，若达到日限则触发轮换
        // 通过 proxy 调用确保 @Transactional 生效
        self.checkAndRotate(qr.getId(), userId, (int) totalNew);
    }

    // ==================== 日限检查 + 轮换 ====================

    /**
     * 检查员工全局日限并触发轮换。
     *
     * <p>三级阈值机制（基于全局池 {@link GlobalAgentPool#dailyMax}）：</p>
     * <ol>
     *   <li><b>预警阈值 (warn)：</b>全局计数 >= dailyMax * warnRatio / 100，仅记录日志提醒</li>
     *   <li><b>紧急阈值 (urgent)：</b>全局计数 >= dailyMax * urgentRatio / 100，提前激活后备接待员</li>
     *   <li><b>日限 (full)：</b>全局计数 >= dailyMax，触发扩容流程：从全局池激活新人，满员员工下码</li>
     * </ol>
     *
     * @param qrCodeId    活码 ID
     * @param userId      员工 userid
     * @param globalCount 该员工今日全局累计添加客户数
     */
    @Transactional
    public void checkAndRotate(Long qrCodeId, String userId, int globalCount) {
        GlobalAgentPool pool = poolRepo.findByAgentUserid(userId).orElse(null);
        if (pool == null) return;

        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null) return;

        int dailyMax = pool.getDailyMax();
        // 防御：dailyMax ≤ 0 表示配置异常，跳过所有阈值检查避免误触发
        if (dailyMax <= 0) {
            log.warn("员工 {} dailyMax={} 异常，跳过阈值检查", userId, dailyMax);
            return;
        }
        int warnThreshold = (dailyMax * qr.getWarnRatio()) / 100;
        int urgentThreshold = (dailyMax * qr.getUrgentRatio()) / 100;

        if (globalCount >= dailyMax) {
            log.warn("员工 {} 全局日限到达 {}/{}，从活码 {} 下码", userId, globalCount, dailyMax, qrCodeId);
            expandQrCodeUsers(qrCodeId, userId, qr, pool);
        } else if (urgentThreshold > 0 && globalCount >= urgentThreshold) {
            log.warn("员工 {} 全局紧急阈值 {}/{}，活码 {} 提前激活后备",
                userId, globalCount, dailyMax, qrCodeId);
            preActivateBackup(qrCodeId, qr);
        } else if (warnThreshold > 0 && globalCount >= warnThreshold) {
            log.info("员工 {} 全局预警阈值 {}/{}", userId, globalCount, dailyMax);
        }
    }

    /**
     * 扩容活码 — 从全局池取接待员加入企微活码，满员员工暂时下码。
     *
     * <p>完整的扩容步骤：</p>
     * <ol>
     *   <li>获取分布式锁，防止并发扩容</li>
     *   <li>人工审核模式跳过</li>
     *   <li>从 {@link GlobalAgentPool} 全局池取待命(standby)的接待员</li>
     *   <li>创建接待员 {@link QrAgent} 记录（日上限沿用全局池预设值）</li>
     *   <li>满员员工标记为 full 状态，记录被谁替换</li>
     *   <li>全局池标记满员员工为 full</li>
     *   <li>事务提交后异步同步企微活码</li>
     *   <li>写入轮换日志</li>
     * </ol>
     *
     * @param qrCodeId   活码 ID
     * @param fullUserId 已满员的员工 userid
     * @param qr         活码实体
     * @param fullPool   已满员员工的全局池记录
     */
    @Transactional
    public void expandQrCodeUsers(Long qrCodeId, String fullUserId,
                                  QrCode qr, GlobalAgentPool fullPool) {
        // 与 preActivateBackup 共用同一把锁，防止并发时重复添加同一员工
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":rotate";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("轮换进行中（扩容等待），跳过: qr={}", qrCodeId);
            return;
        }

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) {
                log.info("人工审核模式，不自动扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }

            // 重入守卫: 如果该员工在此活码上已经下码（被之前的扩容处理过），直接跳过
            // 场景: 同一员工的延迟回调/重推消息在首次扩容完成后到达，dailyCount 可能 > dailyMax
            QrAgent fullAgent = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, fullUserId).orElse(null);
            if (fullAgent == null) {
                log.warn("员工不在活码上，跳过扩容（可能已被手动移除）: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }
            if (fullAgent.getStatus() == QrAgent.AgentStatus.full) {
                log.info("员工已下码，跳过重复扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }

            // 构建排除列表：已在活码上的员工（排除 removed 状态）
            Set<String> excludeUserids = new HashSet<>();
            qrAgentRepo.findByQrCodeId(qrCodeId).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .map(QrAgent::getAgentUserid)
                .forEach(excludeUserids::add);

            // 从全局池取人（自动跳过已在活码上的员工）
            GlobalAgentPool backup = poolService.takeStandby(excludeUserids);
            if (backup == null) {
                log.error("全局池枯竭！活码 {} 无法扩容", qrCodeId);
                alertService.alertEmptyBackup(qrCodeId, qr.getSchoolName());
                return;
            }
            String backupUserid = backup.getAgentUserid();

            // 创建接待员 QrAgent 记录
            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId).agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax())
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active).build();
            qrAgentRepo.save(newAgent);

            // 满员员工下码（fullAgent 在前面已做过 null 检查+return，此处必然非 null）
            fullAgent.setStatus(QrAgent.AgentStatus.full);
            fullAgent.setReplacedBy(backupUserid);
            fullAgent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(fullAgent);

            // 全局池标记满员
            poolService.markFull(fullUserId);

            // 事务提交后异步同步企微（通过代理调用确保 @Async 生效）
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.syncQrCodeToWechatAsync(qrCodeId);
                    }
                });

            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId).toUserid(backupUserid)
                .reason("全局日限到达 — 自动扩容").build());

            log.info("扩容完成: 活码{} 员工{}下码, {}上码", qrCodeId, fullUserId, backupUserid);
        } finally {
            // 仅当锁仍属于当前线程时才释放，防止锁过期后误删其他线程持有的锁
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * 提前激活后备 — 在达到紧急阈值时触发，从全局池取人加入活码。
     *
     * <p>与 {@link #expandQrCodeUsers} 的区别：此方法仅在达到紧急阈值(urgentThreshold)时调用，
     * 目的是提前把接待员加到活码上分流。此时触发员工尚未满员，不会被标记为 full。</p>
     *
     * <p>前置条件检查：如果该活码已有 active 状态的 receptionist 在线，则不重复激活。</p>
     *
     * @param qrCodeId 活码 ID
     * @param qr       活码实体
     */
    @Transactional
    public void preActivateBackup(Long qrCodeId, QrCode qr) {
        // 与 expandQrCodeUsers 共用同一把锁，防止并发时重复添加同一员工
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":rotate";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("轮换进行中（预激活等待），跳过: qr={}", qrCodeId);
            return;
        }

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

            // 构建排除列表：已在活码上的员工
            Set<String> excludeUserids = new HashSet<>();
            qrAgentRepo.findByQrCodeId(qrCodeId).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .map(QrAgent::getAgentUserid)
                .forEach(excludeUserids::add);

            GlobalAgentPool backup = poolService.takeStandby(excludeUserids);
            if (backup == null) {
                log.warn("全局池无 standby，活码 {} 无法预激活", qrCodeId);
                alertService.alertEmptyBackup(qrCodeId, qr.getSchoolName());
                return;
            }
            String backupUserid = backup.getAgentUserid();

            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId).agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax())
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active).build();
            qrAgentRepo.save(newAgent);

            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.syncQrCodeToWechatAsync(qrCodeId);
                    }
                });

            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId).toUserid(backupUserid)
                .reason("全局紧急阈值触发 — 提前激活").build());

            log.info("预激活: 活码{} 加入 {}", qrCodeId, backupUserid);
        } finally {
            // 仅当锁仍属于当前线程时才释放，防止锁过期后误删其他线程持有的锁
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * 异步同步活码用户列表到企业微信（事务提交后触发）。
     *
     * <p>设计意图：将企微 API 调用从回调主线程中移出，避免扩容/预激活时
     * {@code updateContactWay} 的 HTTP 调用阻塞 CallbackWorker。</p>
     *
     * <p>执行时机：通过 {@link TransactionSynchronizationManager#registerSynchronization}
     * 注册 {@link TransactionSynchronization#afterCommit} 回调，确保 DB 事务已提交。</p>
     *
     * <p>注意：此方法必须通过代理引用 {@code self} 调用，确保 {@code @Async} 通过 Spring AOP 生效。
     * 直接 {@code this.syncQrCodeToWechatAsync()} 会绕过代理，在调用线程同步执行。</p>
     *
     * @param qrCodeId 活码 ID
     */
    @Async("taskExecutor")
    public void syncQrCodeToWechatAsync(Long qrCodeId) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null || qr.getQrConfigId() == null) return;

        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qrCodeId);
        List<String> userIds = new ArrayList<>();
        for (QrAgent a : agents) {
            if (a.getStatus() == QrAgent.AgentStatus.active) {
                userIds.add(a.getAgentUserid());
            }
        }

        if (userIds.isEmpty()) {
            log.warn("活码 {} 无可用联系人，跳过异步同步", qr.getQrConfigId());
            return;
        }

        syncQrCodeToWechatWithHealing(qrCodeId, qr.getQrConfigId(), userIds, 0);
    }

    /**
     * Layer 2 自愈（异步版）：同步联系人到企微，遇到 40098/41054 自动定位并替换。
     * 使用 while 循环代替递归，防止重试次数配置错误导致栈溢出。
     */
    private void syncQrCodeToWechatWithHealing(Long qrCodeId, String configId,
                                                List<String> userIds, int startAttempt) {
        int attempt = startAttempt;
        List<String> currentUserIds = new ArrayList<>(userIds);

        while (attempt < 5) {
            if (currentUserIds.isEmpty()) {
                log.warn("活码 {} 无可用联系人，跳过异步同步", configId);
                return;
            }

            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("config_id", configId);
                body.put("user", new ArrayList<>(currentUserIds));
                String json = objectMapper.writeValueAsString(body);
                JsonNode result = wecomApi.updateContactWay(json);
                int errcode = result.has("errcode") ? result.get("errcode").asInt() : -1;

                if (errcode == 0) {
                    log.info("企微活码异步同步完成: config_id={}, users={}", configId, currentUserIds);
                    return;
                }

                // 非可自愈错误：记录告警后放弃
                if (errcode != 40098 && errcode != 41054) {
                    String errmsg = result.has("errmsg") ? result.get("errmsg").asText() : "未知错误";
                    log.error("异步同步企微活码失败(不可自愈): config_id={}, errcode={}, errmsg={}",
                        configId, errcode, errmsg);
                    alertService.createAlert("system", "sync_wecom_fail",
                        AgentAlert.AlertSeverity.high,
                        String.format("企微活码同步失败 config_id=%s errcode=%d errmsg=%s",
                            configId, errcode, errmsg),
                        AgentAlert.AutoAction.none, qrCodeId);
                    return;
                }

                log.error("异步同步企微活码失败 (errcode={}): config_id={}, users={}",
                    errcode, configId, currentUserIds);

                // 二分查找定位不可用用户
                String badUserid = findFailingUser(configId, currentUserIds);
                if (badUserid == null) {
                    log.error("活码 {} 无法定位不可用用户 (errcode={})", configId, errcode);
                    return;
                }

                log.warn("Layer2自愈(异步): 活码 {} 定位到不可用用户 userid={}, errcode={}",
                    configId, badUserid, errcode);

                // ① 从 qr_agent 移除
                qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, badUserid).ifPresent(qa -> {
                    qa.setStatus(QrAgent.AgentStatus.removed);
                    qrAgentRepo.save(qa);
                });

                // ② 封锁 agent 并从全局池移除
                poolService.blockAgentForWechatIssue(badUserid, errcode);

                // ③ 从当前用户列表中移除
                currentUserIds.remove(badUserid);

                // ④ 从全局池选取替代员工
                Set<String> exclude = new HashSet<>(currentUserIds);
                GlobalAgentPool replacement = poolService.takeStandby(exclude);
                if (replacement != null) {
                    currentUserIds.add(replacement.getAgentUserid());
                    int maxOrder = qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId)
                        .stream().mapToInt(QrAgent::getSortOrder).max().orElse(0);
                    qrAgentRepo.save(QrAgent.builder()
                        .qrCodeId(qrCodeId).agentUserid(replacement.getAgentUserid())
                        .role(QrAgent.AgentRole.receptionist)
                        .dailyMax(replacement.getDailyMax())
                        .sortOrder(maxOrder + 1)
                        .status(QrAgent.AgentStatus.active).build());
                    log.info("Layer2自愈(异步): 活码 {} 替代 {} -> {} (attempt={})",
                        qrCodeId, badUserid, replacement.getAgentUserid(), attempt + 1);
                } else {
                    log.warn("Layer2自愈(异步): 活码 {} 无替代员工可用", qrCodeId);
                }

                // ⑤ 创建告警
                alertService.createAlert(badUserid, "wechat_unavailable",
                    AgentAlert.AlertSeverity.medium,
                    String.format("企微不可用员工已被自愈移除(异步): userid=%s errcode=%d 活码=%d 替换=%s",
                        badUserid, errcode, qrCodeId,
                        replacement != null ? replacement.getAgentUserid() : "无"),
                    AgentAlert.AutoAction.removed, qrCodeId);

                attempt++;

            } catch (Exception e) {
                log.error("异步自愈同步异常: config_id={}", configId, e);
                return;
            }
        }

        // 重试耗尽
        log.error("活码 {} 异步自愈重试已达上限({}次)。当前用户: {}",
            configId, attempt, currentUserIds);
        alertService.createAlert("system", "async_sync_heal_exhausted",
            AgentAlert.AlertSeverity.high,
            String.format("活码 %s 异步自愈重试 %d 次后仍失败，需人工介入。当前用户: %s",
                configId, attempt, currentUserIds),
            AgentAlert.AutoAction.none, qrCodeId);
    }

    /**
     * 二分查找定位不可用用户（与 QrCodeService.findFailingUser 相同逻辑）。
     */
    private String findFailingUser(String configId, List<String> userIds) {
        if (userIds.isEmpty()) return null;
        if (userIds.size() == 1) return userIds.get(0);

        List<String> mutable = new ArrayList<>(userIds);
        int left = 0, right = mutable.size();

        while (left + 1 < right) {
            int mid = (left + right) / 2;
            List<String> leftHalf = mutable.subList(left, mid);

            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("config_id", configId);
                body.put("user", new ArrayList<>(leftHalf));
                String json = objectMapper.writeValueAsString(body);
                JsonNode result = wecomApi.updateContactWay(json);
                int errcode = result.has("errcode") ? result.get("errcode").asInt() : -1;

                if (errcode == 40098 || errcode == 41054) {
                    right = mid;
                } else {
                    left = mid;
                }
            } catch (Exception e) {
                log.warn("二分查找 API 异常，退化为线性扫描", e);
                break;
            }
        }

        // 兜底：线性扫描
        for (int i = left; i < Math.min(right, mutable.size()); i++) {
            String uid = mutable.get(i);
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("config_id", configId);
                body.put("user", List.of(uid));
                String json = objectMapper.writeValueAsString(body);
                JsonNode result = wecomApi.updateContactWay(json);
                int errcode = result.has("errcode") ? result.get("errcode").asInt() : -1;
                if (errcode == 40098 || errcode == 41054) {
                    return uid;
                }
            } catch (Exception ex) {
                log.warn("线性扫描查用户异常: userid={}", uid, ex);
            }
        }

        return null;
    }

    /**
     * 获取该活码当前处于 active 状态的接待员数量。
     *
     * <p>用于运营后台展示活码实时负载情况，辅助判断是否需要人工扩容。</p>
     *
     * @param qrCodeId 活码 ID
     * @return active 状态的接待员人数
     */
    public int getActiveReceptionistCount(Long qrCodeId) {
        return qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active).size();
    }

    /**
     * 计算当前时间到次日凌晨 00:00 的剩余秒数。
     *
     * <p>用于 Redis key 的 TTL 设置，确保日计数在第二天自动过期归零。
     * 相比固定 86400 秒，这种方式可以精确对齐到午夜时刻，避免累积误差。</p>
     *
     * @return 距离午夜还剩余的秒数
     */
    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Math.max(Duration.between(now, midnight).getSeconds(), 60);
    }
}
