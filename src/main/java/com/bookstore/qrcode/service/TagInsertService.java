package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签的独立事务插入器。
 *
 * <p>{@link TagService#getOrCreateTag} 的「先 findFirstByNameAndGroupKeyword 再 save」在并发下会
 * 双双查空、双双 INSERT，后者撞唯一键 {@code uk_tag_name_group (name, group_keyword)} 抛
 * {@link org.springframework.dao.DataIntegrityViolationException}。getOrCreateTag 被 {@code autoTag}、
 * {@code tagFromForm} 等 {@code @Transactional} 方法 self-invocation 调用，运行在调用方事务里；
 * 若 INSERT 跑在调用方事务，仓库 {@code save} 的 {@code @Transactional} 会把共享事务标成 rollback-only，
 * 使 catch 里的重查复用静默回滚、提交时抛 {@code UnexpectedRollbackException}。
 *
 * <p>这里把「尝试插入」拆到 {@code REQUIRES_NEW} 独立事务：冲突时该事务单独回滚，异常向上抛给
 * {@link TagService#getOrCreateTag} 捕获；调用方事务不受污染，可安全重查赢家复用。
 */
@Service
@RequiredArgsConstructor
public class TagInsertService {

    private final TagRepository tagRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tag insertNew(String name, Tag.TagType type, Long parentId, String groupKeyword) {
        return tagRepo.save(Tag.builder()
            .name(name)
            .type(type)
            .parentId(parentId)
            .groupKeyword(groupKeyword != null ? groupKeyword : "")
            .wecomTagId(null)
            .build());
    }
}
