package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.integration.BaseIntegrationTest;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 坐实 {@link FormTemplateService#ensureCountyTemplate} 模板并发插入冲突的提交语义：
 * 冲突后 catch 里的重查复用是否真实生效、且不抛 {@code UnexpectedRollbackException}。
 *
 * <p>全上下文集成测试（{@link FormTemplateInsertService} 的 {@code REQUIRES_NEW} 依赖事务代理）。
 * ensure 方法有 {@code @Transactional} 边界（跨 bean 调用生效），REQUIRES_NEW 冲突应独立回滚、
 * 不毒化 ensure 的外层事务。
 */
@DisplayName("FormTemplateService ensure 并发创建冲突提交语义")
class FormTemplateServiceConflictIntegrationTest extends BaseIntegrationTest {

    @Autowired private FormTemplateService formTemplateService;
    @SpyBean private FormTemplateRepository templateRepo;
    @Autowired private TransactionTemplate txTemplate;

    @BeforeEach
    void cleanUp() {
        templateRepo.deleteAll();
    }

    @Test
    @DisplayName("并发插入冲突后重查复用应真实生效、且不抛异常")
    void conflictRecoveryReusesWinner() {
        // 1. 种子：县区码默认模板已存在（已提交）
        Long winnerId = txTemplate.execute(s -> {
            FormTemplate t = templateRepo.save(FormTemplate.builder()
                .name("县区码默认模板").fields("[]").tagMapping("{}").build());
            return t.getId();
        });

        // 2. 预取赢家（提交后脱离事务，成为 detached 实体）
        FormTemplate winner = txTemplate.execute(s ->
            templateRepo.findByName("县区码默认模板").orElseThrow());

        // 3. 首次 find 强制查空 → 走 INSERT 路径，REQUIRES_NEW 插入撞唯一键冲突
        doReturn(Optional.empty())
            .doReturn(Optional.of(winner))
            .when(templateRepo).findByName("县区码默认模板");

        // 4. 在 ensureCountyTemplate 自身事务里跑，捕获是否抛异常
        Throwable thrown = null;
        FormTemplate result = null;
        try {
            result = formTemplateService.ensureCountyTemplate();
        } catch (Throwable e) {
            thrown = e;
        }

        // 5. 断言：不抛异常、复用已有记录
        assertThat(thrown).as("ensureCountyTemplate 不应抛异常").isNull();
        assertThat(result.getId()).as("冲突恢复应复用已有记录").isEqualTo(winnerId);
    }
}
