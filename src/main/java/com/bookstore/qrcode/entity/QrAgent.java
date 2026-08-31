package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码与员工的关联表，记录每个员工在某个活码上的角色、日配额、当前接待数、状态。
 * <p>
 * 一个活码（QrCode）下可绑定多个员工，每个员工在此活码上独立记录配额与接待情况。
 * 系统根据 role 决定该员工在此活码上承担的任务类型（接待/服务/双重），
 * 并根据 dailyMax / serviceDailyMax 控制日接待上限，配合 dailyCurrent 实现每日计数器。
 * </p>
 *
 * @author bookstore
 * @since 1.0
 */
@Entity
@Table(name = "qr_agent")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrAgent {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的活码 ID（QrCode 表主键），不可空 */
    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    /** 员工在企业微信中的 userid（或系统内员工唯一标识），不可空 */
    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    /**
     * 员工在此活码上的角色。
     * <ul>
     *   <li>{@link AgentRole#receptionist} — 前台接待员/客服，负责首次接待客户</li>
     *   <li>{@link AgentRole#service} — 服务人员/后端服务老师，接待由前台转接的客户</li>
     *   <li>{@link AgentRole#dual} — 双重角色，既可做前台接待也可做后端服务</li>
     * </ul>
     * 默认值：receptionist
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentRole role = AgentRole.receptionist;

    /**
     * 日接待总上限（含接待与服务），默认 100。
     * <p>
     * 当 {@code role = receptionist} 时，此字段控制当天最多可接待的客户总数；
     * 当 {@code role = service} 时，此字段作为总配额兜底（但通常由 serviceDailyMax 控制）。
     * </p>
     */
    @Column(name = "daily_max", nullable = false)
    @Builder.Default
    private Integer dailyMax = 150;

    /**
     * 当日已接待数（计数器），默认 0。
     * <p>
     * 每次客户被分配给该员工时 +1，每日凌晨（或按 {@link #lastResetAt} 记录的时间）重置为 0。
     * 当 {@code dailyCurrent >= dailyMax} 时，该员工在该活码上的状态自动变为 {@link AgentStatus#full}。
     * </p>
     */
    @Column(name = "daily_current")
    @Builder.Default
    private Integer dailyCurrent = 0;

    /**
     * 服务日上限（仅对 role = service 或 dual 生效），可空。
     * <p>
     * 与 {@link #dailyMax} 的区别：
     * <ul>
     *   <li>{@code dailyMax} — 总上限，对所有角色均生效，是硬上限；</li>
     *   <li>{@code serviceDailyMax} — 服务上限，仅控制「服务」场景的配额；
     *       当此值为 null 时，服务场景复用 {@code dailyMax} 作为上限。</li>
     * </ul>
     * 例如：某员工 dailyMax=150，serviceDailyMax=150，表示当天最多服务 150 个客户，
     * 但总的（含接待）不超过 150。
     * </p>
     */
    @Column(name = "service_daily_max")
    private Integer serviceDailyMax;

    /**
     * 排序序号，值越小排序越靠前（默认 0）。
     * 用于活码分配员工时决定优先级顺序。
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 员工在此活码上的当前状态。
     * <ul>
     *   <li>{@link AgentStatus#active} — 活跃，可正常分配客户</li>
     *   <li>{@link AgentStatus#full} — 已满，当日配额用完，不再分配新客户（由计数器自动触发）</li>
     *   <li>{@link AgentStatus#removed} — 已移除，该员工已从活码中解绑，不再参与分配</li>
     *   <li>{@link AgentStatus#blocked} — 被屏蔽，管理员手动暂停该员工的接待能力，暂时不分配客户</li>
     * </ul>
     * 默认值：active
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AgentStatus status = AgentStatus.active;

    /**
     * 被替换的员工标识（agent_userid）。
     * <p>
     * 当管理员将某员工替换为另一员工时，记录被替换者的 userid。
     * 例如：员工 A 离职后替换为员工 B，则 B 的记录中 replacedBy = A 的 userid。
     * 用于离职交接、排班替换等场景下的追溯。
     * </p>
     */
    @Column(name = "replaced_by", length = 100)
    private String replacedBy;

    /**
     * 上次日计数器重置时间。
     * <p>
     * 每日凌晨系统自动将 {@link #dailyCurrent} 重置为 0 时更新此字段，
     * 记录最近一次重置发生的时间戳，用于追踪计数周期和排查问题。
     * </p>
     */
    @Column(name = "last_reset_at")
    private LocalDateTime lastResetAt;

    /**
     * 是否为临时顶替接待员。
     * <p>
     * 服务老师是活码唯一 active 成员时日限下码前，从同部门临时补入的接待员
     * 会被标记为临时（{@code true}），次日服务老师恢复 active 后由每日重置释放。
     * 正常上码的接待员此值为 {@code false}（默认）。
     * </p>
     */
    @Column(name = "is_temporary")
    @Builder.Default
    private Boolean temporary = false;

    /**
     * 绑定目标信息，JSON 格式。
     * <p>
     * 用于存储员工在此活码上的附加绑定配置，示例结构：
     * <pre>
     * {
     *   "tags": ["VIP客户", "新客"],
     *   "remark": "该员工负责高价值客户",
     *   "ext": { ... }
     * }
     * </pre>
     * 当前主要用途：客户扫码后自动打标（tags 字段），实现客户标签的自动化绑定。
     * </p>
     */
    @Column(name = "bind_target", columnDefinition = "JSON")
    private String bindTarget;

    /** 创建时间，不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间和更新时间。
     * <p>由 JPA 生命周期注解 {@link PrePersist} 触发。</p>
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 更新前回调：自动更新修改时间。
     * <p>由 JPA 生命周期注解 {@link PreUpdate} 触发。</p>
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 员工在活码上的角色枚举。
     */
    public enum AgentRole {
        /** 前台接待员/客服：负责首次接待客户，受 dailyMax 控制 */
        receptionist,
        /** 服务人员/后端服务老师：接待由前台转接的客户，受 serviceDailyMax 控制 */
        service,
        /** 双重角色：既可做前台接待也可做后端服务，同时受两个上限控制 */
        dual
    }

    /**
     * 员工在活码上的状态枚举。
     */
    public enum AgentStatus {
        /** 活跃：可正常分配客户 */
        active,
        /** 已满：当日配额用完，不再分配新客户（由 {@link #dailyCurrent} >= {@link #dailyMax} 自动触发） */
        full,
        /** 已移除：该员工已从活码中解绑，不再参与分配 */
        removed,
        /** 被屏蔽：管理员手动暂停该员工的接待能力 */
        blocked
    }
}
