package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.CustomerTransfer.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerTransferRepository 去重维度")
class CustomerTransferRepositoryTest {

    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private TestEntityManager em;

    private Customer c;

    @BeforeEach
    void setUp() {
        c = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-1").name("客户").type(1)
                .status(Customer.CustomerStatus.active).addTime(LocalDateTime.now()).build());
        // c 在活码 10 有一条 confirmed 转移；活码 20 无
        transferRepo.save(CustomerTransfer.builder()
                .customerId(c.getId()).fromUserid("recA").toUserid("svcA")
                .qrCodeId(10L).status(TransferStatus.confirmed).build());
    }

    @Test
    @DisplayName("按 (customer, qr) 去重 — 不同活码互不遮挡")
    void dedupIsolatedByQrCode() {
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 10L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isTrue();  // 活码 10 有 confirmed → 挡
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 20L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isFalse(); // 活码 20 无 → 不挡（多校继承不被活码 10 的历史挡住）
    }

    @Test
    @DisplayName("qr_code_id IS NULL 的历史行降级为按 customer_id 判定")
    void nullQrDegradesToCustomerLevel() {
        transferRepo.save(CustomerTransfer.builder()
                .customerId(c.getId()).fromUserid("recB").toUserid("svcB")
                .qrCodeId(null).status(TransferStatus.confirmed).build());

        // null-qr 的历史记录仍挡任意活码（保守防重复）
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 99L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isTrue();
    }
}
