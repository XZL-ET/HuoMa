package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 代理轮换服务 — 轮换/扩容逻辑，从原 AgentBindService 拆分。
 *
 * <p>消除 @Lazy @Autowired self 自注入：@Async 方法通过注入
 * WechatSyncHealingService 间接调用企微同步。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRotationService {

    private final StringRedisTemplate redisTemplate;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final GlobalAgentPoolRepository poolRepo;
    private final GlobalAgentPoolService poolService;
    private final AgentDailyCountService countService;
    private final WechatSyncHealingService healingService;
    private final AlertService alertService;

    // ==================== 日计数 + 轮换入口 ====================

    /**
     * 员工全局日计数 +1，随后检查阈值触发轮换。
     *
     * <p>使用 Redis INCR 命令实现原子递增。同时维护活码级别和全局级别两个计数器。
     * 递增后通过调用 checkAndRotate 触发阈值检查与自动轮换。</p>
     *
     * @param userId  企微员工 userid
     * @param state   活码 state 参数，用于反查 QrCode 记录
     */
    public void incrementDailyCount(String userId, String state) {
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) return;

        // 活码维度：通过 QrAgent ID 使用 AgentDailyCountService
        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);

        // 全局维度 key：该员工在所有活码下的今日总添加量
        String totalKey = RedisConfig.AGENT_DAILY_TOTAL_PREFIX + userId;
        long ttl = Math.max(getSecondsUntilMidnight(), 60);

        // Lua 脚本保证 INCR + EXPIRE 原子执行
        String lua = "local v = redis.call('INCR', KEYS[1]) "
                   + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                   + "return v";
        DefaultRedisScript<Long> incrScript = new DefaultRedisScript<>(lua, Long.class);
        long totalNew = redisTemplate.execute(incrScript, List.of(totalKey), String.valueOf(ttl));

        // 活码维度计数（通过 AgentDailyCountService）
        if (qa != null) {
            long newCount = countService.incrementDailyCount(qa.getId(), 1);
            // 同步到 DB
            try {
                qa.setDailyCurrent((int) newCount);
                qrAgentRepo.save(qa);
            } catch (Exception e) {
                log.error("同步 qr_agent.daily_current 失败: userId={}, qrId={}, count={}",
                    userId, qr.getId(), newCount, e);
            }
        }

        // 同步全局计数到 DB
        try {
            poolService.updateDailyCurrent(userId, (int) totalNew);
        } catch (Exception e) {
            log.error("同步 global_agent_pool.daily_current 失败: userId={}, count={}",
                userId, totalNew, e);
        }

        // 递增后立即检查全局阈值
        checkAndRotate(qr.getId(), userId, (int) totalNew);
    }

    // ==================== 日限检查 + 轮换 ====================

    /**
     * 检查员工全局日限并触发轮换。
     *
     * <p>三级阈值机制（基于全局池 GlobalAgentPool.dailyMax）：
     * <ol>
     *   <li><b>预警阈值 (warn)：</b>全局计数 >= dailyMax * warnRatio / 100，仅记录日志</li>
     *   <li><b>紧急阈值 (urgent)：</b>全局计数 >= dailyMax * urgentRatio / 100，提前激活后备</li>
     *   <li><b>日限 (full)：</b>全局计数 >= dailyMax，触发扩容流程</li>
     * </ol>
     */
    @Transactional
    public void checkAndRotate(Long qrCodeId, String userId, int globalCount) {
        GlobalAgentPool pool = poolRepo.findByAgentUserid(userId).orElse(null);
        if (pool == null) return;

        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null) return;

        int dailyMax = pool.getDailyMax();
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
     */
    @Transactional
    public void expandQrCodeUsers(Long qrCodeId, String fullUserId,
                                  QrCode qr, GlobalAgentPool fullPool) {
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

            QrAgent fullAgent = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, fullUserId).orElse(null);
            if (fullAgent == null) {
                log.warn("员工不在活码上，跳过扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }
            if (fullAgent.getStatus() == QrAgent.AgentStatus.full) {
                log.info("员工已下码，跳过重复扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }

            // 构建排除列表
            Set<String> excludeUserids = new HashSet<>();
            qrAgentRepo.findByQrCodeId(qrCodeId).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .map(QrAgent::getAgentUserid)
                .forEach(excludeUserids::add);

            GlobalAgentPool backup = poolService.takeStandby(excludeUserids, qr.getDepartmentId());
            if (backup == null) {
                log.error("全局池枯竭！活码 {} 无法扩容", qrCodeId);
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

            fullAgent.setStatus(QrAgent.AgentStatus.full);
            fullAgent.setReplacedBy(backupUserid);
            fullAgent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(fullAgent);

            poolService.markFull(fullUserId);

            // 事务提交后异步同步企微（委托给 WechatSyncHealingService）
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        syncQrCodeToWechatAsync(qrCodeId);
                    }
                });

            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId).toUserid(backupUserid)
                .reason("全局日限到达 — 自动扩容").build());

            log.info("扩容完成: 活码{} 员工{}下码, {}上码", qrCodeId, fullUserId, backupUserid);
        } finally {
            Long unlockResult = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(lockKey), lockValue);
            if (unlockResult != null && unlockResult == 1) {
                log.debug("分布式锁安全释放: {}", lockKey);
            } else {
                log.warn("分布式锁释放失败（已过期或被他人持有）: {}", lockKey);
            }
        }
    }

    /**
     * 提前激活后备 — 在达到紧急阈值时触发，从全局池取人加入活码。
     */
    @Transactional
    public void preActivateBackup(Long qrCodeId, QrCode qr) {
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

            Set<String> excludeUserids = new HashSet<>();
            qrAgentRepo.findByQrCodeId(qrCodeId).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .map(QrAgent::getAgentUserid)
                .forEach(excludeUserids::add);

            GlobalAgentPool backup = poolService.takeStandby(excludeUserids, qr.getDepartmentId());
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
                        syncQrCodeToWechatAsync(qrCodeId);
                    }
                });

            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId).toUserid(backupUserid)
                .reason("全局紧急阈值触发 — 提前激活").build());

            log.info("预激活: 活码{} 加入 {}", qrCodeId, backupUserid);
        } finally {
            Long unlockResult = redisTemplate.execute(
                RedisConfig.SAFE_UNLOCK_SCRIPT,
                List.of(lockKey), lockValue);
            if (unlockResult != null && unlockResult == 1) {
                log.debug("分布式锁安全释放: {}", lockKey);
            } else {
                log.warn("分布式锁释放失败（已过期或被他人持有）: {}", lockKey);
            }
        }
    }

    /**
     * 异步同步活码用户列表到企业微信（事务提交后触发）。
     *
     * <p>委托给 WechatSyncHealingService，消除 @Lazy self 自注入。</p>
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

        // 委托给 WechatSyncHealingService
        WechatSyncHealingService.SyncResult result =
            healingService.syncWithHealing(qrCodeId, userIds, "agent-rotation");

        // 自愈移除不可用成员后，从全局池补充替补
        if (result.needReplacement) {
            healingService.supplementReplacement(qrCodeId);
        }
    }

    /**
     * 获取该活码当前处于 active 状态的接待员数量。
     */
    public int getActiveReceptionistCount(Long qrCodeId) {
        return qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active).size();
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Math.max(Duration.between(now, midnight).getSeconds(), 60);
    }
}
