package com.bookstore.qrcode.wecom;

import java.util.Map;
import java.util.Set;

/**
 * 企业微信 API 常见错误码常量定义。
 * <p>
 * 集中管理企微返回的典型错误码，用于两个场景：
 * <ul>
 *   <li><b>熔断（Circuit Breaker）</b> — {@link #RATE_LIMITED} 直接触发熔断，阻止后续请求</li>
 *   <li><b>累计告警（Accumulate Alert）</b> — {@link #REJECTED} 和 {@link #DELETED_BY_USER} 在达到阈值后报警</li>
 * </ul>
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
     * 默认阈值：<b>累计 10 次</b>触发告警，提示运营人员介入。
     */
    public static final int REJECTED = 25002;

    /**
     * 84061 — 该错误码在不同 API 上下文中有不同含义：
     * <ul>
     *   <li><b>添加客户 API</b>：操作频率过高，触发企微风控限流。出现时应立即停止调用（熔断）。</li>
     *   <li><b>在职继承 API</b>：not external contact — 客户已不是原接待员的好友，
     *       客户关系不存在，无法发起继承。此场景下重试无效，应直接标记为终端失败。</li>
     * </ul>
     * 该码会进入 {@link #MELT_CODES} 熔断集合（仅适用于添加客户场景）。
     * 企微常见限流规则：单个应用 600 次/分钟、IP 维度 1200 次/分钟。
     *
     * @see #NOT_EXTERNAL_CONTACT
     */
    public static final int RATE_LIMITED = 84061;

    /**
     * 84061 — 在职继承场景：客户已不是外部联系人。
     * <p>
     * 与 {@link #RATE_LIMITED} 同值，但在「在职继承」API 中表示客户
     * 与 handover_userid 之间不存在好友关系，无法发起继承。此状态为<b>永久性</b>，
     * 重试无效，应直接将转移记录标记为终端失败（{@code retry_limit}）。
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
     * 默认阈值：<b>累计 5 次</b>触发告警，建议自动移出客户库或标记为「已删除」。
     */
    public static final int DELETED_BY_USER = 84073;

    /**
     * 触发熔断（Circuit Breaker）的错误码集合。
     * <p>
     * 当前仅包含 {@link #RATE_LIMITED}(84061)，
     * 后续可按需扩展（如 40014 — token 过期、40001 — 密钥错误等）。
     * 熔断判定：调用方在收到这些错误码后，不再继续发起该操作，等待恢复窗口。
     */
    public static final Set<Integer> MELT_CODES = Set.of(RATE_LIMITED);

    /**
     * 需要累计统计并触发告警的异常码及其阈值映射。
     * <p>
     * 含义：<b>错误码 → 累计次数阈值</b>。当同一服务人员（或同一客户）的某类错误
     * 达到阈值时，系统自动告警，提示运营人员关注。
     * <ul>
     *   <li>{@link #REJECTED}(25002) → 10 次：客户反复拒绝，可能已被对方拉黑</li>
     *   <li>{@link #DELETED_BY_USER}(84073) → 5 次：大量客户删除，可能服务引起了反感</li>
     * </ul>
     */
    public static final Map<Integer, Integer> ACCUMULATE_THRESHOLD = Map.of(
        REJECTED, 10,
        DELETED_BY_USER, 5
    );

    /** 工具类，禁止实例化。 */
    private WecomErrorCodes() {}
}
