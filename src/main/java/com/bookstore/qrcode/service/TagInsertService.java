package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签行的独立事务写入器（插入 / wecomTagId 修复更新）。
 *
 * <p><b>插入路径：</b>{@link TagService#getOrCreateTag} 的「先 findFirstByNameAndGroupKeyword 再 save」
 * 在并发下会双双查空、双双 INSERT，后者撞唯一键 {@code uk_tag_name_group (name, group_keyword)} 抛
 * {@link org.springframework.dao.DataIntegrityViolationException}。getOrCreateTag 被 {@code autoTag}、
 * {@code applyFormTags} 等 {@code @Transactional} 方法 self-invocation 调用，运行在调用方事务里；
 * 若 INSERT 跑在调用方事务，仓库 {@code save} 的 {@code @Transactional} 会把共享事务标成 rollback-only，
 * 使 catch 里的重查复用静默回滚、提交时抛 {@code UnexpectedRollbackException}。
 *
 * <p><b>修复更新路径：</b>见 {@link #updateWecomTagId} —— 不只是「隔离失败」，
 * 而是必须让 tag 行的写立刻提交，否则调用方事务会持着该行的 X 锁去等
 * {@link TagService#bindCustomerTag} 的独立事务插 customer_tag（外键要父行 S 锁），
 * 两个连接互等到 30s 事务超时。
 *
 * <p>两条路径都拆到 {@code REQUIRES_NEW} 独立事务：自身冲突/失败时单独回滚，
 * 异常向上抛给 {@link TagService#getOrCreateTag} 捕获；调用方事务不受污染。
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

    /**
     * 以独立事务更新 tag 行的 wecomTagId 并立即提交，返回更新后的实体。
     *
     * <p>修复路径（本地 wecomTagId 为空 / 与企微当前列表不一致）必须走这里，
     * 不能在调用方事务里 {@code tagRepo.save(tag)}：{@code customer_tag} 有外键
     * {@code tag_id → tag(id)}，紧随其后的 {@link TagService#bindCustomerTag} 在**另一个连接**上
     * 插 customer_tag，FK 检查要在父行 tag(id) 上加 S 锁。若父行的 UPDATE 还挂在调用方事务里
     * 未提交（X 锁），两个连接就互等：外层在 Java 层等内层返回、内层在锁队列里等外层提交。
     * InnoDB 看不到这个锁环（外层并不在锁上等待），不报死锁，只是一直等 ——
     * {@code spring.transaction.default-timeout} 先到，语句被取消，症状就是 30s 事务超时。
     *
     * <p>独立事务在这里提交，X 锁在返回前就释放，调用方事务对 tag 行不留任何未提交写；
     * 返回的实体随事务结束已脱离持久化上下文，调用方在内存里拿到的是新 ID，不会被
     * 自动 flush 回写出去（那会重新引入同一行的 X 锁）。
     *
     * @param tagId       标签主键
     * @param wecomTagId  企微侧标签 ID
     * @return 更新后的标签实体；该行已被删除时返回 {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tag updateWecomTagId(Long tagId, String wecomTagId) {
        return tagRepo.findById(tagId)
            .map(tag -> {
                tag.setWecomTagId(wecomTagId);
                return tagRepo.save(tag);
            })
            .orElse(null);
    }
}
