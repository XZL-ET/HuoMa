package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCodeGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QrCodeGroupRepository extends JpaRepository<QrCodeGroup, Long> {
    List<QrCodeGroup> findAllByOrderByName();
    Optional<QrCodeGroup> findByRegionDistrict(String regionDistrict);

    /** 统计引用了指定表单模板的分组数（删除保护） */
    long countByDefaultFormTemplateId(Long formTemplateId);

    /** 根据关联活码 ID 反查联盟（理论上一个活码只属一个联盟，用 List 防御脏数据） */
    List<QrCodeGroup> findByQrCodeId(Long qrCodeId);

    /** 关键词搜索分页（联盟名称/市州/区县模糊匹配） */
    @Query("SELECT g FROM QrCodeGroup g WHERE " +
           "(:keyword IS NULL OR g.name LIKE %:keyword% OR g.regionCity LIKE %:keyword% " +
           "OR g.regionDistrict LIKE %:keyword%) ORDER BY g.name")
    Page<QrCodeGroup> search(@Param("keyword") String keyword, Pageable pageable);
}
