package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.AgentAlert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警类型元数据与深链生成。
 *
 * <p>集中维护每种 {@code agent_alert.alert_type} 的中文名、建议动作、参考级别，
 * 以及它属于「条件类」（同一条件持续存在时合并为一条）还是「事件类」（每次发生独立记录）。
 * 页面文案、类型筛选、合并键与「去处理」跳转均以此为唯一来源；未知类型回退为原始类型名、
 * 无链接，不报错。</p>
 *
 * @param type          告警类型标识（与 {@code agent_alert.alert_type} 一致）
 * @param label         中文名
 * @param suggestion    建议动作（一句话）
 * @param severity      参考严重级别
 * @param informational 是否为可自动恢复/可归档的说明性告警
 */
public record AlertTypeMeta(String type, String label, String suggestion,
                            AgentAlert.AlertSeverity severity, boolean informational) {

    private static final String LEAK_PREFIX = "leak_";

    /** 全部已知类型（条件类在前、事件类在后）。 */
    public static final List<AlertTypeMeta> ALL = List.of(
        // ===== 条件类 =====
        new AlertTypeMeta("qr_agent_anomaly", "活码员工异常",
            "查看异常员工，确认接待池是否需要补充", AgentAlert.AlertSeverity.medium, false),
        new AlertTypeMeta("wechat_unavailable", "员工企微不可用",
            "去员工管理核对并重新同步", AgentAlert.AlertSeverity.medium, false),
        new AlertTypeMeta("wechat_unavailable_service", "服务老师企微不可用",
            "去员工管理核对并重新同步", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("wechat_48002", "企微 48002 能力缺失",
            "系统级问题，本页无需操作；需更换发送方案", AgentAlert.AlertSeverity.low, true),
        new AlertTypeMeta("inheritance_no_service", "继承学校无服务老师",
            "去活码列表为继承学校配置服务老师", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("inheritance_ambiguous_service", "继承学校服务老师不唯一",
            "去活码列表核对服务老师配置", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("empty_backup", "后备池为空",
            "为活码补充后备接待员", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("service_teacher_anomaly", "服务老师异常",
            "去活码查看异常员工并处理", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("anomaly_replace_shortfall", "异常替换缺口",
            "为活码补充可替换员工", AgentAlert.AlertSeverity.medium, false),
        new AlertTypeMeta("anomaly_replace_blocked", "异常替换受阻",
            "检查活码配置后重新处理", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("daily_anomaly_heal", "每日自愈结果",
            "查看异常员工，确认自愈是否完成", AgentAlert.AlertSeverity.low, true),
        new AlertTypeMeta("traffic_spike", "活码高负载",
            "查看活码并考虑扩容", AgentAlert.AlertSeverity.medium, true),
        new AlertTypeMeta("callback_stream_backlog", "回调流积压",
            "去总览查看消费情况", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("tag_stream_backlog", "打标流积压",
            "去总览查看消费情况", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("datafill_stream_backlog", "补数流积压",
            "去总览查看消费情况", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("outbound_stream_backlog", "出站流积压",
            "去总览查看消费情况", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("transfer_stream_backlog", "交接流积压",
            "去总览查看消费情况", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("transfer_dlq_backlog", "交接死信积压",
            "去总览查看死信队列", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("daily_reset_failure", "每日重置失败",
            "去总览查看重置任务日志", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("deletion_report_fail", "删除报告生成失败",
            "去删除记录重新生成", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("dlq_replay_exhausted", "死信重放耗尽",
            "去总览查看死信队列", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("employee_departed_service", "离职员工仍在服务",
            "去员工管理处理离职交接", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("qr_sync_failed_on_create", "活码创建同步失败",
            "去活码详情重新同步", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("qr_service_removed", "活码服务老师被移除",
            "去活码详情重新配置", AgentAlert.AlertSeverity.high, false),
        // ===== 事件类 =====
        new AlertTypeMeta("add_fail", "加好友失败",
            "去员工管理排查失败原因", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("melt", "员工熔断",
            "去员工管理查看熔断状态", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("transfer_fail", "交接失败",
            "去员工管理排查失败原因", AgentAlert.AlertSeverity.high, false),
        new AlertTypeMeta("transfer_retry_exhausted_svc", "交接重试耗尽",
            "查看继承记录并人工跟进", AgentAlert.AlertSeverity.high, false)
    );

    private static final Map<String, AlertTypeMeta> BY_TYPE = new LinkedHashMap<>();

    static {
        for (AlertTypeMeta meta : ALL) {
            BY_TYPE.put(meta.type(), meta);
        }
    }

    /**
     * 全局类型：同一种告警只保留一条（合并键中 agent 与活码均置空）。
     */
    private static final Set<String> GLOBAL_TYPES = Set.of(
        "inheritance_no_service",
        "inheritance_ambiguous_service",
        "callback_stream_backlog",
        "tag_stream_backlog",
        "datafill_stream_backlog",
        "outbound_stream_backlog",
        "transfer_stream_backlog",
        "transfer_dlq_backlog",
        "dlq_replay_exhausted",
        "daily_reset_failure",
        "deletion_report_fail",
        "daily_anomaly_heal",
        "wechat_48002"
    );

    /**
     * 条件类白名单；事件类仅 {@code add_fail}、{@code melt}、{@code transfer_fail}、
     * {@code transfer_retry_exhausted_svc}，未知类型按事件类处理（逐条保留，宁可多留不可误合并）。
     */
    private static final Set<String> CONDITION_TYPES = Set.of(
        "qr_agent_anomaly",
        "wechat_unavailable",
        "wechat_unavailable_service",
        "wechat_48002",
        "inheritance_no_service",
        "inheritance_ambiguous_service",
        "empty_backup",
        "service_teacher_anomaly",
        "anomaly_replace_shortfall",
        "anomaly_replace_blocked",
        "daily_anomaly_heal",
        "traffic_spike",
        "callback_stream_backlog",
        "tag_stream_backlog",
        "datafill_stream_backlog",
        "outbound_stream_backlog",
        "transfer_stream_backlog",
        "transfer_dlq_backlog",
        "daily_reset_failure",
        "deletion_report_fail",
        "dlq_replay_exhausted",
        "employee_departed_service",
        "qr_sync_failed_on_create",
        "qr_service_removed"
    );

    /** 深链到具体活码（无 id 时回退到活码列表）的类型。 */
    private static final Set<String> QR_DETAIL_TYPES = Set.of(
        "traffic_spike",
        "service_teacher_anomaly",
        "anomaly_replace_shortfall",
        "anomaly_replace_blocked",
        "empty_backup",
        "qr_sync_failed_on_create",
        "qr_service_removed"
    );

    /**
     * 查询类型元数据；未知类型回退为原始类型名、medium、非 informational。
     */
    public static AlertTypeMeta of(String type) {
        AlertTypeMeta meta = BY_TYPE.get(type);
        if (meta != null) {
            return meta;
        }
        String raw = type == null ? "" : type;
        return new AlertTypeMeta(raw, raw, "", AgentAlert.AlertSeverity.medium, false);
    }

    /** 类型的中文名；未知类型返回原始类型名。 */
    public static String labelOf(String type) {
        return of(type).label();
    }

    /**
     * 是否为条件类告警（同一条件持续存在时合并为一条）。
     *
     * <p>未知类型按事件类处理，返回 {@code false}，避免误合并。</p>
     */
    public static boolean isConditionType(String type) {
        if (type == null) {
            return false;
        }
        return CONDITION_TYPES.contains(type) || type.startsWith(LEAK_PREFIX);
    }

    /**
     * 归一化后应写入告警行的 agent 值：{@code traffic_spike} 丢弃 agent，
     * 全局类型丢弃 agent，其余类型原样返回。
     */
    public static String normalizeAgent(String type, String agentUserid) {
        if (type == null) {
            return agentUserid;
        }
        if ("traffic_spike".equals(type) || GLOBAL_TYPES.contains(type)) {
            return null;
        }
        return agentUserid;
    }

    /**
     * 归一化后应写入告警行的活码值：全局类型丢弃活码，其余类型原样返回。
     */
    public static Long normalizeQr(String type, Long qrCodeId) {
        if (type != null && GLOBAL_TYPES.contains(type)) {
            return null;
        }
        return qrCodeId;
    }

    /**
     * 合并键：{@code type|agent|qr}，null 值输出为空串。
     *
     * <p>由 {@link #normalizeAgent(String, String)} / {@link #normalizeQr(String, Long)}
     * 派生，保证与写入告警行的归一化值一致。</p>
     */
    public static String keyOf(String type, String agentUserid, Long qrCodeId) {
        String agent = nullToEmpty(normalizeAgent(type, agentUserid));
        Long normalizedQr = normalizeQr(type, qrCodeId);
        String qr = normalizedQr == null ? "" : normalizedQr.toString();
        return nullToEmpty(type) + "|" + agent + "|" + qr;
    }

    /**
     * 深链地址；空串表示无链接（如 {@code wechat_48002} 与未知类型）。
     *
     * <p>具体活码类（{@link #QR_DETAIL_TYPES}）与 {@code leak_*} 动态类型优先落到
     * 活码详情，无 id 时回退活码列表。</p>
     */
    public static String linkFor(String type, Long qrCodeId) {
        if (type == null) {
            return "";
        }
        if (type.startsWith(LEAK_PREFIX) || QR_DETAIL_TYPES.contains(type)) {
            return qrCodeId != null ? "/qrcodes/" + qrCodeId : "/qrcodes";
        }
        return switch (type) {
            case "qr_agent_anomaly", "daily_anomaly_heal" -> "/qrcodes/verify-all-agents";
            case "inheritance_no_service", "inheritance_ambiguous_service" -> "/qrcodes";
            case "add_fail", "melt", "transfer_fail", "wechat_unavailable",
                 "wechat_unavailable_service", "employee_departed_service" -> "/agents";
            case "wechat_48002" -> "";
            case "transfer_retry_exhausted_svc" ->
                qrCodeId != null ? "/qrcodes/" + qrCodeId + "/transfers" : "/dashboard";
            case "transfer_dlq_backlog", "dlq_replay_exhausted", "callback_stream_backlog",
                 "tag_stream_backlog", "datafill_stream_backlog", "outbound_stream_backlog",
                 "transfer_stream_backlog", "daily_reset_failure" -> "/dashboard";
            case "deletion_report_fail" -> "/deletion-records";
            default -> "";
        };
    }

    /** 深链按钮文案；空串表示无按钮。 */
    public static String linkLabelFor(String type) {
        if (type == null) {
            return "";
        }
        if (type.startsWith(LEAK_PREFIX) || QR_DETAIL_TYPES.contains(type)) {
            return "去活码";
        }
        return switch (type) {
            case "qr_agent_anomaly", "daily_anomaly_heal" -> "查看异常员工";
            case "inheritance_no_service", "inheritance_ambiguous_service" -> "去配置服务老师";
            case "add_fail", "melt", "transfer_fail", "wechat_unavailable",
                 "wechat_unavailable_service", "employee_departed_service" -> "去员工管理";
            case "transfer_retry_exhausted_svc" -> "查看继承记录";
            case "transfer_dlq_backlog", "dlq_replay_exhausted", "callback_stream_backlog",
                 "tag_stream_backlog", "datafill_stream_backlog", "outbound_stream_backlog",
                 "transfer_stream_backlog", "daily_reset_failure" -> "去总览";
            case "deletion_report_fail" -> "去删除记录";
            default -> "";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
