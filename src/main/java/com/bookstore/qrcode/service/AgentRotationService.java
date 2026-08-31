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
 * <p>@Async 方法通过注入 WechatSyncHealingService 间接调用企微同步
 * （消除 @Async 的 self 自注入）；事务入口通过 @Lazy self 代理调用
 * （修复 incrementDailyCount 自调用绕过 @Transactional）。</p>
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

    // 自注入代理：解决 incrementDailyCount → checkAndRotate 的 @Transactional 自调用失效问题
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AgentRotationService self;

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
        self.checkAndRotate(qr.getId(), userId, (int) totalNew);
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
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null) return;

        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, userId).orElse(null);
        if (qa == null) return;

        boolean isService = qa.getRole() == QrAgent.AgentRole.service
            || qa.getRole() == QrAgent.AgentRole.dual;

        GlobalAgentPool pool = poolRepo.findByAgentUserid(userId).orElse(null);

        // 日限判定：
        // - 服务老师/双角色不入全局池，用活码级日限（serviceDailyMax 优先，其次 dailyMax）
        // - 普通接待员在池用全局池日限（跨活码合计，匹配企微实际限制）
        // - 普通接待员不在池（角色漂移被清池）用活码级日限兜底，避免因不在池而永不补人
        int dailyMax;
        if (isService) {
            dailyMax = resolveServiceDailyMax(qa);
        } else if (pool != null) {
            dailyMax = pool.getDailyMax();
        } else {
            dailyMax = qa.getDailyMax() != null ? qa.getDailyMax() : 0;
        }

        if (dailyMax <= 0) {
            log.warn("员工 {} dailyMax={} 异常，跳过阈值检查: qr={}", userId, dailyMax, qrCodeId);
            return;
        }

        int warnThreshold = (dailyMax * qr.getWarnRatio()) / 100;
        int urgentThreshold = (dailyMax * qr.getUrgentRatio()) / 100;

        if (globalCount >= dailyMax) {
            if (isService) {
                log.warn("服务老师/双角色 {} 日限到达 {}/{}，下码停止扫码承接（继承照常）: qr={}",
                    userId, globalCount, dailyMax, qrCodeId);
                downCodeServiceTeacher(qrCodeId, userId, qr, qa);
            } else {
                log.warn("员工 {} 日限到达 {}/{}，从活码 {} 下码",
                    userId, globalCount, dailyMax, qrCodeId);
                expandQrCodeUsers(qrCodeId, userId, qr, pool);
            }
        } else if (urgentThreshold > 0 && globalCount >= urgentThreshold) {
            log.warn("员工 {} 紧急阈值 {}/{}，活码 {} 提前激活后备",
                userId, globalCount, dailyMax, qrCodeId);
            preActivateBackup(qrCodeId, qr);
        } else if (warnThreshold > 0 && globalCount >= warnThreshold) {
            log.info("员工 {} 预警阈值 {}/{}", userId, globalCount, dailyMax);
        }
    }

    /**
     * 服务老师/双角色的活码级日限：优先 {@code serviceDailyMax}，其次 {@code dailyMax}。
     * 服务老师不入全局池，无法用池的 {@code dailyMax} 判定，只能取活码级配置。
     */
    private int resolveServiceDailyMax(QrAgent qa) {
        if (qa.getServiceDailyMax() != null && qa.getServiceDailyMax() > 0) {
            return qa.getServiceDailyMax();
        }
        return qa.getDailyMax() != null ? qa.getDailyMax() : 0;
    }

    /**
     * 服务老师/双角色下码前的兜底补员。
     *
     * <p>企微「联系我」二维码（contact_way，type=2 多成员模式）的 {@code user} 列表
     * 不能为空（至少 1 人）。若该服务老师是活码上唯一的 active 成员，直接下码会让
     * contact_way 变空。故先从同部门补一名接待员上码，再下码。</p>
     *
     * @return {@code true} 可安全下码（活码已有其他 active 成员，或补员成功）；
     *         {@code false} 补员失败（全局池枯竭），调用方应保持服务老师 active 不下码
     */
    private boolean supplementBeforeServiceDownCode(Long qrCodeId, String userId, QrCode qr) {
        List<QrAgent> activeOthers = qrAgentRepo
            .findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active).stream()
            .filter(a -> !a.getAgentUserid().equals(userId))
            .toList();
        if (!activeOthers.isEmpty()) {
            return true;
        }

        Set<String> excludeUserids = new HashSet<>();
        qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .map(QrAgent::getAgentUserid)
            .forEach(excludeUserids::add);

        // 同部门优先：取「服务老师本人」所在部门，而非活码部门；无则回退活码部门
        Long deptId = poolService.resolvePrimaryDepartmentId(userId);
        if (deptId == null) {
            deptId = qr.getDepartmentId();
        }
        GlobalAgentPool backup = poolService.takeStandby(excludeUserids, deptId);
        if (backup == null) {
            log.error("服务老师 {} 下码前补员失败：全局池枯竭，活码 {} 保持 active 不下码",
                userId, qrCodeId);
            alertService.alertEmptyBackup(qrCodeId, qr.getSchoolName());
            return false;
        }

        String backupUserid = backup.getAgentUserid();
        QrAgent newAgent = QrAgent.builder()
            .qrCodeId(qrCodeId).agentUserid(backupUserid)
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(backup.getDailyMax())
            .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
            .status(QrAgent.AgentStatus.active)
            .temporary(true)
            .build();
        qrAgentRepo.save(newAgent);

        rotateLogRepo.save(QrRotateLog.builder()
            .qrCodeId(qrCodeId).toUserid(backupUserid)
            .reason("服务老师下码前同部门补员").build());

        log.info("服务老师 {} 下码前补员(临时): 活码{} 加入 {}", userId, qrCodeId, backupUserid);
        return true;
    }

    /**
     * 服务老师/双角色日限下码（继承照常），带分布式锁防并发重复下码。
     *
     * <p>与 {@link #expandQrCodeUsers} 对称地使用 {@code :rotate} 锁，避免并发回调
     * 同时读到 {@code active} 状态、重复补员。下码前先调用
     * {@link #supplementBeforeServiceDownCode} 兜底补员，补员失败（全局池枯竭）则
     * 保持 active 不下码，避免 contact_way 变空。</p>
     */
    private void downCodeServiceTeacher(Long qrCodeId, String userId, QrCode qr, QrAgent qa) {
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":rotate";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("轮换进行中（服务老师下码等待），跳过: qr={}", qrCodeId);
            return;
        }

        try {
            if (qa.getStatus() == QrAgent.AgentStatus.full) {
                log.debug("服务老师 {} 已下码，跳过重复处理: qr={}", userId, qrCodeId);
                return;
            }
            if (!supplementBeforeServiceDownCode(qrCodeId, userId, qr)) {
                log.warn("服务老师 {} 下码前补员失败（全局池枯竭），保持 active 不下码: qr={}",
                    userId, qrCodeId);
                return;
            }
            qa.setStatus(QrAgent.AgentStatus.full);
            qa.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(qa);
            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId).fromUserid(userId)
                .reason("服务老师日限下码").build());
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        syncQrCodeToWechatAsync(qrCodeId);
                    }
                });
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
            // 服务老师/双角色不参与轮换，不应走到这里（checkAndRotate 已拦截），防御兜底
            if (fullAgent.getRole() == QrAgent.AgentRole.service
                || fullAgent.getRole() == QrAgent.AgentRole.dual) {
                log.warn("expandQrCodeUsers 被服务老师/双角色触发（不应发生），跳过: qr={}, user={}",
                    qrCodeId, fullUserId);
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
                .role(fullAgent.getRole()) // 跟随被替换员工的角色
                .dailyMax(backup.getDailyMax())
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active)
                .temporary(Boolean.TRUE.equals(fullAgent.getTemporary())) // 临时顶替满员，接替者继承临时性，次日一并释放
                .build();
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
                .qrCodeId(qrCodeId).fromUserid(fullUserId).toUserid(backupUserid)
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
     *
     * <p><b>幂等性保护：</b>使用 Redis key {@code preactivate:done:{qrCodeId}}
     * 确保每个活码每天最多触发一次预激活，防止接待员无限堆积。
     * Key 的 TTL 为距离当日午夜的秒数，次日自动失效。</p>
     */
    @Transactional
    public void preActivateBackup(Long qrCodeId, QrCode qr) {
        // ── 幂等性保护：当天已预激活过则跳过 ──
        String doneKey = RedisConfig.PREACTIVATE_DONE_PREFIX + qrCodeId;
        long ttlSeconds = getSecondsUntilMidnight();
        Boolean firstAttempt = redisTemplate.opsForValue()
            .setIfAbsent(doneKey, "1", Duration.ofSeconds(ttlSeconds));
        if (Boolean.FALSE.equals(firstAttempt)) {
            log.debug("当天已预激活，跳过: qr={}", qrCodeId);
            return;
        }

        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":rotate";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("轮换进行中（预激活等待），跳过: qr={}", qrCodeId);
            // 并发冲突时删除幂等标记，允许后续回调重试
            redisTemplate.delete(doneKey);
            return;
        }

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) {
                // manual 模式下不回退 preactivate 标记 -
                // 管理员明确关闭自动轮换，标记应该保留到明天
                return;
            }

            Set<String> excludeUserids = new HashSet<>();
            qrAgentRepo.findByQrCodeId(qrCodeId).stream()
                .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                .map(QrAgent::getAgentUserid)
                .forEach(excludeUserids::add);

            GlobalAgentPool backup = poolService.takeStandby(excludeUserids, qr.getDepartmentId());
            if (backup == null) {
                log.warn("全局池无 standby，活码 {} 无法预激活", qrCodeId);
                alertService.alertEmptyBackup(qrCodeId, qr.getSchoolName());
                // 池空时删除标记，等池恢复后可以重试
                redisTemplate.delete(doneKey);
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
