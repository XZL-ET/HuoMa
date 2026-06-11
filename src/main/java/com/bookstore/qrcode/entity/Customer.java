package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 企微外部联系人（客户）实体。
 * <p>
 * 记录通过企微（企业微信）添加的外部联系人信息，包括添加来源（活码扫码、员工主动添加）、
 * 当前归属员工、所属学校等业务数据。每个客户在全系统中由 externalUserid 唯一标识，
 * 该 ID 与企微侧的外部联系人 ID 一一对应。
 * </p>
 *
 * @author Bookstore
 * @since 1.0
 */
@Entity
@Table(name = "customer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {
    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 企微外部联系人 ID。
     * <p>
     * 对应企业微信「外部联系人」接口中的 external_userid 字段，
     * 在同一企业微信租户下唯一标识一个外部联系人（客户）。该值的格式为：
     * {@code wm${openid}}（微信用户）或 {@code wo${openid}}（企业微信用户）。
     * 通过此 ID 可与企微 API 交互获取客户详情、聊天记录等数据。
     * </p>
     */
    @Column(name = "external_userid", nullable = false, unique = true, length = 100)
    private String externalUserid;

    /** 客户名称（微信昵称或企微联系人名称） */
    @Column(length = 200)
    private String name;

    /** 客户头像 URL */
    @Column(length = 500)
    private String avatar;

    /**
     * 客户类型。
     * <ul>
     *   <li>1 — 微信用户（个人微信）</li>
     *   <li>2 — 企业微信用户（来自其他企业的联系人）</li>
     * </ul>
     * 该字段对应企微 API 返回的 {@code type} 值，用于区分客户身份来源。
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer type = 1;

    /**
     * 微信开放平台 UnionID。
     * <p>
     * 用于跨应用识别同一微信用户。当企业绑定了微信开放平台（公众号 / 小程序 / 企业微信
     * 统一账号体系）时，企微会返回此 ID。可用于关联公司其他业务系统中的同一用户。
     * </p>
     */
    @Column(length = 100)
    private String unionid;

    /**
     * 添加客户的员工 userid。
     * <p>
     * 记录最初将客户添加到企业通讯录的员工（添加人）。对应企微 API 中
     * 「获取客户详情」返回的 {@code userid}。用于追溯客户是由哪位员工添加。
     * </p>
     */
    @Column(name = "added_agent", length = 100)
    private String addedAgent;

    /**
     * 当前归属员工 userid。
     * <p>
     * 记录当前负责跟进该客户的员工。当发生客户分配/转接时，此字段会被更新。
     * 每个客户同一时刻仅归属一位员工。与 addedAgent 不同，此字段反映的是
     * 实时的服务关系，可能因分配策略发生变化。
     * </p>
     */
    @Column(name = "current_agent", length = 100)
    private String currentAgent;

    /**
     * 来源活码 ID。
     * <p>
     * 记录客户是通过哪个活码（群活码/单人活码）扫码添加的。对应 {@code QrCode} 实体。
     * 通过此字段可以溯源客户的引流渠道，用于统计各活码的加粉效果。
     * </p>
     */
    @Column(name = "source_qr_id")
    private Long sourceQrId;

    /**
     * 所属学校 ID。
     * <p>
     * 记录客户所属的学校/机构标识。当活码关联了特定学校时，通过活码添加的客户
     * 会自动填入此字段。用于按学校维度进行客户分组、数据统计和权限隔离。
     * </p>
     */
    @Column(name = "school_id", length = 50)
    private String schoolId;

    /** 客户状态。参见 {@link CustomerStatus} */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CustomerStatus status = CustomerStatus.active;

    /**
     * 添加时间。
     * <p>
     * 客户成为企业联系人的时间，对应企微 API 中「添加时间」字段。
     * 与 createdAt（记录创建时间）不同，此字段代表客户在企微侧的添加时间，
     * 可能在系统记录创建之前。
     * </p>
     */
    @Column(name = "add_time")
    private LocalDateTime addTime;

    /** 记录创建时间，由 {@code prePersist} 自动填充，不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 记录更新时间，由 {@code prePersist} / {@code preUpdate} 自动维护 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调，自动填充创建时间和更新时间。
     * <p>
     * 在 {@code INSERT} 操作前由 JPA 生命周期回调触发，设置 {@link #createdAt}
     * 和 {@link #updatedAt} 为当前数据库时间。
     * </p>
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 更新前回调，自动维护更新时间。
     * <p>
     * 在 {@code UPDATE} 操作前由 JPA 生命周期回调触发，
     * 将 {@link #updatedAt} 更新为当前数据库时间。
     * </p>
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 客户状态枚举。
     * <ul>
     *   <li>{@link #active} — 正常状态，客户为有效的企业联系人，可正常分配和服务</li>
     *   <li>{@link #deleted} — 已删除/流失状态，客户已删除企业成员或已不再跟进</li>
     * </ul>
     *
     * @author Bookstore
     * @since 1.0
     */
    public enum CustomerStatus {

        /** 正常，客户为有效的企业外部联系人 */
        active,

        /** 已删除/流失，客户已不再为企业联系人 */
        deleted
    }
}
