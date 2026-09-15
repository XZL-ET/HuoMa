package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeletionReportService 客户删除员工日报")
class DeletionReportServiceTest {

    @Mock private CustomerDeletionEventRepository deletionRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private SystemConfigRepository configRepository;
    @Mock private WecomApiClient wecomApi;
    @Mock private AlertService alertService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private DeletionReportService reportService;

    private final LocalDate date = LocalDate.of(2026, 9, 13);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reportService, "adminUserids", "admin1,admin2");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private CustomerDeletionEvent event(String externalUserid, String userid, LocalDateTime deletedAt) {
        return CustomerDeletionEvent.builder()
                .externalUserid(externalUserid)
                .userid(userid)
                .direction(CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT)
                .deletedAt(deletedAt)
                .build();
    }

    private List<CustomerDeletionEvent> eventsFor(String userid, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> event("wm-" + userid + "-" + i, userid, date.atTime(9, 0)))
                .toList();
    }

    @Test
    @DisplayName("report — 只列出被删超 5 人的员工（降序），其余合并为员工总数")
    void reportListsOnlyEmployeesExceedingFiveDesc() {
        List<CustomerDeletionEvent> events = new java.util.ArrayList<>();
        events.addAll(eventsFor("agent1", 7));
        events.addAll(eventsFor("agent2", 6));
        events.addAll(eventsFor("agent3", 3));
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(events);
        when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));
        when(employeeRepo.findByUserid("agent2"))
                .thenReturn(Optional.of(Employee.builder().name("赵老师").build()));

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(2);
        verify(wecomApi).sendReportMessage(eq("admin1"),
                org.mockito.ArgumentMatchers.contains("昨日 共 16 位客户删除员工，涉及 3 名员工"));
        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("1. 王老师：7 人"));
        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("2. 赵老师：6 人"));
        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("其余 1 名员工各被删除不超过 5 人"));
        verify(wecomApi).sendReportMessage(eq("admin2"), org.mockito.ArgumentMatchers.contains("1. 王老师：7 人"));
        // 未超 5 人的员工不逐行列出（agent3 未 stub，若被错误列出会以 userid 回退出现）
        verify(wecomApi, never()).sendReportMessage(anyString(), org.mockito.ArgumentMatchers.contains("agent3"));
        // 不推送具体客户 external_userid
        verify(wecomApi, never()).sendReportMessage(anyString(), org.mockito.ArgumentMatchers.contains("wm-agent1"));
    }

    @Test
    @DisplayName("report — 刚好被删 5 人不算超过，归入其余")
    void fiveDeletionsGoesToRest() {
        List<CustomerDeletionEvent> events = new java.util.ArrayList<>();
        events.addAll(eventsFor("agent-six", 6));
        events.addAll(eventsFor("agent-five", 5));
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(events);
        when(employeeRepo.findByUserid("agent-six"))
                .thenReturn(Optional.of(Employee.builder().name("六老师").build()));

        reportService.report(date);

        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("1. 六老师：6 人"));
        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("其余 1 名员工各被删除不超过 5 人"));
        verify(wecomApi, never()).sendReportMessage(anyString(), org.mockito.ArgumentMatchers.contains("agent-five"));
    }

    @Test
    @DisplayName("report — 昨日无删除事件时不推送")
    void reportSkipsPushWhenNoEvents() {
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(0);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("report — 未配置推送对象时不推送")
    void reportSkipsWhenNoAdminConfigured() {
        ReflectionTestUtils.setField(reportService, "adminUserids", "  ");

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(0);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("report — 系统配置里的接收人优先于环境变量")
    void recipientsFromSystemConfigOverrideEnv() {
        ReflectionTestUtils.setField(reportService, "adminUserids", "env-admin");
        when(configRepository.findByConfigKey("deletion_report_admin_userids"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("deletion_report_admin_userids")
                        .configValue("db-admin1,db-admin2")
                        .build()));
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(eventsFor("agent1", 6));
        when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(2);
        verify(wecomApi).sendReportMessage(eq("db-admin1"), org.mockito.ArgumentMatchers.contains("王老师"));
        verify(wecomApi).sendReportMessage(eq("db-admin2"), org.mockito.ArgumentMatchers.contains("王老师"));
        verify(wecomApi, never()).sendReportMessage(eq("env-admin"), anyString());
    }

    @Test
    @DisplayName("report — 系统配置存在但为空时尊重清空，不回退环境变量")
    void emptyConfigOverridesEnv() {
        ReflectionTestUtils.setField(reportService, "adminUserids", "env-admin");
        when(configRepository.findByConfigKey("deletion_report_admin_userids"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("deletion_report_admin_userids")
                        .configValue("")
                        .build()));
        // 事件查询仅在误回退 env 时才会走到，用 lenient 避免修复后被视为无用 stub
        lenient().when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(List.of(event("wm-c1", "agent1", date.atTime(14, 30))));
        lenient().when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(0);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("report — 员工查不到时回退显示 userid")
    void employeeNameFallsBackToUserid() {
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(eventsFor("agent-unknown", 6));
        when(employeeRepo.findByUserid("agent-unknown")).thenReturn(Optional.empty());

        reportService.report(date);

        verify(wecomApi).sendReportMessage(eq("admin1"), org.mockito.ArgumentMatchers.contains("agent-unknown"));
    }

    @Test
    @DisplayName("reportWithLock — 锁被占用时返回 LOCK_BUSY，不推送")
    void reportWithLockReturnsBusyWhenLocked() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        int result = reportService.reportWithLock(date);

        assertThat(result).isEqualTo(DeletionReportService.LOCK_BUSY);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("reportWithLock — 锁空闲时执行推送并释放锁")
    void reportWithLockExecutesAndUnlocks() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(eventsFor("agent1", 6));
        when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        int result = reportService.reportWithLock(date);

        assertThat(result).isEqualTo(2);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("report — 推送给某接收人失败时创建告警")
    void reportCreatesAlertOnPushFailure() {
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(eventsFor("agent1", 6));
        when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));
        doAnswer(invocation -> {
            if ("admin2".equals(invocation.getArgument(0))) {
                throw new RuntimeException("userid not found");
            }
            return null;
        }).when(wecomApi).sendReportMessage(anyString(), anyString());

        int sent = reportService.report(date);

        assertThat(sent).isEqualTo(1);
        verify(alertService).createAlert(eq("admin2"), eq("deletion_report_fail"),
                any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("report — 重点员工超过 50 行时截断并提示未列出")
    void reportTruncatesHighlightedBeyondMaxLines() {
        List<CustomerDeletionEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            events.addAll(eventsFor("agent" + i, 6));
        }
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(events);

        reportService.report(date);

        verify(wecomApi).sendReportMessage(eq("admin1"),
                org.mockito.ArgumentMatchers.contains("50. agent49：6 人"));
        verify(wecomApi).sendReportMessage(eq("admin1"),
                org.mockito.ArgumentMatchers.contains("另有 1 名员工"));
        verify(wecomApi, never()).sendReportMessage(anyString(),
                org.mockito.ArgumentMatchers.contains("agent50"));
    }

    @Test
    @DisplayName("resolvePushTime — 未配置时返回默认 09:00")
    void resolvePushTimeDefaultsToNine() {
        when(configRepository.findByConfigKey(DeletionReportService.TIME_CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertThat(reportService.resolvePushTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("resolvePushTime — 返回配置值")
    void resolvePushTimeReturnsConfigured() {
        when(configRepository.findByConfigKey(DeletionReportService.TIME_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(DeletionReportService.TIME_CONFIG_KEY)
                        .configValue("18:30")
                        .build()));

        assertThat(reportService.resolvePushTime()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    @DisplayName("resolvePushTime — 非法配置回退默认 09:00")
    void resolvePushTimeFallsBackOnInvalid() {
        when(configRepository.findByConfigKey(DeletionReportService.TIME_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(DeletionReportService.TIME_CONFIG_KEY)
                        .configValue("not-a-time")
                        .build()));

        assertThat(reportService.resolvePushTime()).isEqualTo(LocalTime.of(9, 0));
    }

    // ============ 今日推送 ============

    @Test
    @DisplayName("reportTodayUntilNow — 推送今日截至现在的汇总给传入接收人，不读环境变量接收人")
    void reportTodayUntilNowPushesToGivenRecipients() {
        ReflectionTestUtils.setField(reportService, "adminUserids", "");
        List<CustomerDeletionEvent> events = new java.util.ArrayList<>();
        events.addAll(eventsFor("agent1", 7));
        when(deletionRepo.findByDirectionAndDeletedAtBetween(any(), any(), any()))
                .thenReturn(events);
        when(employeeRepo.findByUserid("agent1"))
                .thenReturn(Optional.of(Employee.builder().name("王老师").build()));

        int sent = reportService.reportTodayUntilNow("boss1, boss2");

        assertThat(sent).isEqualTo(2);
        verify(deletionRepo).findByDirectionAndDeletedAtBetween(
                eq(CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT), any(), any());
        verify(wecomApi).sendReportMessage(eq("boss1"),
                org.mockito.ArgumentMatchers.contains("今日截至"));
        verify(wecomApi).sendReportMessage(eq("boss1"),
                org.mockito.ArgumentMatchers.contains("1. 王老师：7 人"));
        verify(wecomApi).sendReportMessage(eq("boss2"),
                org.mockito.ArgumentMatchers.contains("1. 王老师：7 人"));
    }

    @Test
    @DisplayName("reportTodayUntilNow — 接收人为空时不推送")
    void reportTodayUntilNowSkipsWhenNoRecipients() {
        int sent = reportService.reportTodayUntilNow("  , , ");

        assertThat(sent).isEqualTo(0);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("reportTodayWithLock — 锁被占用时返回 LOCK_BUSY")
    void reportTodayWithLockReturnsBusyWhenLocked() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        int result = reportService.reportTodayWithLock("boss1");

        assertThat(result).isEqualTo(DeletionReportService.LOCK_BUSY);
        verify(wecomApi, never()).sendReportMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("saveTodayRecipients — 规范化后保存，getTodayRecipients 读回")
    void saveAndGetTodayRecipients() {
        when(configRepository.findByConfigKey(DeletionReportService.TODAY_RECIPIENTS_CONFIG_KEY))
                .thenReturn(Optional.empty());

        reportService.saveTodayRecipients("  boss1 , boss2 ,, ");

        org.mockito.ArgumentCaptor<SystemConfig> captor =
                org.mockito.ArgumentCaptor.forClass(SystemConfig.class);
        verify(configRepository).save(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("boss1,boss2");
    }

    @Test
    @DisplayName("getTodayRecipients — 未配置时返回空串")
    void getTodayRecipientsDefaultsEmpty() {
        when(configRepository.findByConfigKey(DeletionReportService.TODAY_RECIPIENTS_CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertThat(reportService.getTodayRecipients()).isEqualTo("");
    }
}
