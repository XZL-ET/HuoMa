package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 员工绑定 + 日限额 + 自动轮换 + 后备池激活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBindService {

    private final QrAgentRepository qrAgentRepo;
    private final QrBackupPoolRepository backupPoolRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final StringRedisTemplate redisTemplate;

    // ==================== 日计数 ====================

    /**
     * 员工在指定活码下今日添加数 +1（Redis INCR，原子操作）。
     */
    public void incrementDailyCount(String userId, String state) {
        // 根据 state 找到活码
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) return;

        String key = RedisConfig.AGENT_DAILY_KEY_PREFIX + userId + ":" + qr.getId();
        String totalKey = RedisConfig.AGENT_DAILY_TOTAL_PREFIX + userId;

        long newCount = redisTemplate.opsForValue().increment(key);
        long totalNew = redisTemplate.opsForValue().increment(totalKey);

        // 设置过期时间：到明天凌晨
        redisTemplate.expire(key, getSecondsUntilMidnight(), TimeUnit.SECONDS);
        redisTemplate.expire(totalKey, getSecondsUntilMidnight(), TimeUnit.SECONDS);

        // 同步到 DB（更新 qr_agent.daily_current）
        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);
        if (qa != null) {
            qa.setDailyCurrent((int) newCount);
            qrAgentRepo.save(qa);
        }

        // 检查是否达到日限
        checkAndRotate(qr.getId(), userId, (int) newCount);
    }

    // ==================== 日限检查 + 轮换 ====================

    /**
     * 检查日限 → 触发轮换。
     */
    @Transactional
    public void checkAndRotate(Long qrCodeId, String userId, int currentCount) {
        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, userId).orElse(null);
        if (qa == null || qa.getDailyMax() == null) return;

        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null) return;

        int dailyMax = qa.getDailyMax();
        int warnThreshold = (dailyMax * qr.getWarnRatio()) / 100;
        int urgentThreshold = (dailyMax * qr.getUrgentRatio()) / 100;

        // 多级预警
        if (currentCount >= dailyMax) {
            // 满员 → 从活码移除 + 通知
            log.warn("员工 {} 在活码 {} 已达日限 {}，触发轮换", userId, qrCodeId, dailyMax);
            rotateOut(qrCodeId, userId, qr);
        } else if (currentCount >= urgentThreshold) {
            log.warn("员工 {} 在活码 {} 达到紧急阈值 {}/{}", userId, qrCodeId, currentCount, dailyMax);
            // 告警，但不移除
        } else if (currentCount >= warnThreshold) {
            log.info("员工 {} 在活码 {} 达到预警阈值 {}/{}", userId, qrCodeId, currentCount, dailyMax);
        }
    }

    /**
     * 将员工从活码移除，从后备池激活替代。
     */
    @Transactional
    public void rotateOut(Long qrCodeId, String userId, QrCode qr) {
        // 分布式锁，防止并发轮换
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":" + userId;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("轮换进行中，跳过: qr={}, user={}", qrCodeId, userId);
            return;
        }

        try {
            // ① 员工标记为 full
            QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, userId).orElse(null);
            if (qa == null || qa.getStatus() == QrAgent.AgentStatus.full) return;

            // 人工审核模式 → 只告警不轮换
            if (qr.getRotateMode() == QrCode.RotateMode.manual) {
                log.info("人工审核模式，不自动轮换: qr={}, user={}", qrCodeId, userId);
                return;
            }

            qa.setStatus(QrAgent.AgentStatus.full);
            qa.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(qa);

            // ② 从后备池取第一个待命的
            List<QrBackupPool> backups = backupPoolRepo
                .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);
            if (backups.isEmpty()) {
                // 后备池空 → 活码标记无可用
                log.error("活码 {} 后备池为空，无可用员工！", qrCodeId);
                qr.setStatus(QrCode.QrCodeStatus.no_agent);
                qrCodeRepo.save(qr);
                return;
            }

            QrBackupPool backup = backups.get(0);
            backup.setStatus(QrBackupPool.PoolStatus.activated);
            backup.setUpdatedAt(LocalDateTime.now());
            backupPoolRepo.save(backup);

            // ③ 后备员工加入活码（auto = 从被替换者的 dailyMax 继承）
            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(backup.getAgentUserid())
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(qa.getDailyMax())
                .sortOrder(qa.getSortOrder())
                .status(QrAgent.AgentStatus.active)
                .replacedBy(null)
                .build();
            qrAgentRepo.save(newAgent);

            // ④ 记录替换关系
            qa.setReplacedBy(backup.getAgentUserid());
            qrAgentRepo.save(qa);

            log.info("轮换完成: 活码 {} 员工 {}→{}", qrCodeId, userId, backup.getAgentUserid());

            // ⑤ 重建活码状态
            long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active).size();
            if (activeCount > 0) {
                qr.setStatus(QrCode.QrCodeStatus.active);
                qrCodeRepo.save(qr);
            }

        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    // ==================== 每日重置 ====================

    /**
     * 每日 00:00 重置所有员工的每日计数。
     */
    @Transactional
    public void dailyReset() {
        // 清空 Redis 中的当日计数（由 DailyResetWorker 处理）
        // 恢复所有 full 状态的员工
        List<QrAgent> fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        for (QrAgent qa : fullAgents) {
            qa.setStatus(QrAgent.AgentStatus.active);
            qa.setDailyCurrent(0);
            qa.setLastResetAt(LocalDateTime.now());
            qrAgentRepo.save(qa);
        }
        log.info("每日重置: 恢复 {} 个 full 员工", fullAgents.size());

        // 恢复被封号/熔断的员工不处理（需要人工确认）
        // 后备池中 activated 的回退到 standby
    }

    /**
     * 获取该活码当前可用的接待员数。
     */
    public int getActiveReceptionistCount(Long qrCodeId) {
        return qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active).size();
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Duration.between(now, midnight).getSeconds();
    }
}
