package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class GradeTagMigrationProgressRepositoryTest {

    @Autowired
    private GradeTagMigrationProgressRepository repo;

    @Test
    @DisplayName("进度行可写入并按 row_class / requires_review 统计")
    void savesAndCounts() {
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(1L).rowClass("R1").localTagId(100L).action("LOCAL_ONLY").build());
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(2L).rowClass("R3").localTagId(101L).action("WECOM_MARKED")
            .requiresReview(true).build());

        assertEquals(1, repo.countByRowClass("R1"));
        assertEquals(1, repo.countByRowClass("R3"));
        assertEquals(1, repo.countByRequiresReview(true));
        assertEquals(1, repo.findTop200ByRequiresReviewOrderByIdAsc(true).size());
    }

    @Test
    @DisplayName("findProcessedKeys 返回 (customerId, localTagId) 对")
    void findProcessedKeysReturnsPairs() {
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(7L).rowClass("R2").localTagId(200L).action("WECOM_RELINK").build());

        List<Object[]> keys = repo.findProcessedKeys();
        assertEquals(1, keys.size());
        assertEquals(7L, ((Number) keys.get(0)[0]).longValue());
        assertEquals(200L, ((Number) keys.get(0)[1]).longValue());
    }

    @Test
    @DisplayName("findProcessedKeys 只含成功终态：失败/跳过项不算已处理，重跑会重试")
    void processedKeysExcludeFailures() {
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(11L).rowClass("R3").localTagId(300L).action("WECOM_MARKED").build());
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(12L).rowClass("R3").localTagId(301L).action("WECOM_MARK_FAILED").build());
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(13L).rowClass("R2").localTagId(302L).action("SKIPPED_NO_AGENT").build());

        List<Object[]> keys = repo.findProcessedKeys();

        assertEquals(1, keys.size());
        assertEquals(11L, ((Number) keys.get(0)[0]).longValue());
        assertEquals(300L, ((Number) keys.get(0)[1]).longValue());
    }
}
