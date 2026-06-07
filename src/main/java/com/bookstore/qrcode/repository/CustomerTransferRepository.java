package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface CustomerTransferRepository extends JpaRepository<CustomerTransfer, Long> {
    List<CustomerTransfer> findByCustomerId(Long customerId);
    List<CustomerTransfer> findByStatus(CustomerTransfer.TransferStatus status);
    List<CustomerTransfer> findByStatusAndRetryCountLessThan(
        CustomerTransfer.TransferStatus status, int maxRetries);
    long countByTransferTimeBetween(LocalDateTime start, LocalDateTime end);
    long countByStatusAndTransferTimeBetween(CustomerTransfer.TransferStatus status,
        LocalDateTime start, LocalDateTime end);
    long countByTransferTimeBetweenAndStatus(
        LocalDateTime start, LocalDateTime end, CustomerTransfer.TransferStatus status);
}
