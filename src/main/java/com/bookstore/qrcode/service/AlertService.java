package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final GlobalAgentPoolRepository poolRepo;
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
            boolean upgradedToBlocked = meltCount >= 3;
            agent.setOverallStatus(upgradedToBlocked
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

            // 升级为封禁时，同步清理全局池（参照 blockAgentForWechatIssue）
            if (upgradedToBlocked) {
                poolRepo.findByAgentUserid(userId).ifPresent(pool -> {
                    poolRepo.delete(pool);
                    log.warn("熔断升级为封禁，全局池已移除: userid={}", userId);
                });
            }

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentAlert createAlert(String agentUserid, String alertType,
                                   AgentAlert.AlertSeverity severity,
                                   Object detail,
                                   AgentAlert.AutoAction autoAction,
                                   Long qrCodeId) {
        try {
            // 统一经 Jackson 序列化，确保字符串也会被 JSON 编码（加引号），
            // 否则 MySQL JSON 列会拒绝裸中文字符串（Invalid JSON text）
            String detailJson = objectMapper.writeValueAsString(detail);

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
        createAlert(null, "empty_backup", AgentAlert.AlertSeverity.high,
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

    // ==================== 告警中心页面查询 ====================

    /**
     * 多条件分页搜索告警，供告警中心列表页使用。
     *
     * @param status      告警状态筛选，可为 {@code null}
     * @param severity    严重程度筛选，可为 {@code null}
     * @param alertType   告警类型筛选，可为 {@code null}
     * @param agentUserid 员工账号模糊搜索，可为 {@code null}
     * @param qrCodeId    关联活码 ID，可为 {@code null}
     * @param startDate   起始日期（含），可为 {@code null}
     * @param endDate     结束日期（含），可为 {@code null}
     * @param pageable    分页参数
     * @return 满足条件的告警分页数据
     */
    public Page<AgentAlert> findAlerts(AgentAlert.AlertStatus status,
                                       AgentAlert.AlertSeverity severity,
                                       String alertType,
                                       String agentUserid,
                                       Long qrCodeId,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       Pageable pageable) {
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        return alertRepo.search(status, severity, alertType, agentUserid,
            qrCodeId, start, end, pageable);
    }

    /**
     * 获取告警统计摘要 — 按状态和严重程度分组计数。
     *
     * @return Map 包含 openCount / resolvedCount / autoResolvedCount / totalCount
     *         以及 highCount / mediumCount / lowCount
     */
    public Map<String, Long> getAlertStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalCount", alertRepo.count());
        stats.put("openCount", alertRepo.countByStatus(AgentAlert.AlertStatus.open));
        stats.put("resolvedCount", alertRepo.countByStatus(AgentAlert.AlertStatus.resolved));
        stats.put("autoResolvedCount", alertRepo.countByStatus(AgentAlert.AlertStatus.auto_resolved));
        stats.put("highCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.high));
        stats.put("mediumCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.medium));
        stats.put("lowCount", alertRepo.countBySeverity(AgentAlert.AlertSeverity.low));
        stats.put("todayCount", alertRepo.countByCreatedAtBetween(todayStart, todayEnd));
        stats.put("weekCount", alertRepo.countByCreatedAtBetween(weekStart, todayEnd));
        return stats;
    }

    /**
     * 管理员手动解决告警。
     *
     * <p>将告警状态从 open 置为 resolved，记录操作人和时间。
     * 已关闭的告警不会重复操作。</p>
     *
     * @param alertId   告警 ID
     * @param adminUser 操作的管理员账号
     */
    @Transactional
    public void resolveAlert(Long alertId, String adminUser) {
        alertRepo.findById(alertId).ifPresent(alert -> {
            if (alert.getStatus() == AgentAlert.AlertStatus.open) {
                alert.setStatus(AgentAlert.AlertStatus.resolved);
                alert.setResolvedBy(adminUser);
                alert.setResolvedAt(LocalDateTime.now());
                alertRepo.save(alert);
                log.info("告警 #{} 已由 {} 手动解决", alertId, adminUser);
            }
        });
    }

    /**
     * 一键解决所有未处理告警，分页遍历直到全部处理完。
     * <p>
     * 每批处理 500 条并立即 flush，确保下一轮查询不会重复捞出已解决的记录。
     * 超大数量（万级）场景下有性能考虑，需注意单次事务时长。
     * </p>
     *
     * @param adminUser 操作的管理员账号
     * @return 实际解决的告警数量
     */
    @Transactional
    public int resolveAllAlerts(String adminUser) {
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        int maxIterations = 200; // 安全上限：最多 200 轮 × 500 = 100,000 条
        Page<AgentAlert> page;
        do {
            page = alertRepo.findByStatus(AgentAlert.AlertStatus.open,
                PageRequest.of(0, 500));
            for (AgentAlert alert : page.getContent()) {
                alert.setStatus(AgentAlert.AlertStatus.resolved);
                alert.setResolvedBy(adminUser);
                alert.setResolvedAt(now);
                alertRepo.save(alert);
                count++;
            }
            alertRepo.flush(); // 确保下一轮查询能看到本轮的更新
        } while (page.hasNext() && --maxIterations > 0);
        if (count > 0) {
            log.info("批量解决告警: {} 条，操作人: {}", count, adminUser);
        }
        return count;
    }
}
