package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.controller.QrRotateLogController;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrRotateLog;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局轮换日志列表页集成测试。
 *
 * <p>验证 {@link QrRotateLogController#list} 跨活码查询下码/上码记录，
 * 正确解析学校名与员工姓名，并支持时间范围 + 关键字筛选。</p>
 */
@DisplayName("全局轮换日志列表页 集成测试")
class RotateLogListIntegrationTest extends BaseIntegrationTest {

    @Autowired private QrRotateLogController controller;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private QrCodeRepository qrCodeRepo;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        rotateLogRepo.deleteAll();
        qrCodeRepo.deleteAll();
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("轮换日志测试学校").schoolId("SCH-ROT-LOG")
            .regionCity("深圳").regionDistrict("南山区").build());
        rotateLogRepo.save(QrRotateLog.builder()
            .qrCodeId(qr.getId()).fromUserid("agent1").toUserid("agent2")
            .reason("全局日限到达 — 自动扩容").build());
        rotateLogRepo.save(QrRotateLog.builder()
            .qrCodeId(qr.getId()).fromUserid("agent_svc")
            .reason("服务老师日限下码").build());
    }

    @Test
    @DisplayName("列表页返回全部轮换日志并解析学校名与员工姓名")
    void shouldListRotateLogs() {
        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("30d", null, null, null, model);

        @SuppressWarnings("unchecked")
        List<QrRotateLog> logs = (List<QrRotateLog>) model.get("logs");
        assertThat(logs).hasSize(2);

        QrRotateLog down = logs.stream()
            .filter(l -> "agent_svc".equals(l.getFromUserid())).findFirst().orElseThrow();
        assertThat(down.getToUserid()).isNull();
        assertThat(down.getReason()).contains("服务老师日限下码");

        QrRotateLog expand = logs.stream()
            .filter(l -> "agent1".equals(l.getFromUserid())).findFirst().orElseThrow();
        assertThat(expand.getToUserid()).isEqualTo("agent2");

        @SuppressWarnings("unchecked")
        Map<Long, String> schoolNameMap = (Map<Long, String>) model.get("schoolNameMap");
        assertThat(schoolNameMap.get(qr.getId())).isEqualTo("轮换日志测试学校");

        @SuppressWarnings("unchecked")
        Map<String, String> nameMap = (Map<String, String>) model.get("nameMap");
        assertThat(nameMap.get("agent1")).isEqualTo("agent1"); // 无通讯录数据时回退 userid
    }

    @Test
    @DisplayName("关键字筛选可匹配学校名与员工 userid")
    void shouldFilterByKeyword() {
        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("30d", null, null, "agent_svc", model);

        @SuppressWarnings("unchecked")
        List<QrRotateLog> logs = (List<QrRotateLog>) model.get("logs");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFromUserid()).isEqualTo("agent_svc");
    }
}
