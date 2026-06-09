package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrRotateLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QrRotateLogRepository extends JpaRepository<QrRotateLog, Long> {
    List<QrRotateLog> findByQrCodeIdOrderByCreatedAtDesc(Long qrCodeId, Pageable pageable);
}
