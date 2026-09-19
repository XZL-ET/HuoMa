package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTextbookCover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GradeTextbookCoverRepository extends JpaRepository<GradeTextbookCover, String> {

    Optional<GradeTextbookCover> findByGradeName(String gradeName);
}
