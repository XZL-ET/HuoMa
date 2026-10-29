package com.bookstore.qrcode.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlertTypeMeta 类型元数据")
class AlertTypeMetaTest {

    @Test
    @DisplayName("traffic_spike 的合并键丢弃 agent，只保留活码")
    void shouldDropAgentForKeyOfTrafficSpike() {
        assertThat(AlertTypeMeta.keyOf("traffic_spike", "u1", 7L))
            .isEqualTo(AlertTypeMeta.keyOf("traffic_spike", "u2", 7L));
    }

    @Test
    @DisplayName("全局类型合并键固定为 type||")
    void shouldNormalizeGlobalTypes() {
        assertThat(AlertTypeMeta.keyOf("inheritance_no_service", "u1", 7L)).isEqualTo("inheritance_no_service||");
        assertThat(AlertTypeMeta.keyOf("wechat_48002", null, 7L)).isEqualTo("wechat_48002||");
    }

    @Test
    @DisplayName("leak_ 前缀按条件类处理，事件类不被合并")
    void shouldClassifyTypes() {
        assertThat(AlertTypeMeta.isConditionType("leak_tag_skip_missing_qrcode")).isTrue();
        assertThat(AlertTypeMeta.isConditionType("melt")).isFalse();
        assertThat(AlertTypeMeta.isConditionType("some_future_type")).isFalse();
    }

    @Test
    @DisplayName("深链：有活码去详情，无活码回列表；48002 无链接")
    void shouldBuildLinks() {
        assertThat(AlertTypeMeta.linkFor("traffic_spike", 7L)).isEqualTo("/qrcodes/7");
        assertThat(AlertTypeMeta.linkFor("traffic_spike", null)).isEqualTo("/qrcodes");
        assertThat(AlertTypeMeta.linkFor("qr_agent_anomaly", null)).isEqualTo("/qrcodes/verify-all-agents");
        assertThat(AlertTypeMeta.linkFor("wechat_48002", null)).isEmpty();
        assertThat(AlertTypeMeta.linkFor("unknown_type", null)).isEmpty();
    }

    @Test
    @DisplayName("未知类型回退为原始类型名")
    void shouldFallbackForUnknownType() {
        assertThat(AlertTypeMeta.of("unknown_type").label()).isEqualTo("unknown_type");
        assertThat(AlertTypeMeta.of("unknown_type").informational()).isFalse();
    }

    @Test
    @DisplayName("归一化方法返回应写入告警行的值")
    void shouldNormalizeAgentAndQrForRow() {
        assertThat(AlertTypeMeta.normalizeAgent("traffic_spike", "u1")).isNull();
        assertThat(AlertTypeMeta.normalizeQr("traffic_spike", 7L)).isEqualTo(7L);

        assertThat(AlertTypeMeta.normalizeAgent("wechat_48002", "u1")).isNull();
        assertThat(AlertTypeMeta.normalizeQr("wechat_48002", 7L)).isNull();
        assertThat(AlertTypeMeta.normalizeAgent("inheritance_no_service", "u1")).isNull();
        assertThat(AlertTypeMeta.normalizeQr("inheritance_no_service", 7L)).isNull();

        assertThat(AlertTypeMeta.normalizeAgent("qr_agent_anomaly", "u1")).isEqualTo("u1");
        assertThat(AlertTypeMeta.normalizeQr("qr_agent_anomaly", 7L)).isEqualTo(7L);
    }
}
