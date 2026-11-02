package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTagRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.FormSubmissionRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomPermanentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService 交接欢迎语永久失败分支")
class TransferServiceTest {

    @Mock private CustomerTransferRepository transferRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private CustomerTagRepository customerTagRepo;
    @Mock private FormSubmissionRepository formSubmissionRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private WecomApiClient wecomApi;
    @Mock private AlertService alertService;

    @InjectMocks private TransferService transferService;

    /** 准备一次未填写表单的交接欢迎语发送：走到 sendMessage 即抛出指定的永久异常。 */
    private CustomerTransfer givenGreetingSendFailsWith(WecomPermanentException error) {
        CustomerTransfer transfer = CustomerTransfer.builder()
            .id(1L).customerId(1L).toUserid("svcA").qrCodeId(10L).build();
        Customer customer = Customer.builder()
            .id(1L).externalUserid("ext1").name("家长").build();
        QrCode qr = QrCode.builder()
            .id(10L).schoolName("测试学校")
            .transferGreetingEnabled(true)
            .transferFilledNote("")
            .transferFilledGreeting("您好")
            .transferUnfilledGreeting("请填写 {{form_link}}")
            .build();

        when(transferRepo.findById(1L)).thenReturn(Optional.of(transfer));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));
        when(qrCodeRepo.findById(10L)).thenReturn(Optional.of(qr));
        when(formSubmissionRepo.findByCustomerIdOrderBySubmittedAtDesc(1L)).thenReturn(List.of());
        when(employeeRepo.findByUserid("svcA")).thenReturn(Optional.empty());
        when(customerTagRepo.findByCustomerId(1L)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(error)
            .when(wecomApi).sendMessage(anyString(), anyString(), anyString());
        return transfer;
    }

    @Test
    @DisplayName("48002 → 计入系统级告警，且仍标记已发/永久失败（既有语义不变）")
    void shouldReportSystemAlertOn48002() {
        CustomerTransfer transfer = givenGreetingSendFailsWith(
            new WecomPermanentException(48002, "api forbidden", "{}"));

        transferService.sendGreetingsForNewlyConfirmed(List.of(1L));

        verify(alertService).alertWecom48002();
        assertThat(transfer.getGreetingSent()).isTrue();
        assertThat(transfer.getGreetingPermanentFail()).isTrue();
        verify(transferRepo).saveAndFlush(transfer);
    }

    @Test
    @DisplayName("非 48002 永久错误 → 不触发 48002 系统告警，行为保持原样")
    void shouldNotReport48002ForOtherPermanentErrors() {
        CustomerTransfer transfer = givenGreetingSendFailsWith(
            new WecomPermanentException(60011, "no privilege", "{}"));

        transferService.sendGreetingsForNewlyConfirmed(List.of(1L));

        verify(alertService, never()).alertWecom48002();
        assertThat(transfer.getGreetingSent()).isTrue();
        assertThat(transfer.getGreetingPermanentFail()).isTrue();
        verify(transferRepo).saveAndFlush(transfer);
    }
}
