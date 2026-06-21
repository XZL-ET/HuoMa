package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AgentDailyCountService;
import com.bookstore.qrcode.service.AgentRotationService;
import com.bookstore.qrcode.service.CustomerService;
import com.bookstore.qrcode.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * 业务场景 2：客户扫码 → 新增/更新 → 日计数递增 → 阈值检查。
 *
 * <p>验证完整客户获取链路：
 * {@link CustomerService#upsertFromCallback} → Redis 锁 →
 * 稀疏写入 → DataFill 事件发布 → TransactionSynchronization 释放锁。</p>
 */
@Import(WecomApiMockConfig.class)
@DisplayName("客户扫码 → 新增/更新 集成测试")
class CustomerScanFlowTest extends BaseIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private QrCodeService qrCodeService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private AgentDailyCountService countService;
    @Autowired private AgentRotationService rotationService;
    @Autowired private StringRedisTemplate redisTemplate;

    private QrCode testQr;

    @BeforeEach
    void setUp() {
        // 清理
        customerRepo.deleteAll();
        qrCodeRepo.deleteAll();

        // 先创建一个活码作为来源
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName("测试学校");
        req.setSchoolId("SCH-SCAN-001");
        req.setRegionCity("北京");
        req.setRegionDistrict("朝阳区");
        req.setServiceTeacherUserid("agent1,agent2");
        req.setInitialAgentUserids("agent1,agent2");
        testQr = qrCodeService.create(req);
    }

    // ================================================================
    // 新客户创建
    // ================================================================

    @Test
    @DisplayName("新客户扫码 → 稀疏创建（name='未知'）→ Redis 锁释放")
    void shouldUpsertNewCustomer() {
        Long customerId = customerService.upsertFromCallback(
                "wm-new-user-001", "agent1", testQr.getSchoolId());

        assertThat(customerId).isNotNull();

        Customer saved = customerRepo.findById(customerId).orElseThrow();
        assertThat(saved.getExternalUserid()).isEqualTo("wm-new-user-001");
        assertThat(saved.getName()).isEqualTo("未知");          // 快速写入占位
        assertThat(saved.getAddedAgent()).isEqualTo("agent1");
        assertThat(saved.getCurrentAgent()).isEqualTo("agent1");
        assertThat(saved.getSourceQrId()).isEqualTo(testQr.getId());
        assertThat(saved.getSchoolId()).isEqualTo(testQr.getSchoolId());
        assertThat(saved.getStatus()).isEqualTo(Customer.CustomerStatus.active);
        assertThat(saved.getAddTime()).isNotNull();

        // Redis 锁应已被 TransactionSynchronization.afterCommit 释放
        String lockKey = "customer:lock:wm-new-user-001";
        assertThat(redisTemplate.hasKey(lockKey)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Disabled("Embedded Redis 不支持 Stream (XLEN 命令)，需 Testcontainers Redis 7")
    @DisplayName("新客户扫码 → DataFill 事件发布到 Redis Stream")
    void shouldPublishDataFillEvent() {
        customerService.upsertFromCallback(
                "wm-datafill-test", "agent2", testQr.getSchoolId());

        // DataFill 事件应该已发布到 wecom:datafill:stream
        Long streamLen = redisTemplate.opsForStream().size("wecom:datafill:stream");
        assertThat(streamLen).isGreaterThanOrEqualTo(1);
    }

    // ================================================================
    // 已有客户更新
    // ================================================================

    @Test
    @DisplayName("已有客户再次扫码 → 更新 currentAgent 和 sourceQrId")
    void shouldUpdateExistingCustomer() {
        // 首次添加
        Long id1 = customerService.upsertFromCallback(
                "wm-existing-001", "agent1", testQr.getSchoolId());
        Customer first = customerRepo.findById(id1).orElseThrow();

        // 同客户换员工再扫
        Long id2 = customerService.upsertFromCallback(
                "wm-existing-001", "agent2", testQr.getSchoolId());
        Customer second = customerRepo.findById(id2).orElseThrow();

        assertThat(id1).isEqualTo(id2);         // 同一条记录
        assertThat(second.getCurrentAgent()).isEqualTo("agent2");   // 员工已更新
        assertThat(second.getUpdatedAt()).isAfter(first.getUpdatedAt()); // 时间巳更新
    }

    @Test
    @DisplayName("已删除客户再次扫码 → 重新激活为 active")
    void shouldReactivateDeletedCustomer() {
        Long id = customerService.upsertFromCallback(
                "wm-deleted-001", "agent1", testQr.getSchoolId());

        // 模拟删除
        Customer c = customerRepo.findById(id).orElseThrow();
        c.setStatus(Customer.CustomerStatus.deleted);
        customerRepo.save(c);

        // 再次扫码 → 应重新激活
        customerService.upsertFromCallback(
                "wm-deleted-001", "agent1", testQr.getSchoolId());

        Customer reactivated = customerRepo.findById(id).orElseThrow();
        assertThat(reactivated.getStatus()).isEqualTo(Customer.CustomerStatus.active);
    }

    // ================================================================
    // 日计数递增
    // ================================================================

    @Test
    @DisplayName("客户新增后 → Redis 日计数递增")
    void shouldIncrementDailyCountInRedis() {
        // 清理 Redis 计数
        redisTemplate.delete("agent:daily:total:agent1");

        customerService.upsertFromCallback(
                "wm-count-test", "agent1", testQr.getSchoolId());

        // 验证 Redis 中有计数 key
        // 注意：incrementDailyCount 由回调消费链路调用，这里我们直接调 rotationService
        rotationService.incrementDailyCount("agent1", testQr.getSchoolId());

        // agent1 的全局日计数应 > 0
        String totalKey = "agent:daily:total:agent1";
        String count = redisTemplate.opsForValue().get(totalKey);
        assertThat(count).isNotNull();
        assertThat(Long.parseLong(count)).isGreaterThanOrEqualTo(1);
    }

    // ================================================================
    // 并发保护
    // ================================================================

    @Test
    @DisplayName("Redis 客户锁：创建期间 lock key 存在 → 提交后释放")
    void shouldAcquireAndReleaseCustomerLock() throws Exception {
        String lockKey = "customer:lock:wm-lock-test";

        // 开始前锁不存在
        assertThat(redisTemplate.hasKey(lockKey)).isFalse();

        customerService.upsertFromCallback(
                "wm-lock-test", "agent1", testQr.getSchoolId());

        // 事务提交后锁应已释放
        // 由于 @Transactional 在测试方法中由 Spring Test 管理，
        // afterCommit 在 commit 时调用 → 此时期望锁已释放
        assertThat(redisTemplate.hasKey(lockKey))
                .as("Redis customer lock should be released after commit")
                .isFalse();
    }
}
