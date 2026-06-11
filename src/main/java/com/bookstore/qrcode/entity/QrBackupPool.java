package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 后备接待员池。
 * <p>
 * 当主接待员工（前端接待员）全部满员或不可用时，系统按 {@link #sortOrder} 排序依次激活后备接待员
 * 来接替服务。每条记录代表一个员工在该活码下的后备角色与状态。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "qr_backup_pool")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrBackupPool {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属活码 ID，关联 qr_code 表 */
    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    /** 员工企微 userid，关联企业微信通讯录 */
    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    /**
     * 后备角色。
     * <ul>
     *   <li>{@link PoolRole#receptionist} — 前端接待员，优先服务客户</li>
     *   <li>{@link PoolRole#service} — 服务老师，后端支持角色</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PoolRole role = PoolRole.receptionist;

    /**
     * 排序优先级，数值越小优先级越高，越先被激活。
     * <p>
     * 系统在触发后备逻辑时，按 {@code sortOrder} 升序查找状态为 {@link PoolStatus#standby} 的记录，
     * 选中排序最靠前的员工进行激活。<br>
     * 如有多个相同值的记录，则激活顺序不保证（交由数据库返回顺序决定）。
     * </p>
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 日接客户上限。
     * <p>
     * 该员工每日最多接待的客户数，达到上限后系统会跳过该员工并尝试下一个后备。
     * </p>
     */
    @Column(name = "daily_max")
    @Builder.Default
    private Integer dailyMax = 200;

    /**
     * 后备池状态。
     * <ul>
     *   <li>{@link PoolStatus#standby} — 待命，可被激活</li>
     *   <li>{@link PoolStatus#activated} — 已激活，正在接客</li>
     *   <li>{@link PoolStatus#removed} — 已移除，不再参与后备逻辑</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PoolStatus status = PoolStatus.standby;

    /** 创建时间，不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 持久化前自动填充创建时间和更新时间 */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 后备池员工角色。
     *
     * @author Bookstore Dev
     * @since 1.0
     */
    public enum PoolRole {

        /** 前端接待员，直接面对客户，优先被分配客户 */
        receptionist,

        /** 服务老师，后端支持角色，在接待员不足时接替 */
        service
    }

    /**
     * 后备池状态，标识员工在后备流中的生命周期阶段。
     *
     * @author Bookstore Dev
     * @since 1.0
     */
    public enum PoolStatus {

        /** 待命状态 — 员工在后备池中等待被激活，可参与后备排序和选择 */
        standby,

        /** 已激活状态 — 员工已被激活并开始接待客户 */
        activated,

        /** 已移除状态 — 员工已从后备池中移除，不再参与任何后备逻辑 */
        removed
    }
}
