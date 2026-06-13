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
 * 异常监控与告警服务。
 *
 * <p>接收企微回调中的添加失败事件(add_fail) + 巡检发现的异常，按严重程度分级记录并触发自动处置动作。</p>
 *
 * <h3>企微错误码分级处理策略</h3>
 * <table>
 *   <tr><th>错误码</th><th>含义</th><th>处理方式</th></tr>
 *   <tr><td>84061</td><td>操作频率过高(RATE_LIMITED)</td><td>立即熔断({@link #meltAgent})，从所有活码移除，冷却 30 分钟</td></tr>
 *   <tr><td>25002</td><td>拒绝添加（客户主动拒绝/操作被拦截）</td><td>累计型异常，阈值 10 次/小时触发暂停</td></tr>
 *   <tr><td>84073</td><td>客户已删除员工或被删</td><td>累计型异常，阈值 5 次/小时触发暂停</td></tr>
 *   <tr><td>其他</td><td>其他失败原因</td><td>累计型异常，默认阈值 5 次/小时触发暂停</td></tr>
 * </table>
 *
 * <p>累计型异常采用 1 小时滑动窗口计数，达到阈值后自动暂停员工({@link #pauseAgent})，避免高频率失败影响用户体验。</p>
 *
 * @author 书店技术团队
 * @since 1.0.0
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
     * 处理企微回调中的添加失败事件(add_fail)。
     *
     * <p>根据失败原因中的错误码分级处理：</p>
     * <ul>
     *   <li><b>84061（频率过高风控）：</b>立即熔断员工({@link #meltAgent})，无需累计</li>
     *   <li><b>其他错误码：</b>按 1 小时滑动窗口累计次数，达到阈值后暂停员工({@link #pauseAgent})</li>
     *   <li>达到阈值一半时记录中等告警(medium)作为提前预警</li>
     * </ul>
     *
     * @param event 企微回调事件 JSON 节点，包含 userid、external_userid、fail_reason、state 等字段
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

        // 错误码 84061 = 企微风控 "操作频率过高"(RATE_LIMITED)
        // 这是最严重的企微风控信号，表示员工操作频率触发了企微接口限制
        // 需要立即熔断，将该员工从所有活码中移除并冷却 30 分钟，避免被企微封禁
        if (errcode == 84061) {
            meltAgent(userId, qrCodeId, "企微风控：操作频率过高(84061)");
            return;
        }

        // 累计型异常：统计该员工过去 1 小时内 add_fail 事件的总次数
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long count = alertRepo.countByAgentUseridAndAlertTypeAndCreatedAtAfter(
            userId, "add_fail", oneHourAgo);

        // 不同错误码使用不同的阈值：
        //   - 25002（客户拒绝/操作被拦截）：容忍度较高，10 次/小时才触发
        //   - 84073（客户已删除员工）：已失去联系，5 次/小时触发暂停
        //   - 默认（其他失败）：5 次/小时触发暂停
        int threshold = 5; // 默认阈值
        if (errcode == 25002) threshold = 10;  // 拒绝添加，容忍度高
        if (errcode == 84073) threshold = 5;   // 被客户删除，容忍度低

        if (count >= threshold) {
            // 达到阈值 → 记录高级告警(high) 并自动暂停该员工
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.high,
                Map.of("errcode", errcode, "count", count, "reason", failReason,
                       "external_userid", externalUserId),
                AgentAlert.AutoAction.paused, qrCodeId);
            pauseAgent(userId);
        } else if (count >= threshold / 2) {
            // 达到阈值的一半 → 记录中级告警(medium) 作为提前预警，不自动处置
            createAlert(userId, "add_fail", AgentAlert.AlertSeverity.medium,
                Map.of("errcode", errcode, "count", count, "reason", failReason),
                AgentAlert.AutoAction.none, qrCodeId);
        }
    }

    /**
     * 处理欢迎语发送失败事件(greeting_fail)。
     *
     * <p>欢迎语发送失败通常由企微接口临时异常或客户已删除员工导致。
     * 采用固定阈值 5 次/小时，达到后自动暂停该员工并记录高级告警。</p>
     *
     * <p>与 {@link #handleAddFail} 的区别：欢迎语失败不区分错误码，统一使用 5 次阈值，
     * 且不关联特定活码(qrCodeId 为 null)。</p>
     *
     * @param event 企微回调事件 JSON 节点，包含 userid、external_userid 等字段
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
     * 熔断员工 — 暂停其服务能力，从所有活码移除。
     *
     * <p>熔断是最高级别的自动处置动作，触发条件：</p>
     * <ul>
     *   <li>企微风控错误码 84061（操作频率过高）</li>
     *   <li>{@link com.bookstore.qrcode.service.RateLimiterService} 检测到 1 分钟内添加超过 60 人</li>
     * </ul>
     *
     * <p>熔断逻辑：</p>
     * <ul>
     *   <li>将员工状态设为熔断(melted)或封禁(blocked)，取决于 24 小时内熔断次数</li>
     *   <li>24 小时内累计熔断 >= 3 次 → 升级为 blocked，需要人工介入</li>
     *   <li>&lt; 3 次 → melted，30 分钟后可自动恢复（由定时任务处理）</li>
     *   <li>记录告警，通知运营</li>
     * </ul>
     *
     * @param userId   员工 userid
     * @param qrCodeId 触发熔断的活码 ID（可为 null，如全局速率触发时不关联特定活码）
     * @param reason   熔断原因描述
     */
    @Transactional
    public void meltAgent(String userId, Long qrCodeId, String reason) {
        // 使用悲观写锁（SELECT ... FOR UPDATE）避免高并发下多线程同时更新同一行导致死锁
        Agent agent = agentRepo.findByIdForUpdate(userId).orElse(null);
        if (agent != null) {
            // 已封禁的不再重复更新，避免死锁和无谓的 DB 写
            if (agent.getOverallStatus() == Agent.OverallStatus.blocked) {
                log.debug("员工 {} 已封禁，跳过熔断", userId);
                return;
            }

            int meltCount = agent.getMeltedCount24h() + 1;
            agent.setOverallStatus(meltCount >= 3
                ? Agent.OverallStatus.blocked
                : Agent.OverallStatus.melted);
            agent.setMeltedCount24h(meltCount);
            // status_reason 列类型为 JSON，需序列化而非存裸字符串
            Map<String, Object> reasonMap = new HashMap<>();
            reasonMap.put("reason", reason);
            reasonMap.put("melted_at", LocalDateTime.now().toString());
            reasonMap.put("melt_count_24h", meltCount);
            agent.setStatusReason(objectMapper.valueToTree(reasonMap).toString());
            agentRepo.save(agent);

            createAlert(userId, "melt", AgentAlert.AlertSeverity.high,
                Map.of("reason", reason, "melt_count_24h", meltCount),
                AgentAlert.AutoAction.melted, qrCodeId);

            log.warn("员工 {} 已熔断: {}", userId, reason);
        }
    }

    /**
     * 创建异常告警记录并持久化。
     *
     * <p>告警记录包含以下关键信息：</p>
     * <ul>
     *   <li>关联员工(agentUserid)和活码(qrCodeId)</li>
     *   <li>告警类型(alertType)：add_fail / greeting_fail / melt / empty_backup</li>
     *   <li>严重程度(severity)：low / medium / high</li>
     *   <li>详细内容(detail)：JSON 格式，包含错误码、次数等上下文</li>
     *   <li>自动处置动作(autoAction)：none / paused / melted</li>
     *   <li>初始状态为 open（待处理）</li>
     * </ul>
     *
     * @param agentUserid 关联的员工 userid
     * @param alertType   告警类型（add_fail、greeting_fail、melt、empty_backup）
     * @param severity    严重等级
     * @param detail      详细内容（String 或 Map，自动序列化为 JSON）
     * @param autoAction  已执行的自动处置动作
     * @param qrCodeId    关联的活码 ID（可为 null）
     * @return 持久化后的 {@link AgentAlert} 实体，若序列化失败则返回 null
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
     * 后备池为空告警 — 活码需要扩容但后备池无可用接待员。
     *
     * <p>此告警表示运营人员需要尽快为活码补充后备接待员，
     * 否则当服务老师满员时将无法自动扩容，客户添加可能受阻。</p>
     *
     * @param qrCodeId  活码 ID
     * @param schoolName 学校名称（用于告警展示，便于快速定位）
     */
    @Transactional
    public void alertEmptyBackup(Long qrCodeId, String schoolName) {
        createAlert("", "empty_backup", AgentAlert.AlertSeverity.high,
            Map.of("qr_code_id", qrCodeId, "school_name", schoolName),
            AgentAlert.AutoAction.none, qrCodeId);
    }

    /**
     * 暂停员工 — 将员工状态设为 warning，标记为自动暂停。
     *
     * <p>暂停是较轻的处置动作，与 {@link #meltAgent} 的区别：</p>
     * <ul>
     *   <li>暂停(paused / warning)：不影响已有绑定关系，仅暂停新客户分配</li>
     *   <li>熔断(melted)：从所有活码移除，冷却 30 分钟</li>
     * </ul>
     *
     * <p>暂停原因记录在 statusReason 字段，格式为 JSON，包含自动暂停标记和时间戳，
     * 便于运营人员判断是否为系统自动操作。</p>
     *
     * @param userId 员工 userid
     */
    private void pauseAgent(String userId) {
        Agent agent = agentRepo.findByIdForUpdate(userId).orElse(null);
        if (agent != null) {
            agent.setOverallStatus(Agent.OverallStatus.warning);
            Map<String, Object> reason = new HashMap<>();
            reason.put("auto_paused", true);
            reason.put("time", LocalDateTime.now().toString());
            agent.setStatusReason(objectMapper.valueToTree(reason).toString());
            agentRepo.save(agent);
        }
    }

    /**
     * 根据活码 state 参数反查活码 ID。
     *
     * <p>企微回调事件中的 state 参数对应创建活码时传入的自定义标识（此处为学校标识 schoolId），
     * 通过 {@link QrCodeRepository#findBySchoolId} 反查出系统内的活码 ID。</p>
     *
     * @param state 活码 state 参数
     * @return 活码 ID，如果未找到则返回 null
     */
    private Long findQrCodeId(String state) {
        if (state == null) return null;
        return qrCodeRepo.findBySchoolId(state)
            .map(QrCode::getId).orElse(null);
    }

    /**
     * 从企微返回的失败原因字符串中提取错误码。
     *
     * <p>企微回调的 fail_reason 格式通常为 {@code "errcode:84061, errmsg:xxx"}，
     * 本方法遍历所有冒号分隔的片段，取第一个纯数字串作为错误码。</p>
     *
     * <p>如果无法提取到错误码（格式异常或为空），返回 -1 表示未知错误。</p>
     *
     * @param failReason 企微返回的失败原因字符串
     * @return 错误码整数，若无法解析则返回 -1
     */
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

    /**
     * 从 JSON 节点中安全获取字符串字段值。
     *
     * <p>避免直接调用 {@code event.get(field).asText()} 导致的 NullPointerException，
     * 同时过滤 null 类型的 JSON 值。</p>
     *
     * @param event JSON 节点
     * @param field 字段名
     * @return 字段的字符串值，如果字段不存在或为 null 则返回 null
     */
    private String getStr(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
