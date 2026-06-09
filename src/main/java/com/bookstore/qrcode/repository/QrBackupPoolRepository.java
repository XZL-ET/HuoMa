package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrBackupPool;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QrBackupPoolRepository extends JpaRepository<QrBackupPool, Long> {
    List<QrBackupPool> findByQrCodeId(Long qrCodeId);
    List<QrBackupPool> findByQrCodeIdAndStatusOrderBySortOrder(
        Long qrCodeId, QrBackupPool.PoolStatus status);
    long countByQrCodeIdAndStatus(Long qrCodeId, QrBackupPool.PoolStatus status);
}
