package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {
    Optional<DailyReport> findByDate(LocalDate date);
}
