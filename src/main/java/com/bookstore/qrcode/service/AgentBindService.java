package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
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
    private final QrRotateLogRepository rotateLogRepo;
    private final StringRedisTemplate redisTemplate;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

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
     * 新逻辑：服务老师为主联系人，满了从后备池激活接待员扩容活码。
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

        if (currentCount >= dailyMax) {
            // 满员 → 扩容：从后备池加人，服务老师保留
            log.warn("员工 {} 在活码 {} 已达日限 {}，触发扩容", userId, qrCodeId, dailyMax);
            expandQrCodeUsers(qrCodeId, userId, qr, qa);
        } else if (currentCount >= urgentThreshold) {
            // 紧急阈值 → 提前激活后备（不等满了再抢）
            log.warn("员工 {} 在活码 {} 达到紧急阈值 {}/{}，提前激活后备",
                userId, qrCodeId, currentCount, dailyMax);
            preActivateBackup(qrCodeId, qr);
        } else if (currentCount >= warnThreshold) {
            log.info("员工 {} 在活码 {} 达到预警阈值 {}/{}", userId, qrCodeId, currentCount, dailyMax);
        }
    }

    /**
     * 扩容活码 — 从后备池激活接待员加入企微活码，满员员工暂时下码。
     * 日重置后满员员工自动恢复并重新上码。
     */
    @Transactional
    public void expandQrCodeUsers(Long qrCodeId, String fullUserId, QrCode qr, QrAgent fullAgent) {
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":expand";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("扩容进行中，跳过: qr={}", qrCodeId);
            return;
        }

        try {
            // 人工审核模式 → 只告警不自动
            if (qr.getRotateMode() == QrCode.RotateMode.manual) {
                log.info("人工审核模式，不自动扩容: qr={}, user={}", qrCodeId, fullUserId);
                return;
            }

            // 从后备池取待命的接待员
            List<QrBackupPool> backups = backupPoolRepo
                .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);
            if (backups.isEmpty()) {
                log.error("活码 {} 后备池为空，无法扩容！", qrCodeId);
                return;
            }

            QrBackupPool backup = backups.get(0);
            String backupUserid = backup.getAgentUserid();

            // ① 标记后备已激活
            backup.setStatus(QrBackupPool.PoolStatus.activated);
            backup.setUpdatedAt(LocalDateTime.now());
            backupPoolRepo.save(backup);

            // ② 创建接待员 QrAgent 记录（使用后备池中预设的日上限）
            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax() != null ? backup.getDailyMax() : 200)
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active)
                .build();
            qrAgentRepo.save(newAgent);

            // ③ 满员员工标记为 full（包括服务老师），从企微活码移除
            //    日重置后会恢复为 active 并重新上活码
            fullAgent.setStatus(QrAgent.AgentStatus.full);
            fullAgent.setReplacedBy(backupUserid);
            fullAgent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(fullAgent);

            // ④ 更新企微活码 — 把新接待员加入用户列表
            syncQrUsersToWechat(qr, fullUserId, backupUserid);

            // ⑤ 写轮换日志
            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId)
                .toUserid(backupUserid)
                .reason("日限到达 — 自动扩容")
                .build());

            log.info("扩容完成: 活码 {} 服务老师 {} 满了，激活后备 {}",
                qrCodeId, fullUserId, backupUserid);

        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 提前激活后备 — 在紧急阈值触发，不等满员。
     */
    @Transactional
    public void preActivateBackup(Long qrCodeId, QrCode qr) {
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":preactivate";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) return;

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

            // 检查是否已有接待员激活了
            long activeReceptionists = qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active)
                .stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).count();
            if (activeReceptionists > 0) return; // 已有接待员，不重复激活

            List<QrBackupPool> backups = backupPoolRepo
                .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);
            if (backups.isEmpty()) return;

            QrBackupPool backup = backups.get(0);
            String backupUserid = backup.getAgentUserid();

            // 获取服务老师的 userid
            QrAgent svcAgent = qrAgentRepo.findByQrCodeIdAndRole(qrCodeId, QrAgent.AgentRole.service)
                .stream().findFirst().orElse(null);
            String svcUserid = svcAgent != null ? svcAgent.getAgentUserid() : null;

            backup.setStatus(QrBackupPool.PoolStatus.activated);
            backup.setUpdatedAt(LocalDateTime.now());
            backupPoolRepo.save(backup);

            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax() != null ? backup.getDailyMax() : 200)
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active)
                .build();
            qrAgentRepo.save(newAgent);

            // 更新企微活码用户列表
            if (svcUserid != null) {
                syncQrUsersToWechat(qr, svcUserid, backupUserid);
            }

            // 写轮换日志
            rotateLogRepo.save(QrRotateLog.builder()
                .qrCodeId(qrCodeId)
                .toUserid(backupUserid)
                .reason("紧急阈值触发 — 提前激活后备")
                .build());

            log.info("提前激活后备: 活码 {} 加入接待员 {}", qrCodeId, backupUserid);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 同步活码用户列表到企微。
     * 服务老师始终保留；接待员只上 active 状态的。
     */
    private void syncQrUsersToWechat(QrCode qr, String fullUserId, String newUserId) {
        try {
            Set<String> userIds = new LinkedHashSet<>();

            // 服务老师只在 active 状态时上活码（满了就暂时下码）
            List<QrAgent> allAgents = qrAgentRepo.findByQrCodeId(qr.getId());
            for (QrAgent a : allAgents) {
                if (a.getRole() == QrAgent.AgentRole.service
                    && a.getStatus() == QrAgent.AgentStatus.active) {
                    userIds.add(a.getAgentUserid());
                }
            }

            // 接待员只取 active 状态
            for (QrAgent a : allAgents) {
                if (a.getRole() != QrAgent.AgentRole.service
                    && a.getStatus() == QrAgent.AgentStatus.active) {
                    userIds.add(a.getAgentUserid());
                }
            }

            if (userIds.isEmpty()) {
                log.warn("活码 {} 无可用联系人，跳过同步", qr.getQrConfigId());
                return;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("config_id", qr.getQrConfigId());
            body.put("user", new ArrayList<>(userIds));
            String json = objectMapper.writeValueAsString(body);

            wecomApi.updateContactWay(json);
            log.info("企微活码用户列表已同步: config_id={}, users={}", qr.getQrConfigId(), userIds);
        } catch (Exception e) {
            log.error("同步企微活码用户列表失败: config_id={}", qr.getQrConfigId(), e);
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
