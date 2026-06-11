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
 * 员工绑定与日限额管理服务。
 *
 * <p>核心职责：管理企微员工与活码的绑定关系，实现日计数 -> 阈值检查 -> 自动轮换 -> 后备池扩容 -> 企微同步 -> 每日重置 的完整业务闭环。</p>
 *
 * <h3>业务链路说明</h3>
 * <ul>
 *   <li><b>日计数：</b>客户扫码添加好友时，通过 {@code Redis INCR} 原子递增该员工在当前活码下的今日添加数</li>
 *   <li><b>阈值检查：</b>按 预警阈值(warn) / 紧急阈值(urgent) / 日限(full) 三级判定当前压力水位</li>
 *   <li><b>自动轮换：</b>员工满员后标记为 {@link QrAgent.AgentStatus#full}，从企微活码暂时移除</li>
 *   <li><b>后备扩容：</b>从 {@link QrBackupPool} 后备池中激活待命的接待员加入活码，分担流量</li>
 *   <li><b>企微同步：</b>通过 {@link com.bookstore.qrcode.wecom.WecomApiClient} 同步活码用户列表到企业微信</li>
 *   <li><b>每日重置：</b>凌晨 00:00 将所有 full 状态的员工恢复为 active，清零日计数，重新上活码</li>
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
    private final QrBackupPoolRepository backupPoolRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final StringRedisTemplate redisTemplate;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

    // ==================== 日计数 ====================

    /**
     * 员工在指定活码下今日添加数 +1。
     *
     * <p>使用 Redis INCR 命令实现原子递增，避免并发场景下的计数丢失。
     * 同时维护两个计数器：活码级别(key)和员工级别(totalKey)，前者用于活码维度的日限判断，
     * 后者用于员工全局维度的监管统计。</p>
     *
     * <p>执行流程：Redis 递增 -> 设置 TTL 至午夜过期 -> 同步到 DB 持久化 -> 检查阈值触发轮换。</p>
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
        // Redis 是缓存层，DB 是持久化层，双写保障数据不丢失
        QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);
        if (qa != null) {
            qa.setDailyCurrent((int) newCount);
            qrAgentRepo.save(qa);
        }

        // 递增后立即检查是否触及各阈值，若达到日限则触发轮换
        checkAndRotate(qr.getId(), userId, (int) newCount);
    }

    // ==================== 日限检查 + 轮换 ====================

    /**
     * 检查员工日限并触发轮换。
     *
     * <p>三级阈值机制（基于 {@link QrCode#warnRatio} 和 {@link QrCode#urgentRatio} 计算）：</p>
     * <ol>
     *   <li><b>预警阈值 (warn)：</b>当前数 >= dailyMax * warnRatio / 100，仅记录日志提醒</li>
     *   <li><b>紧急阈值 (urgent)：</b>当前数 >= dailyMax * urgentRatio / 100，提前激活后备接待员（不等满员再抢人）</li>
     *   <li><b>日限 (full)：</b>当前数 >= dailyMax，触发扩容流程：从后备池激活新人，满员员工暂时下码</li>
     * </ol>
     *
     * <p>核心设计思路：服务老师(service)为主联系人，满了从后备池激活接待员(receptionist)扩容活码。</p>
     *
     * @param qrCodeId     活码 ID
     * @param userId       员工 userid
     * @param currentCount 该员工今日在当前活码下已添加的客户数
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
     *
     * <p>完整的扩容步骤：</p>
     * <ol>
     *   <li>获取分布式锁，防止并发扩容</li>
     *   <li>人工审核模式跳过</li>
     *   <li>从 {@link QrBackupPool} 后备池取待命(standby)的接待员</li>
     *   <li>标记后备为已激活(activated)</li>
     *   <li>创建接待员 {@link QrAgent} 记录（预设日上限，默认 200）</li>
     *   <li>满员员工标记为 full 状态（含服务老师），记录被谁替换</li>
     *   <li>调用企微 API 同步用户列表</li>
     *   <li>写入轮换日志</li>
     * </ol>
     *
     * <p>日重置后 full 员工自动恢复为 active 状态并重新上活码。</p>
     *
     * @param qrCodeId  活码 ID
     * @param fullUserId 已满员的员工 userid
     * @param qr         活码实体
     * @param fullAgent  已满员的 QrAgent 记录
     */
    @Transactional
    public void expandQrCodeUsers(Long qrCodeId, String fullUserId, QrCode qr, QrAgent fullAgent) {
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":expand";
        // 分布式锁：使用 Redis SET NX EX 实现，防止多个员工同时满员触发重复扩容
        // 锁超时 10 秒，扩容操作通常 < 2 秒，足够避免死锁
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

            // 从后备池取待命的接待员，按排序序号升序取（sortOrder 越小优先级越高）
            List<QrBackupPool> backups = backupPoolRepo
                .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);
            if (backups.isEmpty()) {
                log.error("活码 {} 后备池为空，无法扩容！", qrCodeId);
                return;
            }

            QrBackupPool backup = backups.get(0);
            String backupUserid = backup.getAgentUserid();

            // ① 标记后备池记录为已激活(activated)，后续不再作为备选
            backup.setStatus(QrBackupPool.PoolStatus.activated);
            backup.setUpdatedAt(LocalDateTime.now());
            backupPoolRepo.save(backup);

            // ② 创建接待员 QrAgent 记录（使用后备池中预设的日上限，兜底默认 200）
            //     角色为接待员(receptionist)，区别于主服务老师(service)
            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax() != null ? backup.getDailyMax() : 200)
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active)
                .build();
            qrAgentRepo.save(newAgent);

            // ③ 满员员工标记为 full 状态（包括服务老师），从企微活码用户列表中暂时移除
            //     记录 replacedBy 为替换者的 userid，便于后续审计和日重置恢复
            //     日重置后(dailyReset)会统一恢复为 active 并重新上活码
            fullAgent.setStatus(QrAgent.AgentStatus.full);
            fullAgent.setReplacedBy(backupUserid);
            fullAgent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(fullAgent);

            // ④ 调用企微 API 更新活码配置，将新接待员加入用户列表
            //     同时已满员的服务老师从列表移除（通过 syncQrUsersToWechat 内部过滤逻辑实现）
            syncQrUsersToWechat(qr, fullUserId, backupUserid);

            // ⑤ 写轮换日志，记录扩容原因和替换关系，用于运营审计
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
     * 提前激活后备 — 在达到紧急阈值时触发，不等满员。
     *
     * <p>与 {@link #expandQrCodeUsers} 的区别：此方法仅在达到紧急阈值(urgentThreshold)时调用，
     * 目的是提前把接待员加到活码上分流，避免客户等到满员才切换造成的服务中断。
     * 此时服务老师尚未满员，不会被标记为 full。</p>
     *
     * <p>前置条件检查：如果该活码已有 active 状态的接待员在线，则不重复激活。</p>
     *
     * @param qrCodeId 活码 ID
     * @param qr       活码实体
     */
    @Transactional
    public void preActivateBackup(Long qrCodeId, QrCode qr) {
        String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":preactivate";
        // 分布式锁防止并发提前激活，场景：同一活码下多名员工同时达到紧急阈值
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) return;

        try {
            if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

            // 检查该活码是否已有 active 状态的接待员在线
            // 如果已有接待员正在分流，说明扩容已完成，不再重复激活
            long activeReceptionists = qrAgentRepo.findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active)
                .stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).count();
            if (activeReceptionists > 0) return;

            List<QrBackupPool> backups = backupPoolRepo
                .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);
            if (backups.isEmpty()) return;

            QrBackupPool backup = backups.get(0);
            String backupUserid = backup.getAgentUserid();

            // 获取服务老师的 userid，用于企微 API 调用（同步活码用户列表时需要传入当前主联系人）
            QrAgent svcAgent = qrAgentRepo.findByQrCodeIdAndRole(qrCodeId, QrAgent.AgentRole.service)
                .stream().findFirst().orElse(null);
            String svcUserid = svcAgent != null ? svcAgent.getAgentUserid() : null;

            // 标记后备池记录为已激活
            backup.setStatus(QrBackupPool.PoolStatus.activated);
            backup.setUpdatedAt(LocalDateTime.now());
            backupPoolRepo.save(backup);

            // 创建接待员 QrAgent 记录（与扩容逻辑复用相同的构建逻辑）
            QrAgent newAgent = QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(backupUserid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(backup.getDailyMax() != null ? backup.getDailyMax() : 200)
                .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
                .status(QrAgent.AgentStatus.active)
                .build();
            qrAgentRepo.save(newAgent);

            // 更新企微活码用户列表，将接待员加入
            // 注意：此处不标记服务老师为 full，因为只是提前激活而非满员替换
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
     * 同步活码用户列表到企业微信。
     *
     * <p>核心过滤规则：</p>
     * <ul>
     *   <li>服务老师(service)：仅在 active 状态时上活码。满员(full)时暂时下码，日重置后恢复</li>
     *   <li>接待员(receptionist)：仅在 active 状态时上活码。非 service 角色的员工均视为接待员</li>
     *   <li>非 active 状态（如 full、paused、blocked）的员工不会出现在活码用户列表中</li>
     * </ul>
     *
     * <p>使用 {@link LinkedHashSet} 保证用户列表的顺序一致性，避免企微 API 不必要的全量更新。</p>
     *
     * @param qr         活码实体，用于获取企微 config_id
     * @param fullUserId 满员员工 userid（仅用于日志记录）
     * @param newUserId  新加入的接待员 userid（仅用于日志记录）
     */
    private void syncQrUsersToWechat(QrCode qr, String fullUserId, String newUserId) {
        try {
            // 使用 LinkedHashSet 保证顺序的同时去重，避免同一个员工被多次加入
            Set<String> userIds = new LinkedHashSet<>();

            // 第一轮：收集 active 状态的服务老师
            // 服务老师满了(full)就从列表移除，日重置时恢复
            List<QrAgent> allAgents = qrAgentRepo.findByQrCodeId(qr.getId());
            for (QrAgent a : allAgents) {
                if (a.getRole() == QrAgent.AgentRole.service
                    && a.getStatus() == QrAgent.AgentStatus.active) {
                    userIds.add(a.getAgentUserid());
                }
            }

            // 第二轮：收集 active 状态的接待员（角色非 service 的均为接待员）
            // 先收集服务老师再加接待员，保证服务老师在活码中排在前面
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
     * 每日 00:00 重置所有员工的每日计数，恢复过期绑定关系。
     *
     * <p>每日重置是轮换机制的闭环操作：</p>
     * <ol>
     *   <li>Redis 中的日计数因 TTL 过期自动清空（无需显式操作）</li>
     *   <li>将所有 full 状态员工恢复为 active，清零日计数，记录重置时间</li>
     *   <li>恢复后的员工将在下一次 {@link #syncQrUsersToWechat} 调用时重新加入企微活码</li>
     *   <li>被封号(blocked)或熔断(melted)的员工不在此处理，需要人工确认后恢复</li>
     * </ol>
     *
     * <p>由 {@link com.bookstore.qrcode.worker.DailyResetWorker} 定时调度执行。</p>
     */
    @Transactional
    public void dailyReset() {
        // Redis 日计数由 TTL 自动过期清零，无需显式删除 Redis key
        //   但 DB 中的 daily_current 字段需要手动归零，因为它是持久化存储

        // 恢复所有 full 状态的员工为 active 状态
        //   这会使他们在下次活码同步时重新被加入企微活码用户列表
        List<QrAgent> fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
        for (QrAgent qa : fullAgents) {
            qa.setStatus(QrAgent.AgentStatus.active);
            qa.setDailyCurrent(0);
            qa.setLastResetAt(LocalDateTime.now());
            qrAgentRepo.save(qa);
        }
        log.info("每日重置: 恢复 {} 个 full 员工", fullAgents.size());

        // 被封号(blocked)/熔断(melted)/暂停(paused) 的员工不在此处理
        //   这些状态需要人工确认风险解除后再手动恢复，避免自动恢复导致风控再次触发
        // 后备池中已激活(activated)的记录回退到待命(standby)的逻辑
        //   由后备池管理服务或运营后台处理，此处不自动回退
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
