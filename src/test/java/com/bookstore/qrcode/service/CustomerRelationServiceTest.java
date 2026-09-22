package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerRelationService 关系写入")
class CustomerRelationServiceTest {

    @Mock private CustomerRelationRepository relationRepo;
    @Mock private EntityManager entityManager;
    @InjectMocks private CustomerRelationService service;

    @Test
    @DisplayName("upsertActive — 新关系写入来源字段")
    void upsertNewRelationWritesSource() {
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.empty());
        LocalDateTime now = LocalDateTime.now();

        service.upsertActive(1L, "recA", 10L, "SCHOOL-1", now);

        verify(relationRepo).save(argThat(r -> r.getQrCodeId() == 10L
                && "SCHOOL-1".equals(r.getSchoolId())
                && r.getAddTime().equals(now)
                && r.getStatus() == RelationStatus.active));
    }

    @Test
    @DisplayName("upsertActive — 已有精确来源时不覆盖（COALESCE 语义）")
    void upsertExistingKeepsPreciseSource() {
        CustomerRelation existing = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("recA")
                .qrCodeId(10L).schoolId("SCHOOL-1").addTime(LocalDateTime.now())
                .status(RelationStatus.active).build();
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.of(existing));

        service.upsertActive(1L, "recA", null, null, null); // 全量同步近似值，全 null

        assertThat(existing.getQrCodeId()).isEqualTo(10L);   // 未被 null 覆盖
        assertThat(existing.getSchoolId()).isEqualTo("SCHOOL-1");
        verify(relationRepo).save(existing);
    }

    @Test
    @DisplayName("upsertActive — 并发插入冲突（唯一键）时重查更新，不向上抛异常")
    void upsertRecoversFromConcurrentInsertConflict() {
        LocalDateTime now = LocalDateTime.now();
        CustomerRelation winner = CustomerRelation.builder()
                .id(99L).customerId(1L).employeeUserid("recA")
                .status(RelationStatus.active).build(); // 赢家来源字段为 null，需 COALESCE 回填

        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA"))
                .thenReturn(Optional.empty())          // 并发下首次查空 → 走 INSERT
                .thenReturn(Optional.of(winner));       // 冲突后重查 → 拿到赢家
        doThrow(new DataIntegrityViolationException("dup uk_customer_employee"))
                .doReturn(winner)
                .when(relationRepo).save(any(CustomerRelation.class));

        service.upsertActive(1L, "recA", 10L, "SCHOOL-1", now);

        assertThat(winner.getQrCodeId()).isEqualTo(10L);      // 恢复路径 COALESCE 回填来源
        assertThat(winner.getSchoolId()).isEqualTo("SCHOOL-1");
        assertThat(winner.getAddTime()).isEqualTo(now);
        assertThat(winner.getStatus()).isEqualTo(RelationStatus.active);
        verify(relationRepo, times(2)).save(any(CustomerRelation.class));
        verify(entityManager, never()).clear();   // 绝不能 clear 整个上下文（会丢失外层 customer UPDATE）
    }

    @Test
    @DisplayName("markRemoved — 存在才置 removed")
    void markRemovedExisting() {
        CustomerRelation existing = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("recA").status(RelationStatus.active).build();
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.of(existing));

        service.markRemoved(1L, "recA");

        assertThat(existing.getStatus()).isEqualTo(RelationStatus.removed);
        verify(relationRepo).save(existing);
    }

    @Test
    @DisplayName("markRemoved — 不存在时静默跳过（不抛异常）")
    void markRemovedMissingSilentlySkips() {
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.empty());

        service.markRemoved(1L, "recA");

        verify(relationRepo, never()).save(any());
    }
}
