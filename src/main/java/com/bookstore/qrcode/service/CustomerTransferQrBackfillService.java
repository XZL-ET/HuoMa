package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerTransferQrBackfillService {

    private final CustomerTransferRepository transferRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;

    /** 回填存量 customer_transfer.qr_code_id：据 customer.school_id 反查活码主键。返回回填行数。 */
    public int backfill() {
        List<CustomerTransfer> nulls = transferRepo.findByQrCodeIdIsNull();
        int filled = 0;
        for (CustomerTransfer t : nulls) {
            Customer c = customerRepo.findById(t.getCustomerId()).orElse(null);
            if (c == null || c.getSchoolId() == null) continue;
            qrCodeRepo.findBySchoolId(c.getSchoolId()).ifPresent(qr -> {
                t.setQrCodeId(qr.getId());
                transferRepo.save(t);
            });
            if (t.getQrCodeId() != null) filled++;
        }
        log.info("customer_transfer.qr_code_id 回填: {} 行中回填 {} 行", nulls.size(), filled);
        return filled;
    }
}
