package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRelationService {

    private final CustomerRelationRepository relationRepo;

    /**
     * upsert active 关系。来源字段（qr_code_id/school_id/add_time）仅在现有值为 null 时写入，
     * 绝不覆盖增量回调已写的精确值（COALESCE 语义，spec §4.4 铁律 1）。
     */
    @Transactional
    public void upsertActive(Long customerId, String employeeUserid,
                             Long qrCodeId, String schoolId, LocalDateTime addTime) {
        CustomerRelation rel = relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
            .orElseGet(() -> CustomerRelation.builder()
                .customerId(customerId).employeeUserid(employeeUserid)
                .status(RelationStatus.active).build());
        rel.setStatus(RelationStatus.active);
        if (rel.getQrCodeId() == null && qrCodeId != null) rel.setQrCodeId(qrCodeId);
        if (rel.getSchoolId() == null && schoolId != null) rel.setSchoolId(schoolId);
        if (rel.getAddTime() == null && addTime != null) rel.setAddTime(addTime);
        relationRepo.save(rel);
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
