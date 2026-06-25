package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 全局员工池实体 — 替代每码独立后备池 {@code qr_backup_pool}。
 *
 * <p>全局池汇总所有可用员工，任一活码需要扩容时从池中按优先级取人。
 * 员工在全局层面仅有一份日限额配置（{@link #dailyMax}），
 * 所有活码上的接待量合入 {@link #dailyCurrent} 统一判断满员。</p>
 *
 * <p>状态流转：
 * <ul>
 *   <li>{@code standby} — 待命，可被任意活码分配</li>
 *   <li>{@code full} — 今日全局配额用完，不再分配新客户，午夜自动恢复</li>
 *   <li>{@code blocked} — 管理员手动暂停（如封号/休假）</li>
 * </ul>
 *
 * @author Bookstore Dev Team
 * @since 2.0.0
 */
@Entity
@Table(name = "global_agent_pool")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalAgentPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 企微员工 UserID，全局唯一 */
    @Column(name = "agent_userid", nullable = false, length = 100, unique = true)
    private String agentUserid;

    /** 全局日接待上限（所有活码合计），默认 150 */
    @Column(name = "daily_max", nullable = false)
    @Builder.Default
    private Integer dailyMax = 150;

    /** 今日已接待客户数（所有活码合计，由 Redis 同步） */
    @Column(name = "daily_current")
    @Builder.Default
    private Integer dailyCurrent = 0;

    /** 分配优先级，数值越小越优先被分配 */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /** 员工所属企微部门 ID（取主部门，从 Employee 同步），null 时退化为全局取人 */
    @Column(name = "department_id")
    private Long departmentId;

    /** 池状态：standby / full / blocked */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PoolStatus status = PoolStatus.standby;

    /** 上次日重置时间 */
    @Column(name = "last_reset_at")
    private LocalDateTime lastResetAt;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 全局池员工状态枚举。
     */
    public enum PoolStatus {
        /** 待命 — 可被分配 */
        standby,
        /** 已满 — 今日配额用完，午夜自动恢复 */
        full,
        /** 暂停 — 管理员手动封锁（封号/休假） */
        blocked
    }
}
