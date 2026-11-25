package com.bookstore.qrcode.service;

import com.bookstore.qrcode.repository.SystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("StrictDeptConfigService 严格同部门配置")
class StrictDeptConfigServiceTest {

    private static final String MODE_KEY = "strict_dept_mode";
    private static final String EXEMPT_KEY = "strict_dept_exempt_userids";

    @Autowired
    private StrictDeptConfigService service;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @BeforeEach
    void cleanStrictDeptConfig() {
        deleteIfPresent(MODE_KEY);
        deleteIfPresent(EXEMPT_KEY);
    }

    /**
     * 清理写回 system_config 的键：多个 {@code @SpringBootTest} 共享同一 H2 上下文，
     * 若把 strict_dept_mode 留给后续测试，会让清理/取人类测试意外进入严格模式。
     */
    @AfterEach
    void restoreStrictDeptConfig() {
        deleteIfPresent(MODE_KEY);
        deleteIfPresent(EXEMPT_KEY);
    }

    private void deleteIfPresent(String key) {
        systemConfigRepository.findByConfigKey(key)
                .ifPresent(systemConfigRepository::delete);
    }

    @Test
    @DisplayName("缺省时严格模式关闭且豁免为5人默认名单")
    void 缺省时严格模式关闭且豁免为5人默认名单() {
        assertThat(service.isStrictModeEnabled()).isFalse();
        assertThat(service.getExemptUserids()).containsExactlyInAnyOrder(
                "18809427636", "18709336236", "15213919369", "15209340344", "WeiSiQi");
    }

    @Test
    @DisplayName("开关与豁免名单可写入并读回")
    void 开关与豁免名单可写入并读回() {
        service.setStrictModeEnabled(true);
        service.setExemptUserids(List.of("X1", "X2"));

        assertThat(service.isStrictModeEnabled()).isTrue();
        assertThat(service.getExemptUserids()).containsExactlyInAnyOrder("X1", "X2");
    }
}
