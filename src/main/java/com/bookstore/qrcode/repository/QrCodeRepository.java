package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface QrCodeRepository extends JpaRepository<QrCode, Long> {
    Optional<QrCode> findBySchoolId(String schoolId);
    boolean existsBySchoolId(String schoolId);

    @Query("SELECT q FROM QrCode q WHERE "
         + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%) "
         + "AND (:city IS NULL OR q.regionCity = :city) "
         + "AND (:district IS NULL OR q.regionDistrict = :district) "
         + "AND (:status IS NULL OR q.status = :status)")
    Page<QrCode> search(@Param("keyword") String keyword,
                        @Param("city") String city,
                        @Param("district") String district,
                        @Param("status") QrCode.QrCodeStatus status,
                        Pageable pageable);

    long countByStatus(QrCode.QrCodeStatus status);
    List<QrCode> findByStatus(QrCode.QrCodeStatus status);
    List<QrCode> findAll();
}
