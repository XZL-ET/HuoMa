package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface GradeTagMigrationProgressRepository
        extends JpaRepository<GradeTagMigrationProgress, Long> {

    /** 已成功处理的工作项自然键集合，用于断点续跑：每行返回 [customerId, localTagId]。
     *  只含终态成功的 action —— WECOM_MARK_FAILED / SKIPPED_NO_AGENT 不在此列，
     *  因此失败或跳过的客户在重跑时会被重新处理（写操作幂等，重跑安全）。 */
    @Query("select p.customerId, p.localTagId from GradeTagMigrationProgress p "
        + "where p.action in ('LOCAL_ONLY','WECOM_RELINK','WECOM_MARKED')")
    List<Object[]> findProcessedKeys();

    Optional<GradeTagMigrationProgress> findByCustomerIdAndLocalTagId(
        Long customerId, Long localTagId);

    long countByRowClass(String rowClass);

    long countByRequiresReview(boolean requiresReview);

    List<GradeTagMigrationProgress> findTop200ByRequiresReviewOrderByIdAsc(boolean requiresReview);
}
