package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Scene;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.DistrictManagerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.TransferReconciliationService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 每日转接对账推送的集成测试。
 *
 * <p>验证 {@link CustomerTransferRepository#summarizeTransfersByManager} 的 JPQL
 * 正确性，以及 {@link TransferReconciliationService#reconcile} 按负责人构建对账
 * 消息并调用 {@link WecomApiClient#sendAppMessage} 推送的完整链路。</p>
 */
@DisplayName("每日转接对账集成测试")
@Import(WecomApiMockConfig.class)
class TransferReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private DistrictManagerRepository districtManagerRepo;
    @Autowired private TransferReconciliationService reconciliationService;
    @Autowired private WecomApiClient wecomApi;

    private QrCode qr1;
    private QrCode qr2;

    @BeforeEach
    void setUp() {
        transferRepo.deleteAll();
        customerRepo.deleteAll();
        qrCodeRepo.deleteAll();
        districtManagerRepo.deleteAll();

        saveDistrictManager("深圳", "南山区", "mgr-nanshan", "南山负责人");
        saveDistrictManager("深圳", "福田区", "mgr-futian", "福田负责人");

        qr1 = saveQr("SCH-A", "第一中学", "深圳", "南山区");
        qr2 = saveQr("SCH-B", "第二中学", "深圳", "福田区");
    }

    private DistrictManager saveDistrictManager(String city, String district,
                                                String userid, String name) {
        DistrictManager dm = new DistrictManager();
        dm.setRegionCity(city);
        dm.setRegionDistrict(district);
        dm.setManagerUserid(userid);
        dm.setManagerName(name);
        return districtManagerRepo.save(dm);
    }

    private QrCode saveQr(String schoolId, String schoolName,
                          String city, String district) {
        QrCode qr = new QrCode();
        qr.setSchoolName(schoolName);
        qr.setSchoolId(schoolId);
        qr.setRegionCity(city);
        qr.setRegionDistrict(district);
        qr.setStatus(QrCode.QrCodeStatus.active);
        qr.setScene(Scene.daily_push);
        qr.setCreateMode(QrCode.CreateMode.manual);
        return qrCodeRepo.save(qr);
    }

    private Customer saveCustomer(QrCode qr, String suffix) {
        Customer c = new Customer();
        c.setExternalUserid("wm-" + qr.getSchoolId() + "-" + suffix + "-" + System.nanoTime());
        c.setName("家长-" + suffix);
        c.setAddedAgent("rec");
        c.setCurrentAgent("rec");
        c.setSchoolId(qr.getSchoolId());
        c.setSourceQrId(qr.getId());
        c.setAddTime(LocalDateTime.now().minusDays(1));
        c.setStatus(Customer.CustomerStatus.active);
        return customerRepo.save(c);
    }

    private CustomerTransfer saveTransfer(Customer c, QrCode qr,
                                          CustomerTransfer.TransferStatus status,
                                          LocalDateTime transferTime) {
        return transferRepo.save(CustomerTransfer.builder()
            .customerId(c.getId())
            .fromUserid("rec").toUserid("svc")
            .qrCodeId(qr.getId())
            .transferTime(transferTime)
            .status(status)
            .retryCount(0).pollCount(0)
            .build());
    }

    @Test
    @DisplayName("summarizeTransfersByManager：按负责人聚合昨日转接的各类状态计数")
    void shouldSummarizeTransfersByManager() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime start = yesterday.atStartOfDay();
        LocalDateTime end = yesterday.plusDays(1).atStartOfDay();
        LocalDateTime inWindow = yesterday.atTime(12, 0);
        LocalDateTime outOfWindow = yesterday.minusDays(1).atTime(12, 0);

        // 南山区（qr1）：confirmed + rejected + api_failed 共 3 笔在窗口内
        Customer c1 = saveCustomer(qr1, "A");
        Customer c2 = saveCustomer(qr1, "B");
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed, inWindow);
        saveTransfer(c2, qr1, CustomerTransfer.TransferStatus.rejected, inWindow);
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.api_failed, inWindow);
        // 窗口外一笔，不应计入
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed, outOfWindow);

        // 福田区（qr2）：timeout 1 笔
        Customer c3 = saveCustomer(qr2, "C");
        saveTransfer(c3, qr2, CustomerTransfer.TransferStatus.timeout, inWindow);

        List<Object[]> rows = transferRepo.summarizeTransfersByManager(start, end);

        assertThat(rows).hasSize(2);

        Object[] nanshanRow = rows.stream()
            .filter(r -> "mgr-nanshan".equals(r[0]))
            .findFirst().orElseThrow();
        assertThat(nanshanRow[1]).isEqualTo("南山负责人");
        assertThat(((Number) nanshanRow[2]).longValue()).isEqualTo(3); // total
        assertThat(((Number) nanshanRow[3]).longValue()).isEqualTo(1); // confirmed
        assertThat(((Number) nanshanRow[4]).longValue()).isEqualTo(1); // rejected
        assertThat(((Number) nanshanRow[5]).longValue()).isEqualTo(0); // timeout
        assertThat(((Number) nanshanRow[6]).longValue()).isEqualTo(1); // api_failed
        assertThat(((Number) nanshanRow[7]).longValue()).isEqualTo(0); // retry_limit
        assertThat(((Number) nanshanRow[8]).longValue()).isEqualTo(0); // pending

        Object[] futianRow = rows.stream()
            .filter(r -> "mgr-futian".equals(r[0]))
            .findFirst().orElseThrow();
        assertThat(futianRow[1]).isEqualTo("福田负责人");
        assertThat(((Number) futianRow[2]).longValue()).isEqualTo(1); // total
        assertThat(((Number) futianRow[5]).longValue()).isEqualTo(1); // timeout
    }

    @Test
    @DisplayName("reconcile：按负责人构建对账消息并调用 sendAppMessage 推送")
    void shouldReconcileAndPushToManagers() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime inWindow = yesterday.atTime(12, 0);

        // 南山区：confirmed + rejected（成功 1 失败 1）
        Customer c1 = saveCustomer(qr1, "A");
        Customer c2 = saveCustomer(qr1, "B");
        saveTransfer(c1, qr1, CustomerTransfer.TransferStatus.confirmed, inWindow);
        saveTransfer(c2, qr1, CustomerTransfer.TransferStatus.rejected, inWindow);
        // 福田区：timeout（失败 1）
        Customer c3 = saveCustomer(qr2, "C");
        saveTransfer(c3, qr2, CustomerTransfer.TransferStatus.timeout, inWindow);

        int sent = reconciliationService.reconcile(yesterday);

        assertThat(sent).isEqualTo(2);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(wecomApi, times(2)).sendAppMessage(userCaptor.capture(), msgCaptor.capture());

        List<String> users = userCaptor.getAllValues();
        List<String> messages = msgCaptor.getAllValues();
        assertThat(users).containsExactlyInAnyOrder("mgr-nanshan", "mgr-futian");

        String nanshanMsg = messages.get(users.indexOf("mgr-nanshan"));
        assertThat(nanshanMsg).contains("南山负责人");
        assertThat(nanshanMsg).contains("共 2 笔");
        assertThat(nanshanMsg).contains("成功 1");
        assertThat(nanshanMsg).contains("失败 1");
        assertThat(nanshanMsg).contains("拒绝 1");

        String futianMsg = messages.get(users.indexOf("mgr-futian"));
        assertThat(futianMsg).contains("福田负责人");
        assertThat(futianMsg).contains("共 1 笔");
        assertThat(futianMsg).contains("超时 1");
    }
}
