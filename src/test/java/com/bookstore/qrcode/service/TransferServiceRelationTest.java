package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService 转移确认关系维护")
class TransferServiceRelationTest {

    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private CustomerRelationService customerRelationService;
    @InjectMocks private TransferService transferService;

    @Test
    @DisplayName("转移确认后 from 置 removed + to upsert active（带来源）")
    void confirmedTransferMaintainsRelation() {
        QrCode qr = new QrCode();
        qr.setId(10L);
        qr.setSchoolId("SCHOOL-1");
        when(qrCodeRepo.findById(10L)).thenReturn(Optional.of(qr));

        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(10L).build();

        transferService.applyTransferConfirmedRelations(t);

        verify(customerRelationService).applyTransferConfirmed(
                eq(1L), eq("recA"), eq("svcA"), eq(10L), eq("SCHOOL-1"), any());
    }
}
