package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Scene;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.TransferFailType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转接记录查看页 Repository 查询集成测试。
 *
 * <p>验证 {@link CustomerTransferRepository#summarizeTransfersByQrCode}、
 * {@link CustomerTransferRepository#findStatusAndFailReasonByQrCodeAndAddTime} 与
 * {@link CustomerTransferRepository#findPageByQrCodeAndAddTime} 的
 * JPQL 正确性（显式 JOIN + CASE WHEN 聚合 + 投影 + LIKE 过滤分页），时间筛选基于 Customer.addTime。</p>
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
        return saveTransfer(c, qr, status, null);
    }

    private CustomerTransfer saveTransfer(Customer c, QrCode qr,
                                          CustomerTransfer.TransferStatus status,
                                          String failReason) {
        return transferRepo.save(CustomerTransfer.builder()
            .customerId(c.getId())
            .fromUserid("rec").toUserid("svc")
            .qrCodeId(qr.getId())
            .transferTime(LocalDateTime.now())
            .status(status)
            .failReason(failReason)
            .retryCount(0).pollCount(0)
            .build());
    }

    @Test
    @DisplayName("summarizeTransfersByQrCode：按活码汇总，失败状态拆分为四列")
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
        assertThat(((Number) qr1Row[4]).longValue()).isEqualTo(1); // rejected
        assertThat(((Number) qr1Row[5]).longValue()).isEqualTo(0); // timeout
        assertThat(((Number) qr1Row[6]).longValue()).isEqualTo(0); // api_failed
        assertThat(((Number) qr1Row[7]).longValue()).isEqualTo(0); // retry_limit
        assertThat(((Number) qr1Row[8]).longValue()).isEqualTo(0); // pending_confirm

        Object[] qr2Row = rows.stream()
            .filter(r -> ((Long) r[0]).equals(qr2.getId()))
            .findFirst().orElseThrow();
        assertThat(qr2Row[1]).isEqualTo("第二中学");
        assertThat(((Number) qr2Row[2]).longValue()).isEqualTo(1);
        assertThat(((Number) qr2Row[3]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[4]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[5]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[6]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[7]).longValue()).isEqualTo(0);
        assertThat(((Number) qr2Row[8]).longValue()).isEqualTo(1); // pending_confirm
    }

    @Test
    @DisplayName("summarizeTransfersByQrCode：同一客户多条转移记录不重复计数")
    void shouldNotDoubleCountCustomerWithMultipleTransfers() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(30);
        LocalDateTime end = now.plusDays(1);

        Customer c1 = saveCustomer(qr1, now.minusDays(1));
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.timeout, "无接替记录，超时作废");
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed);

        List<Object[]> rows = transferRepo.summarizeTransfersByQrCode(start, end);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(((Number) row[2]).longValue()).isEqualTo(1); // 新增客户
        assertThat(((Number) row[3]).longValue()).isEqualTo(1); // confirmed（取最新一条）
        assertThat(((Number) row[5]).longValue()).isEqualTo(0); // timeout 不重复计入
    }

    @Test
    @DisplayName("findStatusAndFailReasonByQrCodeAndAddTime：返回范围内记录的状态与失败原因投影")
    void shouldFindStatusAndFailReasonByQrCodeAndAddTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        Customer inRange1 = saveCustomer(qr1, now.minusDays(1));
        Customer inRange2 = saveCustomer(qr1, now.minusDays(2));
        Customer outOfRange = saveCustomer(qr1, now.minusDays(40));

        saveTransfer(inRange1, qr1, CustomerTransfer.TransferStatus.confirmed);
        saveTransfer(inRange2, qr1, CustomerTransfer.TransferStatus.api_failed,
            "接管员工企微票据过期(errcode=40205)");
        saveTransfer(outOfRange, qr1, CustomerTransfer.TransferStatus.timeout);

        List<Object[]> rows = transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(
            qr1.getId(), start, end);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r[0])
            .containsExactlyInAnyOrder(
                CustomerTransfer.TransferStatus.confirmed,
                CustomerTransfer.TransferStatus.api_failed);
        assertThat(rows).filteredOn(r -> r[0] == CustomerTransfer.TransferStatus.api_failed)
            .extracting(r -> r[1])
            .containsExactly("接管员工企微票据过期(errcode=40205)");
    }

    @Test
    @DisplayName("findStatusAndFailReasonByQrCodeAndAddTime：同一客户多条记录仅返回最新一条")
    void shouldReturnOnlyLatestTransferPerCustomer() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        Customer c1 = saveCustomer(qr1, now.minusDays(1));
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.timeout, "无接替记录，超时作废");
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed);

        List<Object[]> rows = transferRepo.findStatusAndFailReasonByQrCodeAndAddTime(
            qr1.getId(), start, end);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(CustomerTransfer.TransferStatus.confirmed);
        assertThat(rows.get(0)[1]).isNull();
    }

    @Test
    @DisplayName("findPageByQrCodeAndAddTime：分页 + failReason LIKE 过滤")
    void shouldPageAndFilterByFailReason() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        Customer c1 = saveCustomer(qr1, now.minusDays(1));
        Customer c2 = saveCustomer(qr1, now.minusDays(2));
        Customer c3 = saveCustomer(qr1, now.minusDays(3));
        Customer c4 = saveCustomer(qr1, now.minusDays(4));
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.api_failed, "接管员工企微票据过期(errcode=40205)，需重新登录");
        saveTransfer(c2, qr1, CustomerTransfer.TransferStatus.api_failed, "接替成员客户数已达上限(errcode=84097)");
        saveTransfer(c3, qr1, CustomerTransfer.TransferStatus.timeout, "无接替记录，超时作废");
        saveTransfer(c4, qr1, CustomerTransfer.TransferStatus.api_failed, "客户已不是好友(errcode=84061)，无法发起继承");

        // 无过滤：共 4 条
        Page<CustomerTransfer> all = transferRepo.findPageByQrCodeAndAddTime(
            qr1.getId(), start, end, null, PageRequest.of(0, 50));
        assertThat(all.getTotalElements()).isEqualTo(4);

        // 按 errcode=40205 过滤：仅 1 条
        Page<CustomerTransfer> filtered = transferRepo.findPageByQrCodeAndAddTime(
            qr1.getId(), start, end, "%errcode=40205%", PageRequest.of(0, 50));
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent().get(0).getCustomerId()).isEqualTo(c1.getId());

        // 分页：每页 2 条，共 2 页，按 transferTime 倒序
        Page<CustomerTransfer> page0 = transferRepo.findPageByQrCodeAndAddTime(
            qr1.getId(), start, end, null, PageRequest.of(0, 2));
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getContent()).hasSize(2);
        Page<CustomerTransfer> page1 = transferRepo.findPageByQrCodeAndAddTime(
            qr1.getId(), start, end, null, PageRequest.of(1, 2));
        assertThat(page1.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("findPageByQrCodeAndAddTimeOther：排除已知类别，仅返回「其他」类记录")
    void shouldFindOtherByExclusion() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        Customer c1 = saveCustomer(qr1, now.minusDays(1));
        Customer c2 = saveCustomer(qr1, now.minusDays(2));
        Customer c3 = saveCustomer(qr1, now.minusDays(3));
        Customer c4 = saveCustomer(qr1, now.minusDays(4));
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.api_failed, "接管员工企微票据过期(errcode=40205)");
        saveTransfer(c2, qr1, CustomerTransfer.TransferStatus.timeout, "无接替记录，超时作废");
        saveTransfer(c3, qr1, CustomerTransfer.TransferStatus.api_failed, "重试耗尽: connection timeout");
        saveTransfer(c4, qr1, CustomerTransfer.TransferStatus.api_failed, "客户不存在");

        Page<CustomerTransfer> other = transferRepo.findPageByQrCodeAndAddTimeOther(
            qr1.getId(), start, end, PageRequest.of(0, 50));

        assertThat(other.getTotalElements()).isEqualTo(2);
        assertThat(other.getContent())
            .extracting(CustomerTransfer::getCustomerId)
            .containsExactlyInAnyOrder(c3.getId(), c4.getId());
    }

    @Test
    @DisplayName("findPageByQrCodeAndAddTimeOther：排除所有 TransferFailType 已知类别 keyword")
    void shouldExcludeAllKnownFailTypeKeywords() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(7);
        LocalDateTime end = now.plusDays(1);

        // 为每个已知类别（keyword 非空）构造一条记录，验证被 other 查询排除
        for (TransferFailType ft : TransferFailType.values()) {
            if (ft.keyword() == null) continue; // OTHER 无关键词
            Customer c = saveCustomer(qr1, now.minusDays(1));
            saveTransfer(c, qr1, CustomerTransfer.TransferStatus.api_failed,
                "失败原因示例：" + ft.keyword());
        }
        // 一条真正的「其他」类记录（不含任何已知 keyword）
        Customer cOther = saveCustomer(qr1, now.minusDays(1));
        saveTransfer(cOther, qr1, CustomerTransfer.TransferStatus.api_failed, "网络连接被重置");

        Page<CustomerTransfer> other = transferRepo.findPageByQrCodeAndAddTimeOther(
            qr1.getId(), start, end, PageRequest.of(0, 50));

        assertThat(other.getTotalElements()).isEqualTo(1);
        assertThat(other.getContent().get(0).getCustomerId()).isEqualTo(cOther.getId());
    }
}
