package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 客户的独立事务插入器。
 *
 * <p>回调新增客户时「先 findByExternalUserid 再 INSERT」在并发（加好友回调双触发）下会双双查空、
 * 双双 INSERT，后者撞 external_userid 唯一键抛
 * {@link org.springframework.dao.DataIntegrityViolationException}。若该 INSERT 跑在调用方事务里，
 * 仓库 {@code save} 的 {@code @Transactional} 会把共享事务标成 rollback-only，使 catch 里的
 * 重查更新（{@link CustomerService#upsertFromCallback}）静默回滚。
 *
 * <p>这里把「尝试插入」拆到 {@code REQUIRES_NEW} 独立事务：冲突时该事务单独回滚，异常向上抛给
 * 调用方捕获；调用方外层事务不受污染，可安全重查赢家更新。
 */
@Service
@RequiredArgsConstructor
public class CustomerInsertService {

    private final CustomerRepository customerRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Customer insertNew(String externalUserid, String userId, Long qrCodeId, String schoolId) {
        return customerRepo.save(Customer.builder()
            .externalUserid(externalUserid)
            .name("未知")            // 占位，DataFillWorker 异步补全
            .type(1)
            .addedAgent(userId)
            .currentAgent(userId)
            .sourceQrId(qrCodeId)
            .schoolId(schoolId)
            .status(Customer.CustomerStatus.active)
            .addTime(LocalDateTime.now())
            .build());
    }
}
