package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 关系的独立事务插入器。
 *
 * <p>「先 findBy 再 save」的 upsert 在并发回调下会双双查空、双双 INSERT，后者撞唯一键
 * {@code (customer_id, employee_userid)} 抛 {@link org.springframework.dao.DataIntegrityViolationException}。
 * 若该 INSERT 跑在调用方事务里，仓库 {@code save} 的 {@code @Transactional} 会把共享事务标成
 * rollback-only，导致 catch 里的重查补写（{@link CustomerRelationService#upsertActive}）静默回滚、
 * 提交时抛 {@code UnexpectedRollbackException}。
 *
 * <p>这里把「尝试插入」拆到 {@code REQUIRES_NEW} 独立事务：冲突时该事务单独回滚（失败的实体随
 * 事务一起丢弃），异常向上抛给调用方捕获；调用方外层事务从未被污染，可安全重查赢家回填。
 */
@Service
@RequiredArgsConstructor
public class CustomerRelationInsertService {

    private final CustomerRelationRepository relationRepo;

    /**
     * 尝试插入一条 active 关系。来源字段直接写入（新实体初始为 null，等价 COALESCE 语义）。
     * 并发冲突时抛 {@code DataIntegrityViolationException}，本事务独立回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertActive(Long customerId, String employeeUserid,
                             Long qrCodeId, String schoolId, LocalDateTime addTime) {
        relationRepo.saveAndFlush(CustomerRelation.builder()
            .customerId(customerId).employeeUserid(employeeUserid)
            .qrCodeId(qrCodeId).schoolId(schoolId).addTime(addTime)
            .status(RelationStatus.active).build());
    }
}
