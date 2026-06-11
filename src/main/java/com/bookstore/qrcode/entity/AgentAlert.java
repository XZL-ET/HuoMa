package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 员工状态预警 / 熔断记录。
 * <p>
 * 当员工（客服/服务老师）出现异常行为或触发限流规则时，系统会生成一条预警记录，
 * 并根据严重程度自动执行相应的处理动作（如暂停分配、移除接待资格、熔断等），
 * 同时记录动作的执行结果，供管理员追溯和人工介入处理。
 * </p>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li><b>open</b> — 预警刚生成，未处理；</li>
 *   <li><b>auto_resolved</b> — 系统自动恢复（如熔断期结束）；</li>
 *   <li><b>resolved</b> — 管理员手动标记解决。</li>
 * </ol>
 *
 * <h3>detail 字段 JSON 结构示例</h3>
 * <pre>{@code
 * {
 *   "reason": "连续5分钟无响应",
 *   "metric": {
 *     "name": "response_rate",
 *     "value": 0.2,
 *     "threshold": 0.5
 *   },
 *   "duration_minutes": 5,
 *   "related_alert_ids": [101, 102],
 *   "action_result": "melted_5m"
 * }
 * }</pre>
 *
 * @author Bookstore Dev
 * @since 1.10
 */
@Entity
@Table(name = "agent_alert")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentAlert {

    /** 主键，自增 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 员工企业微信 userid，关联员工配置表 */
    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    /**
     * 预警类型标识，如：
     * <ul>
     *   <li><code>response_timeout</code> — 响应超时</li>
     *   <li><code>daily_limit_reached</code> — 日接上限已满</li>
     *   <li><code>abnormal_behavior</code> — 异常行为</li>
     * </ul>
     */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /** 预警严重程度 */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.medium;

    /**
     * 预警详情（JSON 格式），记录触发预警的上下文信息，方便人工排查。
     * <p>
     * 推荐结构包含：触发原因、指标名称与数值、持续时间、关联告警 ID、自动动作执行结果等。
     * 具体内容因 {@link #alertType} 而异，参见类注释中的示例。
     * </p>
     */
    @Column(columnDefinition = "JSON")
    private String detail;

    /** 系统自动执行的处理动作 */
    @Column(name = "auto_action", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AutoAction autoAction = AutoAction.none;

    /** 预警当前状态，参见类注释中的生命周期说明 */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AlertStatus status = AlertStatus.open;

    /** 解决此预警的管理员账号（人工介入时填写） */
    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    /** 解决时间，人工或系统自动解决时记录 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 关联的活码 ID，可用于定位具体哪个活码触发了预警 */
    @Column(name = "qr_code_id")
    private Long qrCodeId;

    /** 记录创建时间，由 {@link #prePersist()} 自动填充，不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 持久化前自动设置创建时间 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // ========== 内部枚举 ==========

    /**
     * 预警严重程度。
     * <ul>
     *   <li><b>low</b> — 低级别，仅记录不触发自动动作，可忽略；</li>
     *   <li><b>medium</b> — 中等级别，触发轻量自动动作并通知管理员；</li>
     *   <li><b>high</b> — 高级别，触发熔断等强干预动作，需管理员跟进。</li>
     * </ul>
     */
    public enum AlertSeverity { low, medium, high }

    /**
     * 系统自动执行的处理动作。
     * <ul>
     *   <li><b>none</b> — 未执行任何自动动作；</li>
     *   <li><b>paused</b> — 暂停该员工的客户分配（临时停用）；</li>
     *   <li><b>removed</b> — 从当前活码的接待池中移除该员工；</li>
     *   <li><b>melted</b> — 熔断，进入冷却期，冷却期结束后自动恢复。</li>
     * </ul>
     */
    public enum AutoAction { none, paused, removed, melted }

    /**
     * 预警状态，反映其完整生命周期。
     * <ul>
     *   <li><b>open</b> — 预警已生成，尚未处理；</li>
     *   <li><b>resolved</b> — 管理员手动标记已解决，并记录解决人 ({@link #resolvedBy}) 与解决时间 ({@link #resolvedAt})；</li>
     *   <li><b>auto_resolved</b> — 系统自动解决（如熔断冷却期结束后自动恢复），不记录解决人。</li>
     * </ul>
     */
    public enum AlertStatus { open, resolved, auto_resolved }
}
