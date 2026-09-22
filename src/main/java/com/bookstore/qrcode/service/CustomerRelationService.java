package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRelationService {

    private final CustomerRelationRepository relationRepo;
    private final EntityManager entityManager;

    /**
     * upsert active 关系。来源字段（qr_code_id/school_id/add_time）仅在现有值为 null 时写入，
     * 绝不覆盖增量回调已写的精确值（COALESCE 语义，spec §4.4 铁律 1）。
     *
     * <p>唯一键 (customer_id, employee_userid) 使关系写天然幂等。但「先 findBy 再 save」在
     * 并发回调（加好友回调双触发）下会双双查空、双双 INSERT，后者触发
     * {@link DataIntegrityViolationException}。这里捕获冲突后重查更新（spec §4.4 铁律 3），
     * 并且只 detach 失败的那条关系，绝不清空整个持久化上下文——否则会 detach 外层事务里
     * 已 {@code save} 的 customer，静默丢失其 UPDATE。
     */
    @Transactional
    public void upsertActive(Long customerId, String employeeUserid,
                             Long qrCodeId, String schoolId, LocalDateTime addTime) {
        CustomerRelation rel = relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
            .orElseGet(() -> CustomerRelation.builder()
                .customerId(customerId).employeeUserid(employeeUserid)
                .status(RelationStatus.active).build());
        applySource(rel, qrCodeId, schoolId, addTime);
        try {
            relationRepo.save(rel);
        } catch (DataIntegrityViolationException e) {
            // 并发插入冲突：另一线程已写入同一 (customer_id, employee_userid)。
            // 只 detach 失败的这条关系，保留外层事务里 pending 的 customer UPDATE（绝不 clear()）。
            entityManager.detach(rel);
            log.warn("关系并发插入冲突，重查后更新: customerId={}, employee={}", customerId, employeeUserid);
            CustomerRelation winner = relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
                .orElse(null);
            if (winner == null) {
                // 查不到说明非并发冲突（如 DB 异常），原样抛出
                throw e;
            }
            applySource(winner, qrCodeId, schoolId, addTime);
            relationRepo.save(winner);
        }
    }

    /** 置 active 并按 COALESCE 语义回填来源字段（仅现有值为 null 时写入）。 */
    private void applySource(CustomerRelation rel, Long qrCodeId, String schoolId, LocalDateTime addTime) {
        rel.setStatus(RelationStatus.active);
        if (rel.getQrCodeId() == null && qrCodeId != null) rel.setQrCodeId(qrCodeId);
        if (rel.getSchoolId() == null && schoolId != null) rel.setSchoolId(schoolId);
        if (rel.getAddTime() == null && addTime != null) rel.setAddTime(addTime);
    }

    /** 置 removed。关系不存在时静默跳过（防御：回调丢失的客户从未入过关系表）。 */
    @Transactional
    public void markRemoved(Long customerId, String employeeUserid) {
        relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
            .ifPresent(rel -> {
                rel.setStatus(RelationStatus.removed);
                relationRepo.save(rel);
            });
    }

    /** 转移确认：from 置 removed + to upsert active（follow_user 里 from 被 to 替换）。 */
    @Transactional
    public void applyTransferConfirmed(Long customerId, String fromUserid, String toUserid,
                                       Long qrCodeId, String schoolId, LocalDateTime confirmTime) {
        markRemoved(customerId, fromUserid);
        upsertActive(customerId, toUserid, qrCodeId, schoolId, confirmTime);
    }
}
