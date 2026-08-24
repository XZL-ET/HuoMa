package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.SchoolCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolCategoryRepository extends JpaRepository<SchoolCategory, Long> {

    /** 按排序号+名称排序 */
    List<SchoolCategory> findAllByOrderBySortOrderAscName();

    /** 按名称查找 */
    Optional<SchoolCategory> findByName(String name);

    /** 检查名称是否已存在 */
    boolean existsByName(String name);

    /** 统计引用了指定表单模板的分类数（删除保护） */
    long countByDefaultFormTemplateId(Long formTemplateId);
}
