package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
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
 *   <li><b>每日重置：</b>凌晨 00:00 全局池 full→standby，所有 full 员工恢复为 active，清零日计数，重新上活码</li>
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
    /** 自身代理引用 — 确保 @Async 通过 Spring AOP 代理生效 */
    private final AgentBindService self;

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

        long newCount = redisTemplate.opsForValue().increment(key);
        long totalNew = redisTemplate.opsForValue().increment(totalKey);

        // 设置过期时间为次日凌晨 00:00，确保日计数每天自动归零
        // 选用 TTL 自过期而非定时任务清理，降低实现复杂度
        redisTemplate.expire(key, getSecondsUntilMidnight(), TimeUnit.SECONDS);
        redisTemplate.expire(totalKey, getSecondsUntilMidnight(), TimeUnit.SECONDS);

        // 同步到 DB：更新 qr_agent.daily_current 字段，用于系统重启后恢复计数
        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);
        if (qa != null) {
            qa.setDailyCurrent((int) newCount);
            qrAgentRepo.save(qa);
        }
        // 同步全局池的 daily_current
        poolService.updateDailyCurrent(userId, (int) totalNew);

        // 递增后立即检查全局阈值，若达到日限则触发轮换
        checkAndRotate(qr.getId(), userId, (int) totalNew);
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
        int warnThreshold = (dailyMax * qr.getWarnRatio()) / 100;
        int urgentThreshold = (dailyMax * qr.getUrgentRatio()) / 100;

        if (globalCount >= dailyMax) {
            log.warn("员工 {} 全局日限到达 {}/{}，从活码 {} 下码", userId, globalCount, dailyMax, qrCodeId);
            expandQrCodeUsers(qrCodeId, userId, qr, pool);
        } else if (globalCount >= urgentThreshold) {
            log.warn("员工 {} 全局紧急阈值 {}/{}，活码 {} 提前激活后备",
                userId, globalCount, dailyMax, qrCodeId);
            preActivateBackup(qrCodeId, qr);
        } else if (globalCount >= warnThreshold) {
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
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":expand";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("扩容进行中，跳过: qr={}", qrCodeId);
            return;
        }

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) {
                log.info("人工审核模式，不自动扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }

            // 从全局池取人
            GlobalAgentPool backup = poolService.takeStandby();
            if (backup == null) {
                log.error("全局池枯竭！活码 {} 无法扩容", qrCodeId);
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

            // 满员员工下码
            QrAgent fullAgent = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, fullUserId).orElse(null);
            if (fullAgent != null) {
                fullAgent.setStatus(QrAgent.AgentStatus.full);
                fullAgent.setReplacedBy(backupUserid);
                fullAgent.setUpdatedAt(LocalDateTime.now());
                qrAgentRepo.save(fullAgent);
            }

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
            redisTemplate.delete(lockKey);
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
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":preactivate";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) return;

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

            long activeReceptionists = qrAgentRepo
                .findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active)
                .stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).count();
            if (activeReceptionists > 0) return;

            GlobalAgentPool backup = poolService.takeStandby();
            if (backup == null) {
                log.warn("全局池无 standby，活码 {} 无法预激活", qrCodeId);
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
            redisTemplate.delete(lockKey);
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
        Set<String> userIds = new LinkedHashSet<>();

        // receptionist 角色全部按 active 状态过滤
        for (QrAgent a : agents) {
            if (a.getStatus() == QrAgent.AgentStatus.active) {
                userIds.add(a.getAgentUserid());
            }
        }

        if (userIds.isEmpty()) {
            log.warn("活码 {} 无可用联系人，跳过异步同步", qr.getQrConfigId());
            return;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("config_id", qr.getQrConfigId());
            body.put("user", new ArrayList<>(userIds));
            String json = objectMapper.writeValueAsString(body);
            wecomApi.updateContactWay(json);
            log.info("企微活码异步同步完成: config_id={}, users={}", qr.getQrConfigId(), userIds);
        } catch (Exception e) {
            log.error("异步同步企微活码失败: config_id={}", qr.getQrConfigId(), e);
        }
    }

    // ==================== 每日重置 ====================

    /**
     * 每日 00:00 重置所有员工的每日计数，恢复过期绑定关系。
     *
     * <p>每日重置是轮换机制的闭环操作：</p>
     * <ol>
     *   <li>全局池恢复：将所有 full 员工恢复为 standby，清零日计数</li>
     *   <li>QrAgent 恢复：将所有 full 状态员工恢复为 active，清零日计数</li>
     *   <li>异步同步所有受影响的活码到企微</li>
     * </ol>
     *
     * <p>由 {@link com.bookstore.qrcode.worker.DailyResetWorker} 定时调度执行。</p>
     */
    @Transactional
    public void dailyReset() {
        // 全局池恢复 full → standby
        poolService.dailyReset();

        // QrAgent 恢复 full → active
        List<QrAgent> fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        for (QrAgent qa : fullAgents) {
            qa.setStatus(QrAgent.AgentStatus.active);
            qa.setDailyCurrent(0);
            qa.setLastResetAt(LocalDateTime.now());
            qrAgentRepo.save(qa);
        }
        log.info("每日重置: 恢复 {} 个 full 员工", fullAgents.size());

        // 同步所有受影响的活码
        fullAgents.stream()
            .map(QrAgent::getQrCodeId)
            .distinct()
            .forEach(qrCodeId ->
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            self.syncQrCodeToWechatAsync(qrCodeId);
                        }
                    }));
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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Duration.between(now, midnight).getSeconds();
    }
}
