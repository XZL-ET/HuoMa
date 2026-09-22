package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单模板的独立事务插入器。
 *
 * <p>{@link FormTemplateService} 的 {@code ensureCountyTemplate}/{@code ensureResendTemplate}/
 * {@code ensureImageTemplate} 是「先 findByName 再 create」的幂等获取或创建。并发下会双双查空、
 * 双双 INSERT，后者撞唯一键 {@code uk_form_template_name (name)} 抛
 * {@link org.springframework.dao.DataIntegrityViolationException}。若该 INSERT 跑在 ensure 方法的
 * 事务里，仓库 {@code save} 的 {@code @Transactional} 会把共享事务标成 rollback-only，使 catch 里的
 * 重查复用（返回赢家）静默失效、提交时抛 {@code UnexpectedRollbackException}。
 *
 * <p>这里把「尝试插入」拆到 {@code REQUIRES_NEW} 独立事务：冲突时该事务单独回滚，异常向上抛给
 * ensure 方法的 catch 捕获；调用方事务不受污染，重查复用才真正生效。
 */
@Service
@RequiredArgsConstructor
public class FormTemplateInsertService {

    private final FormTemplateRepository templateRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FormTemplate insert(FormTemplate template) {
        return templateRepo.save(template);
    }
}
