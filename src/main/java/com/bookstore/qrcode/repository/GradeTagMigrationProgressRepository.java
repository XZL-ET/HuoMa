package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface GradeTagMigrationProgressRepository
        extends JpaRepository<GradeTagMigrationProgress, Long> {

    /** 已处理工作项的自然键集合，用于断点续跑：每行返回 [customerId, localTagId]。 */
    @Query("select p.customerId, p.localTagId from GradeTagMigrationProgress p")
    List<Object[]> findProcessedKeys();

    long countByRowClass(String rowClass);

    long countByRequiresReview(boolean requiresReview);

    List<GradeTagMigrationProgress> findTop200ByRequiresReviewOrderByIdAsc(boolean requiresReview);
}
