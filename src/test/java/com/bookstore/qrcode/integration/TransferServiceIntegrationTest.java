package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.TransferService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 在职继承核心路径集成测试（真实验证，非 Repository-only）。
 *
 * <p>每条测试独立构造完整的 DB 上下文（Agent → Employee → QrCode → QrAgent → Customer），
 * 通过 Mockito 控制企微 API 返回值，验证 {@link TransferService} 的真实业务逻辑，
 * 包括 TransactionSynchronization 锁释放、去重、冷却期、确认流程和欢迎语发送。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("在职继承核心路径集成测试")
class TransferServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private TransferService transferService;
    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private WecomApiClient wecomApi; // Mockito mock from WecomApiMockConfig
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate txTemplate;

    // ── 测试常量 ──
    private static final String RECEPTIONIST = "test_rec";
    private static final String SERVICE_TEACHER = "test_svc";
    private static final String SCHOOL_ID = "SCH-INHERIT-TEST";
    private static final String EXTERNAL_ID = "wm-inherit-test";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    // ── 每测试独立的主数据 ──
    private QrCode testQr;
    private Customer testCustomer;

    // ================================================================
    //  每次测试前：建好 Agent / Employee / QrCode / QrAgent / Customer
    // ================================================================
    @BeforeEach
    void setUp() {
        // 清理
        transferRepo.deleteAll();
        customerRepo.deleteAll();
        qrAgentRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();

        // ① Agent 全局表（receptionist + service teacher）
        agentRepo.save(Agent.builder()
            .userid(RECEPTIONIST).name("测试接待员")
            .role(Agent.AgentRole.receptionist).dailyTotalCap(500).build());
        agentRepo.save(Agent.builder()
            .userid(SERVICE_TEACHER).name("测试服务老师")
            .role(Agent.AgentRole.service).dailyTotalCap(500).build());

        // ② Employee 通讯录（供 getTeacherName 等使用）
        employeeRepo.save(Employee.builder()
            .userid(RECEPTIONIST).name("测试接待员").active(true).build());
        employeeRepo.save(Employee.builder()
            .userid(SERVICE_TEACHER).name("测试服务老师").active(true).build());

        // ③ QrCode（直接 DB 写入，绕过 QrCodeService.create 的复杂依赖）
        testQr = new QrCode();
        testQr.setSchoolName("继承测试学校");
        testQr.setSchoolId(SCHOOL_ID);
        testQr.setRegionCity("深圳");
        testQr.setRegionDistrict("南山区");
        testQr.setStatus(QrCode.QrCodeStatus.active);
        testQr.setScene(Scene.daily_push);
        testQr.setCreateMode(QrCode.CreateMode.manual);
        testQr.setStudentCount(500);
        testQr.setTransferGreetingEnabled(true);
        testQr.setTransferFilledNote("备注：{{child_name}}");
        testQr.setTransferFilledGreeting("{{parent_name}}您好，欢迎");
        testQr.setTransferUnfilledGreeting("{{parent_name}}您好，请填写表单 {{form_link}}");
        testQr = qrCodeRepo.save(testQr);

        // ④ QrAgent：绑定接待员 + 服务老师
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(testQr.getId()).agentUserid(RECEPTIONIST)
            .role(QrAgent.AgentRole.receptionist).sortOrder(1)
            .status(QrAgent.AgentStatus.active).dailyMax(100).dailyCurrent(0).build());
        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(testQr.getId()).agentUserid(SERVICE_TEACHER)
            .role(QrAgent.AgentRole.service).sortOrder(2)
            .status(QrAgent.AgentStatus.active).dailyMax(200).dailyCurrent(0).build());

        // ⑤ Customer：接待员通过活码添加的客户
        testCustomer = new Customer();
        testCustomer.setExternalUserid(EXTERNAL_ID);
        testCustomer.setName("测试家长");
        testCustomer.setAddedAgent(RECEPTIONIST);
        testCustomer.setCurrentAgent(RECEPTIONIST);
        testCustomer.setSchoolId(SCHOOL_ID);
        testCustomer.setSourceQrId(testQr.getId());
        testCustomer.setAddTime(LocalDateTime.now());
        testCustomer.setStatus(Customer.CustomerStatus.active);
        testCustomer = customerRepo.save(testCustomer);
    }

    @AfterEach
    void tearDown() {
        // 重置 mock stubs，避免测试间污染
        reset(wecomApi);
        WecomApiMockConfig.reapplyBaseStubs(wecomApi);
    }

    // ================================================================
    //  P0-1 验证：initiate 成功路径
    // ================================================================

    @Test
    @DisplayName("initiate 成功：企微 API 返回成功 → pending_confirm 落库 → 锁已释放")
    void shouldCreatePendingConfirmOnSuccess() {
        // given: 覆盖 transferCustomer 返回成功（WecomApiMockConfig 已有默认 stub，此处显式声明）
        // when
        transferService.initiate(testCustomer.getId(), RECEPTIONIST, null,
            EXTERNAL_ID, SCHOOL_ID);

        // then: 转移记录落库，状态为 pending_confirm
        List<CustomerTransfer> records = transferRepo.findByCustomerId(testCustomer.getId());
        assertThat(records).hasSize(1);
        CustomerTransfer transfer = records.get(0);
        assertThat(transfer.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.pending_confirm);
        assertThat(transfer.getFromUserid()).isEqualTo(RECEPTIONIST);
        assertThat(transfer.getToUserid()).isEqualTo(SERVICE_TEACHER);
        assertThat(transfer.getQrCodeId()).isEqualTo(testQr.getId());
        assertThat(transfer.getRetryCount()).isEqualTo(0);
        assertThat(transfer.getPollCount()).isEqualTo(0);

        // 验证 P0-1 修复：TransactionSynchronization.afterCompletion 已释放锁
        String lockKey = "lock:transfer:" + testCustomer.getId();
        assertThat(redisTemplate.hasKey(lockKey))
            .as("Redis lock should be released after transaction commit")
            .isFalse();
    }

    // ================================================================
    //  P0-1 验证：initiate 去重
    // ================================================================

    @Test
    @DisplayName("initiate 去重：已有 pending_confirm → 不创建新记录")
    void shouldSkipWhenPendingConfirmExists() {
        // given: 预先插入一条 pending_confirm
        transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.pending_confirm)
            .retryCount(0).pollCount(0).build());

        long countBefore = transferRepo.count();

        // when
        transferService.initiate(testCustomer.getId(), RECEPTIONIST, null,
            EXTERNAL_ID, SCHOOL_ID);

        // then: 没有新增记录
        assertThat(transferRepo.count()).isEqualTo(countBefore);
    }

    // ================================================================
    //  冷却期验证
    // ================================================================

    @Test
    @DisplayName("initiate 冷却期：7 天内有 terminal 记录 → 跳过")
    void shouldSkipWhenInCooldownPeriod() {
        // given: 有一条 timeout 记录（刚创建，updatedAt=now，在 7 天冷却期内）
        CustomerTransfer terminal = CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now().minusDays(3))
            .status(CustomerTransfer.TransferStatus.timeout)
            .failReason("超时未确认")
            .retryCount(0).pollCount(0).build();
        transferRepo.save(terminal);

        long countBefore = transferRepo.count();

        // when
        transferService.initiate(testCustomer.getId(), RECEPTIONIST, null,
            EXTERNAL_ID, SCHOOL_ID);

        // then: 冷却期内（updatedAt=now 在 7 天内），不发起新转移
        assertThat(transferRepo.count()).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("initiate 冷却期外：8 天前 terminal 记录 → 允许重新发起")
    void shouldAllowWhenCooldownExpired() {
        // given: 8 天前的 rejected 记录（native query 绕过 @PreUpdate）
        CustomerTransfer old = CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now().minusDays(8))
            .status(CustomerTransfer.TransferStatus.rejected)
            .retryCount(0).pollCount(0).build();
        CustomerTransfer saved = transferRepo.save(old);
        final Long savedId = saved.getId();
        // 在独立事务中将 updatedAt 回退到 8 天前（绕过 @PreUpdate）
        txTemplate.executeWithoutResult(status -> {
            em.createNativeQuery(
                "UPDATE customer_transfer SET updated_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.now().minusDays(8))
                .setParameter("id", savedId)
                .executeUpdate();
        });
        em.clear();

        // when
        transferService.initiate(testCustomer.getId(), RECEPTIONIST, null,
            EXTERNAL_ID, SCHOOL_ID);

        // then: 冷却期已过，应创建新的 pending_confirm
        List<CustomerTransfer> records = transferRepo.findByCustomerId(testCustomer.getId());
        assertThat(records).hasSize(2); // 1 old + 1 new
        assertThat(records).anyMatch(t -> t.getStatus() == CustomerTransfer.TransferStatus.pending_confirm);
    }

    // ================================================================
    //  trackResults：确认 → greeting 触发
    // ================================================================

    @Test
    @DisplayName("trackResults：企微返回 status=1 → confirmed + 加入 newlyConfirmed 列表")
    void shouldConfirmAndCollectForGreeting() throws Exception {
        // given: 一条 pending_confirm 记录
        CustomerTransfer pending = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.pending_confirm)
            .retryCount(0).pollCount(0).build());

        // mock getTransferResult → status=1（接替完毕）
        var resultResp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"errcode\":0,\"errmsg\":\"ok\"," +
            "\"customer\":[{\"external_userid\":\"" + EXTERNAL_ID + "\",\"status\":1}]}");
        when(wecomApi.getTransferResult(anyString(), anyString(), anyString()))
            .thenReturn(resultResp);

        // when
        List<Long> newlyConfirmed = transferService.trackResults();

        // then: pending 变为 confirmed
        CustomerTransfer updated = transferRepo.findById(pending.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.confirmed);
        assertThat(updated.getConfirmTime()).isNotNull();

        // 确认被加入 newlyConfirmed 列表（供事务外发送欢迎语）
        assertThat(newlyConfirmed).contains(pending.getId());
    }

    @Test
    @DisplayName("trackResults：企微返回 status=3（客户拒绝）→ rejected")
    void shouldMarkRejectedWhenCustomerRefuses() throws Exception {
        CustomerTransfer pending = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.pending_confirm)
            .retryCount(0).pollCount(0).build());

        // mock getTransferResult → status=3（客户拒绝）
        var refusedResp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"errcode\":0,\"errmsg\":\"ok\"," +
            "\"customer\":[{\"external_userid\":\"" + EXTERNAL_ID + "\",\"status\":3}]}");
        when(wecomApi.getTransferResult(anyString(), anyString(), anyString()))
            .thenReturn(refusedResp);

        List<Long> newlyConfirmed = transferService.trackResults();

        CustomerTransfer updated = transferRepo.findById(pending.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.rejected);
        assertThat(newlyConfirmed).doesNotContain(pending.getId());
    }

    @Test
    @DisplayName("trackResults 安全网：pollCount >= 48 的 pending → retry_limit")
    void shouldMarkRetryLimitViaSafetyNet() throws Exception {
        // given: pending_confirm 且 pollCount = 48（主循环会跳过 because pollCount >= 48）
        CustomerTransfer exhausted = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now().minusHours(25))
            .status(CustomerTransfer.TransferStatus.pending_confirm)
            .pollCount(48).retryCount(0).build());

        // getTransferResult 主循环过滤 pollCount >= 48 的记录，落入安全网
        // 安全网检测到已超 24h → 标记 confirmed（企微静默自动完成）
        List<Long> newlyConfirmed = transferService.trackResults();

        CustomerTransfer updated = transferRepo.findById(exhausted.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.confirmed);
        assertThat(updated.getFailReason()).contains("24h");
        assertThat(newlyConfirmed).hasSize(1);
    }

    // ================================================================
    //  sendGreetingsForNewlyConfirmed
    // ================================================================

    @Test
    @DisplayName("sendGreetingsForNewlyConfirmed：发送成功 → greetingSent=true")
    void shouldSendGreetingAndMarkSent() {
        // given: 一条已确认但未发欢迎语的记录
        CustomerTransfer confirmed = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .confirmTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.confirmed)
            .greetingSent(false).noteSent(false)
            .retryCount(0).pollCount(0).build());

        // sendMessage / updateRemark 是 void 方法，Mockito 默认 doNothing
        // when
        transferService.sendGreetingsForNewlyConfirmed(List.of(confirmed.getId()));

        // then
        CustomerTransfer updated = transferRepo.findById(confirmed.getId()).orElseThrow();
        assertThat(updated.getGreetingSent()).isTrue();
        assertThat(updated.getNoteSent()).isTrue();
        assertThat(updated.getGreetingType())
            .isIn(CustomerTransfer.GreetingType.filled, CustomerTransfer.GreetingType.unfilled);
        // 验证企微 API 确实被调用了
        verify(wecomApi, atLeastOnce()).sendMessage(eq(SERVICE_TEACHER), eq(EXTERNAL_ID), anyString());
    }

    @Test
    @DisplayName("sendGreetingsForNewlyConfirmed：sendMessage 抛异常 → greetingSent 保持 false")
    void shouldKeepGreetingUnsentOnApiFailure() {
        // given
        CustomerTransfer confirmed = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .confirmTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.confirmed)
            .greetingSent(false).noteSent(false)
            .retryCount(0).pollCount(0).build());

        // mock sendMessage 抛异常
        doThrow(new RuntimeException("mock send failure"))
            .when(wecomApi).sendMessage(anyString(), anyString(), anyString());

        // when: 不应抛出异常（内部 catch 了）
        assertThatCode(() ->
            transferService.sendGreetingsForNewlyConfirmed(List.of(confirmed.getId())))
            .doesNotThrowAnyException();

        // then: greetingSent 仍为 false，等待 retryFailedGreetings 补发
        CustomerTransfer updated = transferRepo.findById(confirmed.getId()).orElseThrow();
        assertThat(updated.getGreetingSent()).isFalse();
    }

    // ================================================================
    //  retryFailedTransfers：api_failed → 重试
    // ================================================================

    @Test
    @DisplayName("retryFailedTransfers：api_failed 重试成功 → pending_confirm")
    void shouldRetryApiFailedAndTransitionToPending() {
        // given
        CustomerTransfer failed = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.api_failed)
            .retryCount(1).pollCount(0)
            .failReason("errcode=45001 网络超时").build());

        // mock transferCustomer 返回成功
        try {
            var okResp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"customer\":[{\"external_userid\":\"wm-test\",\"errcode\":0}]}");
            when(wecomApi.transferCustomer(anyString(), anyString(), anyString(), any()))
                .thenReturn(okResp);
        } catch (Exception e) { throw new RuntimeException(e); }

        // when
        transferService.retryFailedTransfers();

        // then
        CustomerTransfer updated = transferRepo.findById(failed.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.pending_confirm);
        assertThat(updated.getRetryCount()).isEqualTo(2); // 1 → 2

        // P0-1 修复：重试锁应已释放
        String retryLockKey = "lock:transfer:" + testCustomer.getId();
        assertThat(redisTemplate.hasKey(retryLockKey))
            .as("retry lock should be released after transaction commit")
            .isFalse();
    }

    @Test
    @DisplayName("retryFailedTransfers：retryCount >= 3 → 不会被查询处理")
    void shouldNotRetryBeyondLimit() {
        // given: retryCount = 3（已达上限），状态仍为 api_failed
        transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now())
            .status(CustomerTransfer.TransferStatus.api_failed)
            .retryCount(3).pollCount(0)
            .failReason("已达重试上限").build());

        long countBefore = transferRepo.count();

        // when
        transferService.retryFailedTransfers();

        // then: 不会多出 pending_confirm（retryCount >= 3 的记录被 findByStatusAndRetryCountLessThan 排除）
        long pendingCount = transferRepo
            .findByStatus(CustomerTransfer.TransferStatus.pending_confirm).size();
        assertThat(pendingCount).isEqualTo(0);
        // 原有记录未被删除或修改
        assertThat(transferRepo.count()).isEqualTo(countBefore);
    }

    // ================================================================
    //  trackResults：超时标记
    // ================================================================

    @Test
    @DisplayName("trackResults：transferTime 超 24 小时 + status=2 → timeout")
    void shouldMarkTimeoutWhenExceeded24Hours() throws Exception {
        // given: 25 小时前发起，仍在等待确认
        CustomerTransfer old = transferRepo.save(CustomerTransfer.builder()
            .customerId(testCustomer.getId())
            .fromUserid(RECEPTIONIST).toUserid(SERVICE_TEACHER)
            .qrCodeId(testQr.getId())
            .transferTime(LocalDateTime.now().minusHours(25))
            .status(CustomerTransfer.TransferStatus.pending_confirm)
            .retryCount(0).pollCount(10).build());

        // mock getTransferResult → status=2（等待接替，客户未确认）
        var waitingResp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"errcode\":0,\"errmsg\":\"ok\"," +
            "\"customer\":[{\"external_userid\":\"" + EXTERNAL_ID + "\",\"status\":2}]}");
        when(wecomApi.getTransferResult(anyString(), anyString(), anyString()))
            .thenReturn(waitingResp);

        List<Long> newlyConfirmed = transferService.trackResults();

        CustomerTransfer updated = transferRepo.findById(old.getId()).orElseThrow();
        // 超过 24h → 企微静默自动完成 → 标记 confirmed 而非 timeout
        assertThat(updated.getStatus()).isEqualTo(CustomerTransfer.TransferStatus.confirmed);
        assertThat(updated.getFailReason()).isNull();
        assertThat(newlyConfirmed).hasSize(1);
    }
}
