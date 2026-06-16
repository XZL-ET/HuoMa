package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 操作审计日志数据访问层。
 */
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
