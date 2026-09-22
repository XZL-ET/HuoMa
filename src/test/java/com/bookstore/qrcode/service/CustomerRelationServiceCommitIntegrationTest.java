package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
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
 * 坐实「并发冲突恢复的提交语义」：冲突后 catch 里的 {@code save(winner)} 补写是否真实落库、
 * 且不抛 {@code UnexpectedRollbackException}。
 *
 * <p>这是全上下文（{@code @SpringBootTest}）集成测试——因为 {@link CustomerRelationInsertService}
 * 的 {@code REQUIRES_NEW} 依赖 Spring 的 {@code @Transactional} 代理，而 {@code @DataJpaTest}
 * 关闭了自动配置、不产生事务代理。用 {@link TransactionTemplate} 真实提交，观测冲突恢复是否落库。
 */
@Import(WecomApiMockConfig.class)
@DisplayName("CustomerRelationService 冲突恢复提交语义")
class CustomerRelationServiceCommitIntegrationTest extends BaseIntegrationTest {

    @Autowired private CustomerRelationService customerRelationService;
    @Autowired private CustomerRepository customerRepo;
    @SpyBean private CustomerRelationRepository relationRepo;
    @Autowired private TransactionTemplate txTemplate;

    @BeforeEach
    void cleanUp() {
        relationRepo.deleteAll();
        customerRepo.deleteAll();
    }

    @Test
    @DisplayName("并发冲突后 save(winner) 的来源回填应真实落库、且不抛异常")
    void conflictRecoveryCommitsWinnerBackfill() {
        // 1. 种子：customer + 一条 (customer, recA) 关系，来源为 null（已提交）
        Long customerId = txTemplate.execute(s -> {
            Customer c = customerRepo.save(Customer.builder()
                    .externalUserid("wm-commit").name("客户").type(1)
                    .status(Customer.CustomerStatus.active).addTime(LocalDateTime.now()).build());
            relationRepo.saveAndFlush(CustomerRelation.builder()
                    .customerId(c.getId()).employeeUserid("recA")
                    .status(RelationStatus.active).build());
            return c.getId();
        });

        // 2. 预取赢家（提交后脱离事务，成为 detached 实体）
        CustomerRelation winner = txTemplate.execute(s ->
                relationRepo.findByCustomerIdAndEmployeeUserid(customerId, "recA").orElseThrow());

        // 3. 首次 findBy 强制查空 → 服务走 INSERT 路径，REQUIRES_NEW 插入撞唯一键冲突
        doReturn(Optional.empty())
                .doReturn(Optional.of(winner))
                .when(relationRepo).findByCustomerIdAndEmployeeUserid(customerId, "recA");

        // 4. 在「真实提交」的事务里跑 upsertActive，捕获是否抛异常
        Throwable thrown = null;
        try {
            txTemplate.execute(s -> {
                customerRelationService.upsertActive(customerId, "recA", 10L, "SCHOOL-2", LocalDateTime.now());
                return null;
            });
        } catch (Throwable e) {
            thrown = e;
        }

        // 5. 新事务重读，观测赢家来源是否真的落库
        CustomerRelation reloaded = txTemplate.execute(s ->
                relationRepo.findById(winner.getId()).orElseThrow());

        assertThat(thrown).as("upsertActive 不应抛异常").isNull();
        assertThat(reloaded.getQrCodeId()).as("冲突恢复的来源回填应落库").isEqualTo(10L);
        assertThat(reloaded.getSchoolId()).isEqualTo("SCHOOL-2");
    }
}
