package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Scene;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转接记录查看页 Repository 查询集成测试。
 *
 * <p>验证 {@link CustomerTransferRepository#summarizeTransfersByQrCode} 与
 * {@link CustomerTransferRepository#findByQrCodeAndCustomerAddTimeBetween} 的
 * JPQL 正确性（显式 JOIN + CASE WHEN 聚合），时间筛选基于 Customer.addTime。</p>
 */
@DisplayName("转接记录 Repository 查询集成测试")
class TransferRecordRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private QrCodeRepository qrCodeRepo;

    private QrCode qr1;
    private QrCode qr2;

    @BeforeEach
    void setUp() {
        transferRepo.deleteAll();
        customerRepo.deleteAll();
        qrCodeRepo.deleteAll();

        qr1 = saveQr("SCH-TX-1", "第一中学");
        qr2 = saveQr("SCH-TX-2", "第二中学");
    }

    private QrCode saveQr(String schoolId, String schoolName) {
        QrCode qr = new QrCode();
        qr.setSchoolName(schoolName);
        qr.setSchoolId(schoolId);
        qr.setRegionCity("深圳");
        qr.setRegionDistrict("南山区");
        qr.setStatus(QrCode.QrCodeStatus.active);
        qr.setScene(Scene.daily_push);
        qr.setCreateMode(QrCode.CreateMode.manual);
        return qrCodeRepo.save(qr);
    }

    private Customer saveCustomer(QrCode qr, LocalDateTime addTime) {
        Customer c = new Customer();
        c.setExternalUserid("wm-" + qr.getSchoolId() + "-" + System.nanoTime());
        c.setName("家长-" + qr.getSchoolName());
        c.setAddedAgent("rec");
        c.setCurrentAgent("rec");
        c.setSchoolId(qr.getSchoolId());
        c.setSourceQrId(qr.getId());
        c.setAddTime(addTime);
        c.setStatus(Customer.CustomerStatus.active);
        return customerRepo.save(c);
    }

    private CustomerTransfer saveTransfer(Customer c, QrCode qr,
                                          CustomerTransfer.TransferStatus status) {
        return transferRepo.save(CustomerTransfer.builder()
            .customerId(c.getId())
            .fromUserid("rec").toUserid("svc")
            .qrCodeId(qr.getId())
            .transferTime(LocalDateTime.now())
            .status(status)
            .retryCount(0).pollCount(0)
            .build());
    }

    @Test
    @DisplayName("summarizeTransfersByQrCode：按活码汇总新增/成功/失败/进行中计数")
    void shouldSummarizeTransfersByQrCode() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(30);
        LocalDateTime end = now.plusDays(1);

        // qr1: 3 个在范围客户（confirmed + rejected + 无转移），1 个范围外客户
        Customer c1 = saveCustomer(qr1, now.minusDays(1));
        Customer c2 = saveCustomer(qr1, now.minusDays(2));
        Customer c3 = saveCustomer(qr1, now.minusDays(3)); // 无转移
        saveCustomer(qr1, now.minusDays(40)); // 范围外，不应计入
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed);
        saveTransfer(c2, qr1, CustomerTransfer.TransferStatus.rejected);

        // qr2: 1 个在范围客户（pending_confirm）
        Customer c4 = saveCustomer(qr2, now.minusDays(1));
        saveTransfer(c4, qr2, CustomerTransfer.TransferStatus.pending_confirm);

        List<Object[]> rows = transferRepo.summarizeTransfersByQrCode(start, end);

        assertThat(rows).hasSize(2);

        Object[] qr1Row = rows.stream()
            .filter(r -> ((Long) r[0]).equals(qr1.getId()))
            .findFirst().orElseThrow();
        assertThat(qr1Row[1]).isEqualTo("第一中学");
        assertThat(((Number) qr1Row[2]).longValue()).isEqualTo(3); // 新增客户
        assertThat(((Number) qr1Row[3]).longValue()).isEqualTo(1); // confirmed
        assertThat(((Number) qr1Row[4]).longValue()).isEqualTo(1); // failed (rejected)
        assertThat(((Number) qr1Row[5]).longValue()).isEqualTo(0); // pending

        Object[] qr2Row = rows.stream()
            .filter(r -> ((Long) r[0]).equals(qr2.getId()))
            .findFirst().orElseThrow();
        assertThat(qr2Row[1]).isEqualTo("第二中学");
        assertThat(((Number) qr2Row[2]).longValue()).isEqualTo(1);
        assertThat(((Number) qr2Row[3]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[4]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[5]).longValue()).isEqualTo(1); // pending
    }

    @Test
    @DisplayName("findByQrCodeAndCustomerAddTimeBetween：仅返回范围内客户的转移记录")
    void shouldFindTransfersByQrCodeAndAddTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        Customer inRange = saveCustomer(qr1, now.minusDays(1));
        Customer outOfRange = saveCustomer(qr1, now.minusDays(40));

        CustomerTransfer t1 = saveTransfer(inRange, qr1, CustomerTransfer.TransferStatus.confirmed);
        saveTransfer(outOfRange, qr1, CustomerTransfer.TransferStatus.timeout);

        List<CustomerTransfer> result =
            transferRepo.findByQrCodeAndCustomerAddTimeBetween(qr1.getId(), start, end);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(t1.getId());
    }
}
