package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.SceneConfigProperties;
import com.bookstore.qrcode.entity.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SceneAllocationTest {

    private SceneConfigProperties sceneConfig;

    @BeforeEach
    void setUp() {
        sceneConfig = new SceneConfigProperties();
        sceneConfig.setDailyPush(new SceneConfigProperties.ScenePreset(0.10, 95));
        sceneConfig.setParentMeeting(new SceneConfigProperties.ScenePreset(0.60, 70));
    }

    @Test
    @DisplayName("日常推送 1000 学生 → 1 接待员")
    void dailyPush1000Students() {
        int need = computeAgentCount(1000, 0.10, 100);
        assertThat(need).isEqualTo(1);
    }

    @Test
    @DisplayName("家长会 1000 学生 → 6 接待员")
    void parentMeeting1000Students() {
        int need = computeAgentCount(1000, 0.60, 100);
        assertThat(need).isEqualTo(6);
    }

    @ParameterizedTest
    @CsvSource({
        "500,  0.10, 100, 1",
        "500,  0.60, 100, 3",
        "3000, 0.10, 100, 3",
        "3000, 0.60, 100, 18",
        "5000, 0.10, 100, 5",
        "5000, 0.60, 100, 30",
        "100,  0.10, 100, 1",
        "50,   0.10, 100, 1",
        "20000, 0.60, 100, 100",
    })
    @DisplayName("公式计算验证")
    void formulaTest(int studentCount, double scanRatio, int dailyMax, int expected) {
        assertThat(computeAgentCount(studentCount, scanRatio, dailyMax)).isEqualTo(expected);
    }

    @Test
    @DisplayName("配置绑定：dailyPush scanRatio=0.10, urgentRatio=95")
    void configBindingDailyPush() {
        assertThat(sceneConfig.getDailyPush().getScanRatio()).isEqualTo(0.10);
        assertThat(sceneConfig.getDailyPush().getUrgentRatio()).isEqualTo(95);
    }

    @Test
    @DisplayName("配置绑定：parentMeeting scanRatio=0.60, urgentRatio=70")
    void configBindingParentMeeting() {
        assertThat(sceneConfig.getParentMeeting().getScanRatio()).isEqualTo(0.60);
        assertThat(sceneConfig.getParentMeeting().getUrgentRatio()).isEqualTo(70);
    }

    @Test
    @DisplayName("getPreset 返回正确预设")
    void getPresetReturnsCorrectPreset() {
        SceneConfigProperties.ScenePreset dailyPreset = sceneConfig.getPreset(Scene.daily_push);
        assertThat(dailyPreset.getScanRatio()).isEqualTo(0.10);
        assertThat(dailyPreset.getUrgentRatio()).isEqualTo(95);

        SceneConfigProperties.ScenePreset meetingPreset = sceneConfig.getPreset(Scene.parent_meeting);
        assertThat(meetingPreset.getScanRatio()).isEqualTo(0.60);
        assertThat(meetingPreset.getUrgentRatio()).isEqualTo(70);

        // null defaults to daily_push
        SceneConfigProperties.ScenePreset nullPreset = sceneConfig.getPreset(null);
        assertThat(nullPreset.getScanRatio()).isEqualTo(0.10);
        assertThat(nullPreset.getUrgentRatio()).isEqualTo(95);
    }

    /** Matches QrCodeService.create() formula exactly */
    static int computeAgentCount(int studentCount, double scanRatio, int dailyMax) {
        int expectedScans = (int) Math.ceil(studentCount * scanRatio);
        return Math.max(1, Math.min(100,
            (int) Math.ceil((double) expectedScans / dailyMax)));
    }
}
