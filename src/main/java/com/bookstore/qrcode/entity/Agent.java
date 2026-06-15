package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 员工实体（Agent 主数据表）。
 * <p>
 * 表示企业微信中的一个员工账号，是系统的核心人员模型。
 * 每个员工都可以通过活码接待客户，系统根据其角色（接待员 / 服务老师 / 双重角色）
 * 分配不同的接待能力与权限。员工可同时出现在多个活码（QrAgent）和后备池（QrBackupPool）中。
 * </p>
 * <p>
 * 每日接待上限（dailyTotalCap）与当日已用量（dailyTotalUsed）共同决定该员工是否还能继续接待新客户；
 * 综合状态（overallStatus）则用于熔断、预警等自动化管控，超出阈值时系统会自动变更状态并记录原因（statusReason）。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "agent")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {

    /**
     * 企业微信员工账号（主键，非自增）。
     * 由企微通讯录同步生成，不可变更。
     */
    @Id
    @Column(length = 100)
    private String userid;

    /**
     * 员工名称。
     * 对应企微通讯录中的显示姓名。
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 手机号。
     * 对应企微通讯录中的手机号码，可用于紧急联系或短信通知。
     */
    @Column(length = 20)
    private String mobile;

    /**
     * 所属部门。
     * 对应企微通讯录中的部门路径，例如"总部/运营部/客服组"。
     */
    @Column(length = 200)
    private String department;

    /**
     * 员工角色。
     * <ul>
     *   <li>{@link AgentRole#receptionist} —— 接待员：仅负责接待新客户</li>
     *   <li>{@link AgentRole#service} —— 服务老师：仅负责服务已有客户</li>
     *   <li>{@link AgentRole#dual} —— 双重角色：可接待新客户也提供服务</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentRole role = AgentRole.receptionist;

    /**
     * 每日接待客户总上限（参考值，供报表/管理后台展示）。
     * <p>注意：实际日限判定不走此字段，而是由
     * {@code GlobalAgentPool.dailyMax} + Redis {@code agent:daily:total:*}
     * 联合驱动。此字段仅作为初始配置模板和历史参考保留。</p>
     */
    @Column(name = "daily_total_cap", nullable = false)
    @Builder.Default
    private Integer dailyTotalCap = 500;

    /**
     * 当日已接待客户总数（参考值，非实时）。
     * <p>注意：实时计数走 Redis key {@code agent:daily:total:{userid}}，
     * 由 {@code AgentBindService.incrementDailyCount} 异步同步到
     * {@code GlobalAgentPool.dailyCurrent}。此字段可能滞后，
     * 仅作为重启后恢复计数的持久化备份。</p>
     */
    @Column(name = "daily_total_used")
    @Builder.Default
    private Integer dailyTotalUsed = 0;

    /**
     * 员工综合状态。
     * <ul>
     *   <li>{@link OverallStatus#normal} —— 正常：可正常接待客户</li>
     *   <li>{@link OverallStatus#warning} —— 预警：接近上限，需关注</li>
     *   <li>{@link OverallStatus#blocked} —— 已停用：管理员手动禁用，不再分配客户</li>
     *   <li>{@link OverallStatus#melted} —— 已熔断：因异常行为自动熔断，暂停接待</li>
     * </ul>
     */
    @Column(name = "overall_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OverallStatus overallStatus = OverallStatus.normal;

    /**
     * 状态变更原因（JSON 格式）。
     * 记录状态从正常切换到预警 / 熔断 / 停用的详细原因，
     * 例如 {"reason":"24小时内熔断3次","operator":"system","time":"2025-06-10T14:30:00"}。
     */
    @Column(name = "status_reason", columnDefinition = "JSON")
    private String statusReason;

    /**
     * 累计添加次数（历史累计）。
     * 该员工通过活码添加客户的总次数，用于统计和报表。
     */
    @Column(name = "total_added")
    @Builder.Default
    private Integer totalAdded = 0;

    /**
     * 累计删除次数（历史累计）。
     * 该员工删除客户的总次数，用于统计和报表。
     */
    @Column(name = "total_deleted")
    @Builder.Default
    private Integer totalDeleted = 0;

    /**
     * 24 小时内熔断次数。
     * 用于监控员工异常行为。当短时间内频繁触发熔断时，
     * 系统会自动将状态切换为 {@link OverallStatus#melted} 并记录原因。
     */
    @Column(name = "melted_count_24h")
    @Builder.Default
    private Integer meltedCount24h = 0;

    /**
     * 创建时间。
     * 记录该员工数据首次入库的时间，入库后不可变更。
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间。
     * 每次修改员工信息时自动更新。
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前自动填充创建时间和更新时间。
     * 由 JPA 回调在实体持久化之前调用。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 更新前自动刷新更新时间。
     * 由 JPA 回调在实体更新之前调用。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 员工角色枚举。
     * 决定员工在接待流程中的职能范围。
     */
    public enum AgentRole {
        /**
         * 接待员：仅负责接待新客户，不提供后续服务。
         */
        receptionist,

        /**
         * 服务老师：仅负责服务已有客户，不参与新客户接待。
         */
        service,

        /**
         * 双重角色：既可以接待新客户，也可以服务已有客户。
         */
        dual
    }

    /**
     * 员工综合状态枚举。
     * 描述员工当前在整个系统中的可用性等级。
     */
    public enum OverallStatus {
        /**
         * 正常：员工可正常接待客户，无限制。
         */
        normal,

        /**
         * 预警：员工接近接待上限或其他阈值，系统标记预警提醒管理员关注。
         */
        warning,

        /**
         * 已停用：管理员手动禁用该员工，系统不再为其分配任何客户。
         */
        blocked,

        /**
         * 已熔断：因异常行为（如频繁添加/删除客户）自动触发熔断机制，
         * 暂停该员工的接待能力，待人工审核后恢复。
         */
        melted
    }
}
