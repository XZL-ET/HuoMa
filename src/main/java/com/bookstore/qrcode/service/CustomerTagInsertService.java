package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerTag;
import com.bookstore.qrcode.repository.CustomerTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户-标签关联的独立事务插入器。
 *
 * <p>{@link TagService#bindCustomerTag} 虽用 {@code existsByCustomerIdAndTagId} 前置检查把并发窗口
 * 缩到极小，但极端并发下仍会双双查空、双双 INSERT，后者撞唯一键 {@code uk_customer_tag (customer_id, tag_id)}
 * 抛 {@link org.springframework.dao.DataIntegrityViolationException}。bindCustomerTag 被多个
 * {@code @Transactional} 方法 self-invocation 调用，运行在调用方事务里；若 INSERT 跑在调用方事务，
 * 仓库 {@code save} 会把共享事务标成 rollback-only，使 catch 里的忽略静默失效、提交时抛
 * {@code UnexpectedRollbackException}。
 *
 * <p>这里把「尝试插入」拆到 {@code REQUIRES_NEW} 独立事务：冲突时该事务单独回滚，调用方事务不受污染，
 * 重复打标被唯一键安全吸收。
 */
@Service
@RequiredArgsConstructor
public class CustomerTagInsertService {

    private final CustomerTagRepository customerTagRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Long customerId, Long tagId, String source) {
        customerTagRepo.save(CustomerTag.builder()
            .customerId(customerId)
            .tagId(tagId)
            .source(CustomerTag.TagSource.valueOf(source))
            .build());
    }

    /**
     * 把客户的某条年级标签绑定从 fromTagId 重接到 toTagId。
     *
     * <p>归并专用。就地 UPDATE 以保留 {@code source} 与 {@code tagged_at}：
     * {@link com.bookstore.qrcode.service.TransferService#checkFormFilled} 依赖 {@code source=form}，
     * 删表重建会把它抹成默认值。</p>
     *
     * <p>若客户已经绑定了 toTagId（同名重复行导致），直接删掉 fromTagId 那条多余行，
     * 否则 UPDATE 会撞唯一键 {@code uk_customer_tag (customer_id, tag_id)}。</p>
     *
     * @return 是否真的发生了变更；源行已被删除（客户被删）时返回 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rewire(Long customerId, Long fromTagId, Long toTagId) {
        if (customerTagRepo.existsByCustomerIdAndTagId(customerId, toTagId)) {
            customerTagRepo.deleteByCustomerIdAndTagId(customerId, fromTagId);
            return true;
        }
        return customerTagRepo.rewireTag(customerId, fromTagId, toTagId) > 0;
    }
}
