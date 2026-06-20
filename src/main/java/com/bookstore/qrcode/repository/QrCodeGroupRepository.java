package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCodeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QrCodeGroupRepository extends JpaRepository<QrCodeGroup, Long> {
    List<QrCodeGroup> findAllByOrderByName();
    Optional<QrCodeGroup> findByRegionDistrict(String regionDistrict);
}
