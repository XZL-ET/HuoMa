package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 学校分类服务。
 * <p>
 * 提供分类 CRUD 操作。删除分类时自动清空关联学校的 categoryId，
 * 保护默认分类"未分类"不被删除。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2026-06-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolCategoryService {

    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;

    public List<SchoolCategory> listAll() {
        return categoryRepo.findAllByOrderBySortOrderAscName();
    }

    public SchoolCategory getById(Long id) {
        return categoryRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("分类不存在: " + id));
    }

    @Transactional
    public SchoolCategory create(String name, Integer sortOrder,
                                  String defaultWelcomeText, Long defaultFormTemplateId) {
        if (categoryRepo.existsByName(name)) {
            throw new RuntimeException("分类名称已存在: " + name);
        }
        return categoryRepo.save(SchoolCategory.builder()
            .name(name)
            .sortOrder(sortOrder != null ? sortOrder : 0)
            .defaultWelcomeText(defaultWelcomeText != null && defaultWelcomeText.isBlank()
                ? null : defaultWelcomeText)
            .defaultFormTemplateId(defaultFormTemplateId)
            .build());
    }

    @Transactional
    public SchoolCategory update(Long id, String name, Integer sortOrder,
                                  String defaultWelcomeText, Long defaultFormTemplateId) {
        SchoolCategory c = getById(id);
        if (name != null && !name.equals(c.getName()) && categoryRepo.existsByName(name)) {
            throw new RuntimeException("分类名称已存在: " + name);
        }
        if (name != null) c.setName(name);
        if (sortOrder != null) c.setSortOrder(sortOrder);
        if (defaultWelcomeText != null)
            c.setDefaultWelcomeText(defaultWelcomeText.isBlank() ? null : defaultWelcomeText);
        c.setDefaultFormTemplateId(defaultFormTemplateId);  // null = 清空
        return categoryRepo.save(c);
    }

    @Transactional
    public int delete(Long id) {
        SchoolCategory c = getById(id);

        // 保护默认分类
        if ("未分类".equals(c.getName())) {
            throw new RuntimeException("默认分类「未分类」不可删除");
        }

        // 清空关联学校的 categoryId
        List<School> linked = schoolRepo.findByCategoryIdAndDeletedFalse(id);
        for (School s : linked) {
            s.setCategoryId(null);
            schoolRepo.save(s);
        }
        if (!linked.isEmpty()) {
            log.info("删除分类「{}」: 已清空 {} 所学校的分类关联", c.getName(), linked.size());
        }

        categoryRepo.deleteById(id);
        return linked.size();
    }
}
