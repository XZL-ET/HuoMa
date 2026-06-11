package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码核心实体，每个学校对应一个活码。
 * <p>活码是企微联系我二维码的管理单元，每个活码关联一个学校，并绑定企微 config_id。
 * 支持以下核心功能：</p>
 * <ul>
 *   <li>自动/手动轮换模式（RotateMode），用于客户分配策略</li>
 *   <li>自定义样式配置（styleConfig），控制二维码展示外观</li>
 *   <li>欢迎语配置（welcomeConfig），控制客户扫码后的自动回复与表单收集</li>
 *   <li>预警/紧急阈值（warnRatio/urgentRatio），用于人力监控告警</li>
 *   <li>自定义标签（customTags），客户扫码后自动打标</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "qr_code")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键 ID，自增 */
    private Long id;

    /** 学校名称 */
    @Column(name = "school_name", nullable = false, length = 100)
    private String schoolName;

    /** 学校唯一标识，用于关联学校数据，全局唯一 */
    @Column(name = "school_id", nullable = false, unique = true, length = 50)
    private String schoolId;

    /** 所在城市 */
    @Column(name = "region_city", nullable = false, length = 50)
    private String regionCity;

    /** 所在区/县 */
    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    /** 企微联系我二维码配置 ID，用于生成二维码和客户添加统计 */
    @Column(name = "qr_config_id", length = 100)
    private String qrConfigId;

    /** 活码二维码图片 URL，可通过该地址下载二维码图片 */
    @Column(name = "qr_url", length = 500)
    private String qrUrl;

    /** 活码二维码图片本地存储路径 */
    @Column(name = "qr_image_path", length = 500)
    private String qrImagePath;

    /**
     * 样式配置（JSON 格式），控制二维码展示外观。
     * <p>JSON 结构示例：</p>
     * <pre>
     * {
     *   "logo_path": "",              // 自定义 logo 图片路径
     *   "theme": "",                  // 主题配色
     *   "guide_text": "",             // 引导文字
     *   "show_school_name": true      // 是否显示学校名称
     * }
     * </pre>
     */
    @Column(name = "style_config", columnDefinition = "JSON")
    private String styleConfig;

    /**
     * 欢迎语配置（JSON 格式），控制客户扫码后的自动回复与表单收集。
     * <p>JSON 结构示例：</p>
     * <pre>
     * {
     *   "text": "",                        // 欢迎语文本
     *   "collect_form": true,               // 是否收集表单信息
     *   "form_callback_tag": "",            // 表单回传后的自动打标标签
     *   "transfer_greeting_enabled": true   // 是否启用转接问候语
     * }
     * </pre>
     */
    @Column(name = "welcome_config", columnDefinition = "JSON")
    private String welcomeConfig;

    /** 活码状态：active-正常, paused-暂停, full-已满, no_agent-无可用客服 */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private QrCodeStatus status = QrCodeStatus.active;

    /** 轮换模式：auto-自动轮换, manual-手动分配 */
    @Column(name = "rotate_mode", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RotateMode rotateMode = RotateMode.auto;

    /** 预警阈值百分比（如 80 表示接待量达到 80% 时触发预警通知），用于人力监控告警 */
    @Column(name = "warn_ratio")
    @Builder.Default
    private Integer warnRatio = 80;

    /** 紧急阈值百分比（如 95 表示接待量达到 95% 时触发紧急告警），用于人力监控告警 */
    @Column(name = "urgent_ratio")
    @Builder.Default
    private Integer urgentRatio = 95;

    /** 创建方式：manual-手动创建, batch_import-批量导入 */
    @Column(name = "create_mode", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CreateMode createMode;

    /** 备注说明 */
    @Column(length = 500)
    private String remark;

    /** 在职继承目标员工 userid（手动触发继承时的导入对象） */
    @Column(name = "transfer_target_userid", length = 100)
    private String transferTargetUserid;

    /** 活码创建时初始上码人数，默认 1 */
    @Column(name = "initial_agent_count")
    @Builder.Default
    private Integer initialAgentCount = 1;

    /** 客户扫码后自动打标的自定义标签列表，多个标签以逗号分隔 */
    @Column(name = "custom_tags", length = 500)
    private String customTags;

    /** 创建时间，不可更新 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 持久化前回调：自动填充创建时间和更新时间。
     *
     * <p>在 INSERT 操作执行前由 JPA 自动调用，为 {@link #createdAt} 和 {@link #updatedAt}
     * 设置当前系统时间，确保每次新建记录的时间戳精确可靠。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * JPA 更新前回调：自动刷新更新时间。
     *
     * <p>在 UPDATE 操作执行前由 JPA 自动调用，将 {@link #updatedAt} 更新为当前系统时间，
     * 精确记录该记录的最后修改时间点。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 活码状态枚举。
     * <ul>
     *   <li>active - 正常启用，客户可正常扫码添加</li>
     *   <li>paused - 已暂停，临时停止该活码的客户分配</li>
     *   <li>full - 已满，所有客服接待名额已满，不再分配新客户</li>
     *   <li>no_agent - 无可用客服，所有客服均处于不可接待状态</li>
     * </ul>
     */
    public enum QrCodeStatus { active, paused, full, no_agent }

    /**
     * 轮换模式枚举，控制客户分配策略。
     * <ul>
     *   <li>auto - 自动轮换（默认），系统按策略自动分配客服</li>
     *   <li>manual - 手动分配，需管理员手动指定客服</li>
     * </ul>
     */
    public enum RotateMode { auto, manual }

    /**
     * 创建方式枚举。
     * <ul>
     *   <li>manual - 手动创建，通过后台页面逐条添加</li>
     *   <li>batch_import - 批量导入，通过 Excel 等工具批量导入</li>
     * </ul>
     */
    public enum CreateMode { manual, batch_import }
}
