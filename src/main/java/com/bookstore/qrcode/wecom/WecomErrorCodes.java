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
     * 84061 — 操作频率过高，触发企微风控限流。
     * <p>
     * 出现此码时应当立即停止对该接口的调用（熔断），等待一段时间后重试。
     * 该码会进入 {@link #MELT_CODES} 熔断集合，触发上层熔断器打开。
     * 企微常见限流规则：单个应用 600 次/分钟、IP 维度 1200 次/分钟。
     */
    public static final int RATE_LIMITED = 84061;

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
