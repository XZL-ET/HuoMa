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
import org.springframework.stereotype.Service;

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

                    // ① 从 qr_agent 移除
                    qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, failing).ifPresent(qa -> {
                        qa.setStatus(QrAgent.AgentStatus.removed);
                        qrAgentRepo.save(qa);
                    });

                    // ② 封锁 agent 并从全局池移除
                    poolService.blockAgentForWechatIssue(failing, 40098);

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

                    // ④ 需要补充新成员
                    result.needReplacement = true;
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

                        qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, failing).ifPresent(qa -> {
                            qa.setStatus(QrAgent.AgentStatus.removed);
                            qrAgentRepo.save(qa);
                        });
                        poolService.blockAgentForWechatIssue(failing, errcode);

                        try {
                            alertService.createAlert(failing, "wechat_unavailable",
                                AgentAlert.AlertSeverity.medium,
                                String.format("企微不可用员工已被自愈移除: userid=%s 活码=%d errcode=%d",
                                    failing, qrCodeId, errcode),
                                AgentAlert.AutoAction.removed, qrCodeId);
                        } catch (Exception e2) {
                            log.error("自愈移除告警创建失败: userid={}, qrCodeId={}", failing, qrCodeId, e2);
                        }
                        result.needReplacement = true;
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
    @org.springframework.transaction.annotation.Transactional
    public void supplementReplacement(Long qrCodeId) {
        // 构建排除列表：当前活码上未移除的员工
        Set<String> excludeUserids = new java.util.HashSet<>();
        qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .map(QrAgent::getAgentUserid)
            .forEach(excludeUserids::add);

        // 从全局池取替补，最多尝试 20 次，跳过异常状态的员工
        String backupUserid = null;
        int dailyMax = 100;
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
