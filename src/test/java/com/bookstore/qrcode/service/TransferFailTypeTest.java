package com.bookstore.qrcode.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转接失败原因归类契约测试。
 *
 * <p>归类逻辑是转接明细页「失败原因分布」与「错误类型下钻」的唯一事实来源：
 * {@code classify} 用于内存统计，{@code patternFor} 用于 DB 层 LIKE 过滤，
 * 二者同源于每个枚举值的 keyword，必须保持一致。</p>
 */
@DisplayName("转接失败原因归类")
class TransferFailTypeTest {

    @Test
    @DisplayName("票据过期 errcode=40205 → 40205")
    void classifyTicketExpired() {
        assertThat(TransferFailType.classify("接管员工企微票据过期(errcode=40205)，需重新登录企微并微信授权"))
            .isEqualTo(TransferFailType.TICKET_EXPIRED);
    }

    @Test
    @DisplayName("客户数达上限：有码与无码均归 84097")
    void classifyLimitExceededBothForms() {
        assertThat(TransferFailType.classify("接替成员客户数已达上限(errcode=84097)"))
            .isEqualTo(TransferFailType.LIMIT_EXCEEDED);
        assertThat(TransferFailType.classify("接替成员客户数已达上限"))
            .isEqualTo(TransferFailType.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("超时作废文案 → timeout")
    void classifyTimeout() {
        assertThat(TransferFailType.classify("无接替记录，超时作废"))
            .isEqualTo(TransferFailType.TIMEOUT);
        assertThat(TransferFailType.classify("未知状态码(9)，超时作废"))
            .isEqualTo(TransferFailType.TIMEOUT);
    }

    @Test
    @DisplayName("已知错误码均正确归类")
    void classifyAllKnownCodes() {
        assertThat(TransferFailType.classify("客户已不是好友(errcode=84061)，无法发起继承"))
            .isEqualTo(TransferFailType.NOT_FRIEND);
        assertThat(TransferFailType.classify("客户已删除服务人员(errcode=84073)，无法发起继承"))
            .isEqualTo(TransferFailType.DELETED_BY_USER);
        assertThat(TransferFailType.classify("客户无法发起在职继承(errcode=84096)"))
            .isEqualTo(TransferFailType.TRANSFER_NOT_AVAILABLE);
        assertThat(TransferFailType.classify("已有正在继承的员工(errcode=84100)"))
            .isEqualTo(TransferFailType.PENDING_EXISTS);
        assertThat(TransferFailType.classify("操作冲突(errcode=45035)，稍后自动重试"))
            .isEqualTo(TransferFailType.CONFLICT);
    }

    @Test
    @DisplayName("客户拒绝接替文案 → rejected")
    void classifyRejected() {
        assertThat(TransferFailType.classify("客户拒绝接替"))
            .isEqualTo(TransferFailType.REJECTED);
    }

    @Test
    @DisplayName("未知原因 → other")
    void classifyOther() {
        assertThat(TransferFailType.classify("目标服务老师异常: 企微不可用"))
            .isEqualTo(TransferFailType.OTHER);
        assertThat(TransferFailType.classify("重试耗尽: connection timeout"))
            .isEqualTo(TransferFailType.OTHER);
    }

    @Test
    @DisplayName("空/空白原因 → null")
    void classifyBlank() {
        assertThat(TransferFailType.classify(null)).isNull();
        assertThat(TransferFailType.classify("")).isNull();
        assertThat(TransferFailType.classify("   ")).isNull();
    }

    @Test
    @DisplayName("patternFor 返回对应 LIKE 通配符模式，与 classify 同源")
    void patternForMatchesKeyword() {
        assertThat(TransferFailType.patternFor("40205")).isEqualTo("%errcode=40205%");
        assertThat(TransferFailType.patternFor("84097")).isEqualTo("%客户数已达上限%");
        assertThat(TransferFailType.patternFor("timeout")).isEqualTo("%超时%");
        assertThat(TransferFailType.patternFor("rejected")).isEqualTo("%客户拒绝接替%");
        assertThat(TransferFailType.patternFor("unknown")).isNull();
        assertThat(TransferFailType.patternFor(null)).isNull();
    }

    @Test
    @DisplayName("fromKey 校验合法 key 并提供中文名")
    void fromKey() {
        assertThat(TransferFailType.fromKey("40205").getLabel()).isEqualTo("票据过期");
        assertThat(TransferFailType.fromKey("84061").getLabel()).isEqualTo("已不是好友");
        assertThat(TransferFailType.fromKey("other").getLabel()).isEqualTo("其他");
        assertThat(TransferFailType.fromKey("nope")).isNull();
        assertThat(TransferFailType.fromKey(null)).isNull();
    }
}
