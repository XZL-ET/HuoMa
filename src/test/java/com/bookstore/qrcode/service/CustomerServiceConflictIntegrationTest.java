package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 坐实 {@link CustomerService#upsertFromCallback} 客户并发插入冲突的提交语义：
 * 冲突后 catch 里的重查更新是否真实落库、且不抛 {@code UnexpectedRollbackException}。
 *
 * <p>全上下文集成测试（{@link CustomerInsertService} 的 {@code REQUIRES_NEW} 依赖事务代理）。
 * 用 {@link TransactionTemplate} 真实提交，观测冲突恢复是否落库。
 */
@Import(WecomApiMockConfig.class)
@DisplayName("CustomerService 回调并发插入冲突提交语义")
class CustomerServiceConflictIntegrationTest extends BaseIntegrationTest {

    @Autowired private CustomerService customerService;
    @SpyBean private CustomerRepository customerRepo;
    @Autowired private TransactionTemplate txTemplate;

    @BeforeEach
    void cleanUp() {
        customerRepo.deleteAll();
    }

    @Test
    @DisplayName("并发插入冲突后重查更新应真实落库、且不抛异常")
    void conflictRecoveryCommitsWinnerUpdate() {
        // 1. 种子：external_userid 已存在（已提交）
        txTemplate.execute(s -> {
            customerRepo.save(Customer.builder()
                    .externalUserid("wm-cust-conflict").name("客户").type(1)
                    .status(Customer.CustomerStatus.active).addTime(LocalDateTime.now()).build());
            return null;
        });

        // 2. 预取赢家（提交后脱离事务，成为 detached 实体）
        Customer winner = txTemplate.execute(s ->
                customerRepo.findByExternalUserid("wm-cust-conflict").orElseThrow());

        // 3. 首次 findByExternalUserid 强制查空 → 走新客户 INSERT 路径，REQUIRES_NEW 插入撞唯一键冲突
        doReturn(Optional.empty())
                .doReturn(Optional.of(winner))
                .when(customerRepo).findByExternalUserid("wm-cust-conflict");

        // 4. 在「真实提交」的事务里跑 upsertFromCallback，捕获是否抛异常
        Throwable thrown = null;
        try {
            txTemplate.execute(s -> {
                customerService.upsertFromCallback("wm-cust-conflict", "agent-new", null);
                return null;
            });
        } catch (Throwable e) {
            thrown = e;
        }

        // 5. 新事务重读，观测归属更新是否落库
        Customer reloaded = txTemplate.execute(s ->
                customerRepo.findById(winner.getId()).orElseThrow());

        assertThat(thrown).as("upsertFromCallback 不应抛异常").isNull();
        assertThat(reloaded.getCurrentAgent()).as("冲突恢复的归属更新应落库").isEqualTo("agent-new");
    }
}
