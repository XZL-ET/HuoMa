package com.bookstore.qrcode.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 欢迎语发送时间窗口的纯逻辑边界测试。
 *
 * <p>窗口定义：早 08:30 起，晚 21:00 止（不含 21:00）。深夜/凌晨不发送，避免打扰客户。</p>
 */
class TransferServiceGreetingWindowTest {

    @Test
    @DisplayName("08:30 前不发送")
    void shouldReturnFalseBefore0830() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(8, 29, 59))).isFalse();
    }

    @Test
    @DisplayName("08:30 起可发送")
    void shouldReturnTrueFrom0830() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(8, 30))).isTrue();
    }

    @Test
    @DisplayName("20:59 仍可发送")
    void shouldReturnTrueBefore2100() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(20, 59, 59))).isTrue();
    }

    @Test
    @DisplayName("21:00 起不发送")
    void shouldReturnFalseFrom2100() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(21, 0))).isFalse();
    }

    @Test
    @DisplayName("白天（12:00）可发送")
    void shouldReturnTrueDuringDay() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(12, 0))).isTrue();
    }

    @Test
    @DisplayName("深夜（23:00）不发送")
    void shouldReturnFalseLateNight() {
        assertThat(TransferService.isWithinGreetingWindow(LocalTime.of(23, 0))).isFalse();
    }
}
