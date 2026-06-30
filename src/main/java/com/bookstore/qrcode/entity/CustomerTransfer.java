package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 客户在员工间转移的完整生命周期记录。
 * <p>
 * 当活码扫码匹配到新员工（目标接待员）时，系统创建此记录跟踪整个转移流程。
 * 包含三大核心机制：
 * <ul>
 *   <li><b>重试机制</b>：调用企业微信API失败时可重试，记录重试次数与失败原因</li>
 *   <li><b>表单填写状态</b>：记录转移发生时客户是否已填写过表单，用于后续差异化欢迎语</li>
 *   <li><b>欢迎语发送状态</b>：区分已填表和未填表两种欢迎语（noteSent 为内部备忘通知，greetingSent 为客户欢迎语）</li>
 * </ul>
 *
 * @author Bookstore Dev Team
 * @since 1.9
 */
@Entity
@Table(name = "customer_transfer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerTransfer {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户ID，对应客户表主键，不可为空 */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * 转出方员工企业微信ID（原服务老师）。
     * 客户从这个员工名下转移给其他员工，不可为空。
     */
    @Column(name = "from_userid", nullable = false, length = 100)
    private String fromUserid;

    /**
     * 转入方员工企业微信ID（目标接待员 / 新服务老师）。
     * 客户被转移给这个员工，不可为空。
     */
    @Column(name = "to_userid", nullable = false, length = 100)
    private String toUserid;

    /**
     * 活码ID，标识本次转移是由哪个活码触发的。
     * 通过活码二维码扫码进入时产生转移记录，可用于追溯活码来源。
     */
    @Column(name = "qr_code_id")
    private Long qrCodeId;

    /** 转移发起时间，即记录创建时记录的时间戳 */
    @Column(name = "transfer_time")
    private LocalDateTime transferTime;

    /**
     * 客户确认转移的时间。
     * 仅在状态为 confirmed（客户确认）时有值；pending_confirm 等其他状态下为空。
     */
    @Column(name = "confirm_time")
    private LocalDateTime confirmTime;

    /**
     * 转移状态，控制整个转移流程的生命周期。
     * <ul>
     *   <li>{@link TransferStatus#pending_confirm} — 待客户确认（初始状态）</li>
     *   <li>{@link TransferStatus#confirmed} — 客户已确认转移</li>
     *   <li>{@link TransferStatus#rejected} — 客户拒绝转移</li>
     *   <li>{@link TransferStatus#timeout} — 超过确认期限未操作</li>
     *   <li>{@link TransferStatus#api_failed} — 调用企业微信转移API失败</li>
     *   <li>{@link TransferStatus#retry_limit} — 重试次数耗尽，最终失败</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransferStatus status = TransferStatus.pending_confirm;

    /**
     * 重试次数，兼用两种上下文：
     * <ul>
     *   <li><b>转移结果追踪</b>（status=pending_confirm）：轮询 get_transfer_result 的次数，
     *       达到 10 次标记为 retry_limit</li>
     *   <li><b>发起重试</b>（status=api_failed）：重新调用 transfer_customer 的次数，
     *       达到 3 次标记为 retry_limit</li>
     * </ul>
     * 注意：从 api_failed 重试成功后状态变为 pending_confirm，retryCount 不清零，
     * 继续作为追踪轮询计数器使用。
     */
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 最后一次失败的原因。
     * <p>
     * 当状态为 api_failed 或 retry_limit 时，记录具体的错误信息，
     * 例如企业微信返回的错误码、错误描述或网络异常信息，便于排查问题。
     */
    @Column(name = "fail_reason", length = 500)
    private String failReason;

    /**
     * 转移发生时客户是否已填写过表单。
     * <p>
     * 该字段在发起转移时根据客户当时的表单填写状态快照记录，而非实时查询。
     * 作用：
     * <ul>
     *   <li>用于决定发送哪种类型的欢迎语（已填表 / 未填表）</li>
     *   <li>避免因表单状态后续变化而影响欢迎语策略的一致性</li>
     *   <li>可用于数据分析，统计已填表客户的转移比例</li>
     * </ul>
     * true = 转移时已填写过表单；false = 未填写；null = 未知。
     */
    @Column(name = "form_filled_at_transfer")
    private Boolean formFilledAtTransfer;

    /**
     * 内部备忘通知是否已发送。
     * <p>
     * 向转出方员工（原服务老师）发送的内部提醒/备忘消息，
     * 告知其名下某客户已转移给其他同事。仅用于内部通知，非客户可见。
     */
    @Column(name = "note_sent")
    @Builder.Default
    private Boolean noteSent = false;

    /**
     * 客户欢迎语是否已发送。
     * <p>
     * 向转入方员工（新服务老师）下发的用于欢迎客户的问候消息。
     * 根据 {@link #greetingType} 决定发送已填表欢迎语或未填表欢迎语。
     * 与 {@link #noteSent} 不同，此标记面向客户侧的问候消息。
     */
    @Column(name = "greeting_sent")
    @Builder.Default
    private Boolean greetingSent = false;

    /**
     * 欢迎语类型，决定发送哪种欢迎语给客户。
     * <ul>
     *   <li>{@link GreetingType#filled} — 已填表欢迎语：客户已填写过表单，发送侧重服务承接的问候</li>
     *   <li>{@link GreetingType#unfilled} — 未填表欢迎语：客户尚未填写表单，发送含表单填写引导的问候</li>
     * </ul>
     * 此值与 {@link #formFilledAtTransfer} 联动决定：
     * formFilledAtTransfer = true  → greetingType = filled；
     * formFilledAtTransfer = false → greetingType = unfilled。
     */
    @Column(name = "greeting_type", length = 20)
    @Enumerated(EnumType.STRING)
    private GreetingType greetingType;

    /** 记录创建时间，不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 记录最后更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 持久化前自动填充创建时间和更新时间。
     * JPA 生命周期回调，由 {@code @PrePersist} 触发。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 更新前自动刷新更新时间。
     * JPA 生命周期回调，由 {@code @PreUpdate} 触发。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 客户转移状态枚举。
     * <p>
     * 定义转移流程中可能出现的所有状态，状态转换示意：
     * <pre>
     *                  ┌→ confirmed（客户确认）
     *                  │
     *   pending_confirm ─→ rejected（客户拒绝）
     *     （初始状态）    │
     *                  ├→ timeout（超时未操作）
     *                  │
     *                  └→ api_failed（API调用失败）→ retry_limit（重试耗尽）
     * </pre>
     */
    public enum TransferStatus {

        /**
         * 待客户确认（初始状态）。
         * <p>
         * 转移记录刚创建、等待客户在企业微信端确认的初始状态。
         * 此时系统已发起转移请求，但客户尚未做出接受或拒绝的响应。
         * 多数转移记录从此状态开始流转。
         */
        pending_confirm,

        /**
         * 客户已确认转移。
         * <p>
         * 客户在企业微信上接受了转移请求，客户关系正式从 {@link #fromUserid}
         * 变更为 {@link #toUserid}。转移流程正常结束的标志，
         * 此时 {@link #confirmTime} 应有值。
         */
        confirmed,

        /**
         * 客户拒绝转移。
         * <p>
         * 客户在企业微信上拒绝了转移请求，客户关系保持原样不变，
         * 仍归属于 {@link #fromUserid}。转移流程终止。
         */
        rejected,

        /**
         * 超过确认期限，客户未操作。
         * <p>
         * 企业微信转移请求有确认有效期（通常为24小时），
         * 若超时客户未响应，状态变更为此值，转移自动作废。
         */
        timeout,

        /**
         * 调用企业微信转移API失败。
         * <p>
         * 发起或确认转移时调用企业微信接口返回错误，
         * 如网络异常、接口限流、参数校验失败等。
         * 系统会根据 {@link #retryCount} 自动重试。
         */
        api_failed,

        /**
         * 重试次数达到上限，最终失败。
         * <p>
         * 经过 {@link #retryCount} 次重试后仍未能完成转移，
         * 为避免无限重试耗尽资源，系统停止尝试并将状态置为此值。
         * 需要人工介入处理。
         */
        retry_limit
    }

    /**
     * 欢迎语类型枚举。
     * <p>
     * 根据客户在转移发生时是否已填写表单，系统发送差异化的欢迎语。
     * 由 {@link #formFilledAtTransfer} 字段决定选用哪种类型。
     */
    public enum GreetingType {

        /**
         * 已填表欢迎语。
         * <p>
         * 客户在转移发生时已经填写过表单，说明其对服务已有基本了解。
         * 欢迎语侧重服务承接和持续跟进，无需重复引导填表。
         */
        filled,

        /**
         * 未填表欢迎语。
         * <p>
         * 客户在转移发生时尚未填写表单，欢迎语中需包含表单填写引导，
         * 提示客户填写信息以便提供更精准的服务。
         */
        unfilled
    }
}
