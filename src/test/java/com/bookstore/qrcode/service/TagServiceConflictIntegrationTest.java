package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.integration.WecomApiMockConfig;
import com.bookstore.qrcode.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 坐实 {@link TagService#getOrCreateTag} 标签并发插入冲突的提交语义：
 * 冲突后 catch 里的重查复用是否真实生效、且不抛 {@code UnexpectedRollbackException}。
 *
 * <p>全上下文集成测试（{@link TagInsertService} 的 {@code REQUIRES_NEW} 依赖事务代理）。
 * 用 {@link TransactionTemplate} 真实提交种子，观测冲突恢复是否复用赢家。
 */
@Import(WecomApiMockConfig.class)
@DisplayName("TagService 标签并发创建冲突提交语义")
class TagServiceConflictIntegrationTest extends BaseIntegrationTest {

    @Autowired private TagService tagService;
    @Autowired private TagInsertService tagInsertService;
    @SpyBean private TagRepository tagRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanUp() {
        tagRepo.deleteAll();
    }

    @Test
    @DisplayName("并发插入冲突后重查复用应真实生效、且不抛异常")
    void conflictRecoveryReusesWinner() {
        // 1. 种子：(北京, 市州) 已存在（已提交）
        Long winnerId = txTemplate.execute(s -> {
            Tag t = tagRepo.save(Tag.builder()
                .name("北京").type(Tag.TagType.system).groupKeyword("市州").build());
            return t.getId();
        });

        // 2. 预取赢家（提交后脱离事务，成为 detached 实体）
        Tag winner = txTemplate.execute(s ->
            tagRepo.findFirstByNameAndGroupKeyword("北京", "市州").orElseThrow());

        // 3. 首次 find 强制查空 → 走 INSERT 路径，REQUIRES_NEW 插入撞唯一键冲突
        doReturn(Optional.empty())
            .doReturn(Optional.of(winner))
            .when(tagRepo).findFirstByNameAndGroupKeyword("北京", "市州");

        // 4. 在外层事务里跑（与 autoTag 的真实调用形态一致：getOrCreateTag 自带独立事务，
        //    运行在调用方事务内），捕获是否抛异常
        Throwable thrown = null;
        Tag result = null;
        try {
            result = txTemplate.execute(s ->
                tagService.getOrCreateTag("北京", Tag.TagType.system, null, "市州"));
        } catch (Throwable e) {
            thrown = e;
        }

        // 5. 断言：不抛异常、复用已有记录（而非新建重复行）
        assertThat(thrown).as("getOrCreateTag 不应抛异常").isNull();
        assertThat(result.getId()).as("冲突恢复应复用已有记录").isEqualTo(winnerId);
    }

    @Test
    @DisplayName("wecomTagId 修复必须在外层事务提交前就已提交（否则外键插入会等锁到 30s 超时）")
    void wecomTagIdRepairIsCommittedBeforeCallerTransactionCommits() {
        // 1. 种子一条 wecomTagId 已过期的标签（已提交）
        Long tagId = txTemplate.execute(s -> tagRepo.save(Tag.builder()
            .name("张掖市").type(Tag.TagType.system).groupKeyword("县区").wecomTagId("old_id").build()).getId());

        // 2. 外层事务里做修复：此刻就必须对另一个连接可见
        txTemplate.executeWithoutResult(s -> {
            tagInsertService.updateWecomTagId(tagId, "new_id");
            assertThat(readWecomTagIdFromSeparateTransaction(tagId))
                .as("修复若与外层事务共用连接（Propagation.REQUIRED），另一个连接读到的是 old_id；"
                    + "那么紧随其后的 customer_tag 外键检查就会在这把未提交的 X 锁上等 30s")
                .isEqualTo("new_id");
        });
    }

    /** 用另一个连接的独立事务读取——模拟 {@code customer_tag} 外键检查看到的已提交状态。 */
    private String readWecomTagIdFromSeparateTransaction(Long tagId) {
        TransactionTemplate independent = new TransactionTemplate(txManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return independent.execute(s -> tagRepo.findById(tagId).orElseThrow().getWecomTagId());
    }
}
