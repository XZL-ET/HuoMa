package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.CustomerTransfer.TransferStatus;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerTransferQrBackfillService 回填 qr_code_id")
class CustomerTransferQrBackfillServiceTest {

    @Mock private CustomerTransferRepository transferRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @InjectMocks private CustomerTransferQrBackfillService backfillService;

    @Test
    @DisplayName("据 customer.school_id 反查活码回填 qr_code_id")
    void backfillFromSchoolId() {
        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(null).status(TransferStatus.confirmed).build();
        when(transferRepo.findByQrCodeIdIsNull()).thenReturn(List.of(t));
        Customer c = Customer.builder().id(1L).schoolId("SCHOOL-1").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(c));
        QrCode qr = new QrCode();
        qr.setId(10L);
        when(qrCodeRepo.findBySchoolId("SCHOOL-1")).thenReturn(Optional.of(qr));

        int n = backfillService.backfill();

        assertThat(n).isEqualTo(1);
        assertThat(t.getQrCodeId()).isEqualTo(10L);
        verify(transferRepo).save(t);
    }

    @Test
    @DisplayName("反查不到活码（活码已删）→ 保持 null，不误填")
    void leaveNullWhenQrMissing() {
        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(null).status(TransferStatus.confirmed).build();
        when(transferRepo.findByQrCodeIdIsNull()).thenReturn(List.of(t));
        Customer c = Customer.builder().id(1L).schoolId("SCHOOL-GONE").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(c));
        when(qrCodeRepo.findBySchoolId("SCHOOL-GONE")).thenReturn(Optional.empty());

        int n = backfillService.backfill();

        assertThat(n).isEqualTo(0);
        assertThat(t.getQrCodeId()).isNull();
        verify(transferRepo, never()).save(any());
    }
}
