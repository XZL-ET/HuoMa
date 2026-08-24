package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.bookstore.qrcode.wecom.WecomTransientException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 企微同步 + 自愈服务。
 *
 * <p>合并原 QrCodeService 和 AgentBindService 中重复的同步与自愈逻辑。
 * 使用 while 循环替代递归，最多 5 次修复尝试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatSyncHealingService {

    private final WecomApiClient wecomApi;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final AgentRepository agentRepo;
    private final EmployeeRepository employeeRepo;
    private final GlobalAgentPoolService poolService;
    private final AlertService alertService;
    private static final int MAX_HEAL_ATTEMPTS = 5;

    /** Self-injection to enable {@code REQUIRES_NEW} transaction in {@code afterCommit} context.
     *  Non-final so {@code @RequiredArgsConstructor} skips it, breaking the circular dependency.
     *  {@code @Lazy} defers initialization until first use. */
    @Lazy
    @Autowired
    private WechatSyncHealingService self;

    /**
     * 同步 QR 码的企微侧成员列表，含自愈。
     *
     * <p>方法本身为同步执行。需要异步调用时，由上层（如
     * AgentRotationService.syncQrCodeToWechatAsync）通过 @Async 包装。</p>
     *
     * @param qrCodeId      QR 码 ID
     * @param targetUserIds 目标企微 userid 列表（有序）
     * @param source        来源标识（"qr-service"/"agent-rotation"）
     * @return 同步结果
     */
    public SyncResult syncWithHealing(
            Long qrCodeId, List<String> targetUserIds, String source) {
        SyncResult result = new SyncResult();
        List<String> current = new ArrayList<>(targetUserIds);
        int attempt = 0;

        // 提前获取 configId，catch 块中自愈也需要
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        String configId = qr != null ? qr.getQrConfigId() : null;
        if (configId == null) {
            result.success = false;
            result.reason = "QR 码不存在或无 config_id";
            return result;
        }

        while (attempt < MAX_HEAL_ATTEMPTS) {
            try {
                // 1. 同步到企微
                wecomApi.updateContactWay(configId, current);
                result.apiCalls++;

                // 2. 验证：企微侧实际生效的成员
                JsonNode detail = wecomApi.getContactWay(configId);
                List<String> actualUsers = extractUsers(detail);

                if (new java.util.HashSet<>(actualUsers).containsAll(current)
                    && actualUsers.size() == current.size()) {
                    result.success = true;
                    result.finalUsers = current;
                    log.info("企微同步成功: qrCodeId={}, source={}, users={}",
                        qrCodeId, source, current.size());
                    return result;
                }

                // 3. 不在企微侧的成员 → 二分查找定位不可用者
                List<String> missing = new ArrayList<>(current);
                missing.removeAll(actualUsers);
                if (!missing.isEmpty()) {
                    String failing = findFailingUser(configId, missing);
                    if (failing == null) break; // 全部可用

                    log.warn("自愈: qrCodeId={}, 移除不可用成员 {} (第{}次)",
                        qrCodeId, failing, attempt + 1);
                    current.remove(failing);
                    result.replacedUsers.add(failing);

                    // ① 从 qr_agent 移除（服务老师/双角色除外，需手动处理）
                    QrAgent failingAgent = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, failing).orElse(null);
                    if (failingAgent != null
                        && (failingAgent.getRole() == QrAgent.AgentRole.service
                         || failingAgent.getRole() == QrAgent.AgentRole.dual)) {
                        log.warn("自愈: 服务老师/双角色不可用但不下码: qrCodeId={}, userid={}, role={}",
                            qrCodeId, failing, failingAgent.getRole());
                        try {
                            alertService.createAlert(failing, "wechat_unavailable_service",
                                AgentAlert.AlertSeverity.high,
                                String.format("活码 %d 服务老师/双角色 %s 企微不可用，请手动处理",
                                    qrCodeId, failing),
                                AgentAlert.AutoAction.none, qrCodeId);
                        } catch (Exception e2) {
                            log.error("服务老师自愈告警失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                        }
                        // 自动提拔替补 dual，保障在职继承不中断
                        try {
                            ensureServiceFallback(qrCodeId, failing);
                        } catch (Exception e2) {
                            log.error("服务老师替补提拔失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                        }
                        // 已从 current 移除（企微侧确实不可用），但不下码不补人
                    } else {
                        if (failingAgent != null) {
                            failingAgent.setStatus(QrAgent.AgentStatus.removed);
                            self.persistAgentRemoval(failingAgent);
                        }
                        // ② 封锁 agent 并从全局池移除
                        poolService.blockAgentForWechatIssue(failing, 40098);
                        result.needReplacement = true;
                    }

                    // ③ 创建告警
                    try {
                        alertService.createAlert(failing, "wechat_unavailable",
                            AgentAlert.AlertSeverity.medium,
                            String.format("企微不可用员工已被自愈移除: userid=%s 活码=%d",
                                failing, qrCodeId),
                            AgentAlert.AutoAction.removed, qrCodeId);
                    } catch (Exception e2) {
                        log.error("自愈移除告警创建失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                    }
                } else {
                    // 数量对不上但不是 missing 问题 — 重试
                    attempt++;
                }
            } catch (WecomTransientException e) {
                log.warn("企微瞬时故障，重试 {}/{}", attempt + 1, MAX_HEAL_ATTEMPTS);
                attempt++;
            } catch (WecomApiException e) {
                int errcode = e.getErrcode();
                // 可自愈错误（40098=成员未实名, 41054=成员不可用）→ 二分排查后重试
                if ((errcode == 40098 || errcode == 41054) && !current.isEmpty()) {
                    log.warn("自愈: 初始同步失败 errcode={}，二分排查 {} 个成员", errcode, current.size());
                    String failing = findFailingUser(configId, current);
                    if (failing != null) {
                        log.warn("自愈: qrCodeId={}, 移除不可用成员 {} (第{}次, errcode={})",
                            qrCodeId, failing, attempt + 1, errcode);
                        current.remove(failing);
                        result.replacedUsers.add(failing);

                        // 服务老师/双角色不下码，只告警
                        QrAgent failingAgent2 = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, failing).orElse(null);
                        if (failingAgent2 != null
                            && (failingAgent2.getRole() == QrAgent.AgentRole.service
                             || failingAgent2.getRole() == QrAgent.AgentRole.dual)) {
                            log.warn("自愈: 服务老师/双角色不可用但不下码: qrCodeId={}, userid={}, role={}, errcode={}",
                                qrCodeId, failing, failingAgent2.getRole(), errcode);
                            try {
                                alertService.createAlert(failing, "wechat_unavailable_service",
                                    AgentAlert.AlertSeverity.high,
                                    String.format("活码 %d 服务老师/双角色 %s 企微不可用(errcode=%d)，请手动处理",
                                        qrCodeId, failing, errcode),
                                    AgentAlert.AutoAction.none, qrCodeId);
                            } catch (Exception e2) {
                                log.error("服务老师自愈告警失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                            }
                            // 自动提拔替补 dual，保障在职继承不中断
                            try {
                                ensureServiceFallback(qrCodeId, failing);
                            } catch (Exception e2) {
                                log.error("服务老师替补提拔失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                            }
                        } else {
                            if (failingAgent2 != null) {
                                failingAgent2.setStatus(QrAgent.AgentStatus.removed);
                                self.persistAgentRemoval(failingAgent2);
                            }
                            poolService.blockAgentForWechatIssue(failing, errcode);
                            result.needReplacement = true;
                        }

                        try {
                            alertService.createAlert(failing, "wechat_unavailable",
                                AgentAlert.AlertSeverity.medium,
                                String.format("企微不可用员工已被自愈移除: userid=%s 活码=%d errcode=%d",
                                    failing, qrCodeId, errcode),
                                AgentAlert.AutoAction.removed, qrCodeId);
                        } catch (Exception e2) {
                            log.error("自愈移除告警创建失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                        }
                        attempt++;
                        // 继续循环，用剩余成员重试同步
                        continue;
                    }
                }
                // 不可自愈的错误 → 直接失败
                log.error("企微 API 错误: errcode={}, msg={}", e.getErrcode(), e.getErrmsg());
                result.success = false;
                result.reason = "企微 API 错误 [" + errcode + "]: " + e.getErrmsg();
                return result;
            } catch (Exception e) {
                log.error("同步异常", e);
                attempt++;
            }
        }

        result.success = !current.isEmpty();
        result.finalUsers = current;
        result.reason = attempt >= MAX_HEAL_ATTEMPTS ? "超过最大自愈次数" : "同步完成";
        return result;
    }

    /**
     * 二分查找定位企微侧不可用的成员。
     * <p>
     * 通过尝试用 updateContactWay 同步子集来二分定位不可用用户。
     * 原在 QrCodeService 和 AgentBindService 各有一份，现统一。
     * </p>
     *
     * @param configId 企微 config_id
     * @param userIds  待检查的用户列表
     * @return 不可用的 userid，全部可用时返回 null
     */
    public String findFailingUser(String configId, List<String> userIds) {
        if (userIds.isEmpty()) return null;
        if (userIds.size() == 1) {
            return isUserAvailable(configId, userIds.get(0)) ? null : userIds.get(0);
        }

        List<String> mutable = new ArrayList<>(userIds);
        int left = 0, right = mutable.size();

        while (left + 1 < right) {
            int mid = (left + right) / 2;
            List<String> leftHalf = mutable.subList(left, mid);

            try {
                wecomApi.updateContactWay(configId, new ArrayList<>(leftHalf));
                // 左半正常，问题在右半
                left = mid;
            } catch (WecomApiException e) {
                if (e.getErrcode() == 40098 || e.getErrcode() == 41054) {
                    right = mid; // 不可用用户在左半
                } else {
                    log.warn("二分查找遇到非可自愈错误 errcode={}，退化为线性扫描", e.getErrcode());
                    break;
                }
            } catch (Exception e) {
                log.warn("二分查找 API 异常，退化为线性扫描", e);
                break;
            }
        }

        // 兜底：线性扫描 [left, right) 范围，最多扫描 10 个避免雪崩
        int maxLinearScan = Math.min(right, left + 10);
        for (int i = left; i < Math.min(maxLinearScan, mutable.size()); i++) {
            String uid = mutable.get(i);
            if (!isUserAvailable(configId, uid)) {
                return uid;
            }
        }

        return null; // All available
    }

    private boolean isUserAvailable(String configId, String userId) {
        try {
            wecomApi.updateContactWay(configId, List.of(userId));
            return true;
        } catch (WecomApiException e) {
            if (e.getErrcode() == 40098 || e.getErrcode() == 41054) {
                return false;
            }
            // 非可自愈错误也当作不可用（保守）
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 自愈移除不可用成员后，从全局池补充 1 名替补接待员。
     *
     * <p>由调用方在 {@link #syncWithHealing} 返回
     * {@link SyncResult#needReplacement} = true 时调用。
     * 该方法负责从全局池取 1 名 standby 员工加入活码的 QrAgent 表。</p>
     *
     * @param qrCodeId 活码主键 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void supplementReplacement(Long qrCodeId) {
        // 构建排除列表：当前活码上未移除的员工
        Set<String> excludeUserids = new java.util.HashSet<>();
        qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .map(QrAgent::getAgentUserid)
            .forEach(excludeUserids::add);

        // 从全局池取替补，最多尝试 20 次，跳过异常状态的员工
        String backupUserid = null;
        int dailyMax = 150;
        for (int attempt = 0; attempt < 20; attempt++) {
            GlobalAgentPool backup = poolService.takeStandby(excludeUserids);
            if (backup == null) break;

            // 二次校验：替补必须是正常状态（防止 warning/blocked/melted 等异常员工被补入）
            Agent repAgent = agentRepo.findById(backup.getAgentUserid()).orElse(null);
            Employee repEmp = employeeRepo.findByUserid(backup.getAgentUserid()).orElse(null);
            String repLabel = QrCodeService.getAnomalyLabel(repAgent, repEmp);

            if (repLabel == null) {
                // 合格
                backupUserid = backup.getAgentUserid();
                dailyMax = backup.getDailyMax();
                break;
            }
            // 不合格 — 加入排除列表，继续取下一个
            excludeUserids.add(backup.getAgentUserid());
            log.warn("自愈补充跳过异常员工: qrCodeId={}, userid={}, anomaly={}",
                qrCodeId, backup.getAgentUserid(), repLabel);
        }

        if (backupUserid == null) {
            log.warn("自愈补充失败：全局池无健康 standby, qrCodeId={}", qrCodeId);
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            String schoolName = qr != null ? qr.getSchoolName() : String.valueOf(qrCodeId);
            alertService.alertEmptyBackup(qrCodeId, schoolName);
            return;
        }

        // 计算 sortOrder = 当前活码最大 sortOrder + 1
        int maxOrder = qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).stream()
            .mapToInt(QrAgent::getSortOrder)
            .max().orElse(-1);

        QrAgent newAgent = QrAgent.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(backupUserid)
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(dailyMax)
            .sortOrder(maxOrder + 1)
            .status(QrAgent.AgentStatus.active)
            .build();
        qrAgentRepo.save(newAgent);

        log.info("自愈补充: 活码{} 加入替补 {}, sortOrder={}",
            qrCodeId, backupUserid, maxOrder + 1);

        // 同步到企微：构建包含替补的最新成员列表，调用 updateContactWay
        try {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null && qr.getQrConfigId() != null) {
                List<String> updatedUserIds = new ArrayList<>();
                for (QrAgent a : qrAgentRepo.findByQrCodeId(qrCodeId)) {
                    if (a.getStatus() == QrAgent.AgentStatus.active) {
                        updatedUserIds.add(a.getAgentUserid());
                    }
                }
                wecomApi.updateContactWay(qr.getQrConfigId(), updatedUserIds);
                log.info("自愈补充后同步企微成功: qrCodeId={}, users={}",
                    qrCodeId, updatedUserIds.size());
            } else {
                log.warn("自愈补充后无法同步企微: QR 码不存在或无 config_id, qrCodeId={}", qrCodeId);
            }
        } catch (Exception e) {
            log.error("自愈补充后同步企微失败: qrCodeId={}", qrCodeId, e);
        }
    }

    private List<String> extractUsers(JsonNode detail) {
        List<String> users = new ArrayList<>();
        JsonNode userList = detail.path("contact_way").path("user");
        if (userList.isArray()) {
            for (JsonNode u : userList) {
                users.add(u.asText());
            }
        }
        return users;
    }

    /**
     * 服务老师/双角色失联时，自动提拔最资深接待员为 dual 作为替补转接目标。
     *
     * <p>只在活码上没有其他可用 service/dual 时才提拔，避免重复。
     * 提拔后发送告警通知管理员。</p>
     *
     * @param qrCodeId      活码 ID
     * @param failingUserId 已失联的服务老师 userid
     */
    private void ensureServiceFallback(Long qrCodeId, String failingUserId) {
        // 检查是否已有其他可用 service/dual（排除当前失联的）
        long otherSvcCount = qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .filter(a -> a.getRole() == QrAgent.AgentRole.service
                      || a.getRole() == QrAgent.AgentRole.dual)
            .filter(a -> !a.getAgentUserid().equals(failingUserId))
            .count();
        if (otherSvcCount > 0) {
            return; // 已有其他替补，无需提拔
        }

        // 找到最资深的活跃接待员（sortOrder 最小 = 上码最早）
        QrAgent senior = qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() == QrAgent.AgentStatus.active)
            .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist)
            .min(java.util.Comparator.comparingInt(QrAgent::getSortOrder))
            .orElse(null);

        if (senior == null) {
            log.warn("活码 {} 服务老师 {} 失联，且无接待员可提拔为替补", qrCodeId, failingUserId);
            alertService.createAlert(failingUserId, "service_fallback_failed",
                AgentAlert.AlertSeverity.high,
                String.format("活码 %d 服务老师 %s 企微不可用且无接待员可提拔，在职继承将中断",
                    qrCodeId, failingUserId),
                AgentAlert.AutoAction.none, qrCodeId);
            return;
        }

        // 提拔 + 同步 Agent 表 + 告警（独立事务确保写入不受 afterCommit 幽灵事务影响）
        self.persistServiceFallback(senior, qrCodeId, failingUserId);
    }

    // ── afterCommit 安全写入（REQUIRES_NEW 独立事务，不受幽灵事务影响） ──

    /**
     * 在独立事务中标记 QrAgent 为已移除。
     *
     * <p>必须在调用方已设置 {@code agent.setStatus(removed)} 之后再调用。
     * 使用 {@code REQUIRES_NEW} 确保写入不受 {@code afterCommit} 上下文中
     * 残留的 EntityManager 绑定影响。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistAgentRemoval(QrAgent agent) {
        qrAgentRepo.save(agent);
    }

    /**
     * 在独立事务中提拔替补 dual 并同步 Agent 表。
     *
     * <p>使用 {@code REQUIRES_NEW} 确保提拔写入不受 {@code afterCommit}
     * 上下文中残留的 EntityManager 绑定影响。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistServiceFallback(QrAgent senior, Long qrCodeId, String failingUserId) {
        senior.setRole(QrAgent.AgentRole.dual);
        qrAgentRepo.save(senior);
        log.info("服务老师替补已提拔: qrCodeId={}, userid={}, receptionist → dual",
            qrCodeId, senior.getAgentUserid());

        agentRepo.findById(senior.getAgentUserid()).ifPresent(a -> {
            if (a.getRole() == Agent.AgentRole.receptionist) {
                a.setRole(Agent.AgentRole.dual);
                agentRepo.save(a);
            }
        });

        alertService.createAlert(senior.getAgentUserid(), "service_fallback_promoted",
            AgentAlert.AlertSeverity.high,
            String.format("活码 %d 服务老师 %s 企微不可用，已将 %s 自动提拔为 dual 作为替补转接目标",
                qrCodeId, failingUserId, senior.getAgentUserid()),
            AgentAlert.AutoAction.none, qrCodeId);
    }

    /** 同步结果 */
    public static class SyncResult {
        public boolean success = false;
        public boolean needReplacement = false;
        public String reason = "";
        public int apiCalls = 0;
        public List<String> finalUsers = new ArrayList<>();
        public List<String> replacedUsers = new ArrayList<>();
    }
}
