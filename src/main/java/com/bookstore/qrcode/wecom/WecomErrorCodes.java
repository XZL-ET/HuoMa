package com.bookstore.qrcode.wecom;

import java.util.Map;

/**
 * 企业微信 API 常见错误码常量定义。
 * <p>
 * 集中管理企微返回的典型错误码，用于「累计告警（Accumulate Alert）」场景：
 * {@link #REJECTED}、{@link #DELETED_BY_USER}、{@link #NOT_EXTERNAL_CONTACT}
 * 在达到阈值后由 {@code AlertService#handleCustomerApiError} 报警。
 * 熔断（Circuit Breaker）由 {@code RateLimiterService} 基于添加速率触发，不依赖错误码。
 * <p>
 * 参考企微文档：https://developer.work.weixin.qq.com/document/path/90539
 *
 * @author bookstore
 * @since 1.0.0
 */
public class WecomErrorCodes {

    /**
     * 20302 — 对方已添加该成员，无需再次添加。
     * <p>
     * 常见于调用「添加客户」接口时，客户已在该服务人员的好友列表中。
     * 此码<strong>不视为异常</strong>，仅记录日志，不参与累计告警。
     */
    public static final int ALREADY_ADDED = 20302;

    /**
     * 25002 — 对方拒绝添加好友请求。
     * <p>
     * 可能原因：客户隐私设置拒绝被添加、客户主动点击「拒绝」、或被企微风控拦截。
     * 累计阈值：<b>10 个客户</b>（1 小时窗口内去重）触发告警，提示运营人员介入。
     */
    public static final int REJECTED = 25002;

    /**
     * 84061 — 客户关系不存在（not external contact）。
     * <p>
     * 在「在职继承」及「添加客户」等场景中，表示客户与成员之间已不存在好友关系，
     * 无法发起继承或继续发消息/打标。此状态为<b>永久性</b>，重试无效，
     * 应直接标记为终端失败（{@code retry_limit}）或累计告警。
     * </p>
     *
     * @since 2.x
     */
    public static final int NOT_EXTERNAL_CONTACT = 84061;

    /**
     * 40205 — 在职继承场景：接管员工企微票据（userticket）过期。
     * <p>
     * 成因：接管员工（takeover_userid）长期未用微信授权方式登录企微、
     * 或使用手机号注册未绑定微信、或客户联系权限被取消。
     * API 重试本身无法刷新票据，但员工重新登录企微客户端后票据即恢复，
     * 此时重试即可成功。因此落库 {@code api_failed} 由
     * {@code retryFailedTransfers} 周期重试（30min→2h→8h→24h），
     * 给员工重新登录留出窗口，而非直接标记 {@code retry_limit} 永久放弃；
     * 重试耗尽后由管理员介入，引导员工重新登录后手动重触发。
     * </p>
     *
     * @since 2.x
     */
    public static final int TICKET_EXPIRED = 40205;

    /**
     * 45035 — 操作冲突（operation conflict）。
     * <p>
     * 在在职继承场景中表示对同一客户关系存在并发操作（客户刚添加、标签/备注仍在写入、
     * 或与其他系统的转移请求撞车），是<b>临时性</b>冲突，重试即可恢复。
     * 应落库 {@code api_failed} 由 {@code retryFailedTransfers} 周期重试，
     * 而非标记 {@code retry_limit} 永久放弃。
     * </p>
     *
     * @since 2.x
     */
    public static final int TRANSFER_CONFLICT = 45035;

    /**
     * 84096 — 在职继承场景：该客户无法发起在职继承。
     * <p>
     * 客户当前状态不满足在职继承条件（如客户已离职、客户数据异常等）。
     * 此状态为<b>永久性</b>，重试无效，应直接标记为终端失败（{@code retry_limit}）。
     * </p>
     *
     * @since 2.x
     */
    public static final int TRANSFER_NOT_AVAILABLE = 84096;

    /**
     * 84097 — 在职继承场景：接替成员客户数已达上限。
     * <p>
     * 目标服务老师的企业微信客户数已达到企微设定的上限，
     * 无法再接收新客户。此状态为<b>永久性</b>——对同一目标员工重试无效，
     * 应直接标记为终端失败（{@code retry_limit}），由管理员指派其他服务老师。
     * </p>
     *
     * @since 2.x
     */
    public static final int TRANSFER_LIMIT_EXCEEDED = 84097;

    /**
     * 84100 — 在职继承场景：已有正在继承的员工。
     * <p>
     * 同一客户已存在进行中的在职继承流程，企微不允许并发发起重复转移。
     * 本地去重检查通常已覆盖此场景，但极端竞态下企微侧可能返回此码。
     * 此状态为<b>永久性</b>——对同一客户重复发起不会改变结果，
     * 应直接标记为终端失败（{@code retry_limit}）。
     * </p>
     *
     * @since 2.x
     */
    public static final int TRANSFER_PENDING_EXISTS = 84100;

    /**
     * 84073 — 客户已删除该服务人员。
     * <p>
     * 表示客户主动删除了企业微信好友关系，后续对该客户的打标签、发消息等操作均会失败。
     * 累计阈值：<b>5 个客户</b>（1 小时窗口内去重）触发告警，建议自动移出客户库或标记为「已删除」。
     */
    public static final int DELETED_BY_USER = 84073;

    /**
     * 需要累计统计并触发告警的异常码及其阈值映射。
     * <p>
     * 含义：<b>错误码 → 累计客户数阈值</b>。当同一服务人员在同一错误码下、
     * 1 小时窗口内出问题的<b>不同客户数</b>达到阈值时，系统自动告警并暂停员工。
     * 未列明的错误码（如 {@link #NOT_EXTERNAL_CONTACT}(84061)）走默认阈值 5。
     * <ul>
     *   <li>{@link #REJECTED}(25002) → 10 个客户：客户反复拒绝，可能已被对方拉黑</li>
     *   <li>{@link #DELETED_BY_USER}(84073) → 5 个客户：大量客户删除，可能服务引起了反感</li>
     * </ul>
     */
    public static final Map<Integer, Integer> ACCUMULATE_THRESHOLD = Map.of(
        REJECTED, 10,
        DELETED_BY_USER, 5
    );

    /** 工具类，禁止实例化。 */
    private WecomErrorCodes() {}
}
