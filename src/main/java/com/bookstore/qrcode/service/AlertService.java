package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 异常监控与告警。
 * 接收回调中的失败事件 + 巡检发现的异常 → 分级记录 → 通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AgentAlertRepository alertRepo;
    private final AgentRepository agentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final ObjectMapper objectMapper;

    /**
     * 处理添加失败事件（add_fail）。
     */
    @Transactional
    public void handleAddFail(JsonNode event) {
        String userId = getStr(event, "userid");
        String externalUserId = getStr(event, "external_userid");
        String failReason = getStr(event, "fail_reason");
        String state = getStr(event, "state");

        if (userId == null) return;

        Long qrCodeId = findQrCodeId(state);
        int errcode = extractErrorCode(failReason);

        // 企微风控信号：频率过高 → 立即熔断
        if (errcode == 84061) { // RATE_LIMITED
            meltAgent(userId, qrCodeId, "企微风控：操作频率过高(84061)");
            return;
        }

        // 累计型异常
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
            userId, "add_fail", oneHourAgo);

        int threshold = 5; // 默认 5 次
        if (errcode == 25002) threshold = 10;  // 拒绝
        if (errcode == 84073) threshold = 5;   // 被删

        if (count >= threshold) {
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.high,
                Map.of("errcode", errcode, "count", count, "reason", failReason,
                       "external_userid", externalUserId),
                AgentAlert.AutoAction.paused, qrCodeId);
            // 暂停该员工
            pauseAgent(userId);
        } else if (count >= threshold / 2) {
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.medium,
                Map.of("errcode", errcode, "count", count, "reason", failReason),
                AgentAlert.AutoAction.none, qrCodeId);
        }
    }

    /**
     * 处理欢迎语发送失败。
     */
    @Transactional
    public void handleGreetingFail(JsonNode event) {
        String userId = getStr(event, "userid");
        String externalUserId = getStr(event, "external_userid");
        if (userId == null) return;

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
            userId, "greeting_fail", oneHourAgo);

        if (count >= 5) {
            createAlert(userId, "greeting_fail", AgentAlert.AlertSeverity.high,
                Map.of("count", count, "external_userid", externalUserId),
                AgentAlert.AutoAction.paused, null);
            pauseAgent(userId);
        }
    }

    /**
     * 熔断员工（从所有活码移除，30 分钟冷却）。
     */
    @Transactional
    public void meltAgent(String userId, Long qrCodeId, String reason) {
        Agent agent = agentRepo.findById(userId).orElse(null);
        if (agent != null) {
            int meltCount = agent.getMeltedCount24h() + 1;
            agent.setOverallStatus(meltCount >= 3
                ? Agent.OverallStatus.blocked
                : Agent.OverallStatus.melted);
            agent.setMeltedCount24h(meltCount);
            agent.setStatusReason(reason);
            agentRepo.save(agent);
        }

        createAlert(userId, "melt", AgentAlert.AlertSeverity.high,
            Map.of("reason", reason, "melt_count_24h",
                agent != null ? agent.getMeltedCount24h() : 1),
            AgentAlert.AutoAction.melted, qrCodeId);

        log.warn("员工 {} 已熔断: {}", userId, reason);
    }

    /**
     * 创建异常记录。
     */
    @Transactional
    public AgentAlert createAlert(String agentUserid, String alertType,
                                   AgentAlert.AlertSeverity severity,
                                   Object detail,
                                   AgentAlert.AutoAction autoAction,
                                   Long qrCodeId) {
        try {
            String detailJson = detail instanceof String
                ? (String) detail
                : objectMapper.writeValueAsString(detail);

            AgentAlert alert = AgentAlert.builder()
                .agentUserid(agentUserid)
                .alertType(alertType)
                .severity(severity)
                .detail(detailJson)
                .autoAction(autoAction)
                .status(AgentAlert.AlertStatus.open)
                .qrCodeId(qrCodeId)
                .build();
            alert = alertRepo.save(alert);

            log.warn("告警: user={}, type={}, severity={}, detail={}",
                agentUserid, alertType, severity, detailJson);
            return alert;
        } catch (Exception e) {
            log.error("创建告警失败", e);
            return null;
        }
    }

    /**
     * 后备池空告警。
     */
    @Transactional
    public void alertEmptyBackup(Long qrCodeId, String schoolName) {
        createAlert("", "empty_backup", AgentAlert.AlertSeverity.high,
            Map.of("qr_code_id", qrCodeId, "school_name", schoolName),
            AgentAlert.AutoAction.none, qrCodeId);
    }

    private void pauseAgent(String userId) {
        Agent agent = agentRepo.findById(userId).orElse(null);
        if (agent != null) {
            agent.setOverallStatus(Agent.OverallStatus.warning);
            Map<String, Object> reason = new HashMap<>();
            reason.put("auto_paused", true);
            reason.put("time", LocalDateTime.now().toString());
            agent.setStatusReason(objectMapper.valueToTree(reason).toString());
            agentRepo.save(agent);
        }
    }

    private Long findQrCodeId(String state) {
        if (state == null) return null;
        return qrCodeRepo.findBySchoolId(state)
            .map(QrCode::getId).orElse(null);
    }

    private int extractErrorCode(String failReason) {
        if (failReason == null) return -1;
        try {
            // 尝试从 "errcode:xxx" 格式提取
            String[] parts = failReason.split(":");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.matches("\\d+")) {
                    return Integer.parseInt(trimmed);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String getStr(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
