package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.School;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface SchoolRepository extends JpaRepository<School, Long> {

    /** 查询所有未删除的市州（去重），按市州排序 */
    @Query("SELECT DISTINCT s.regionCity FROM School s WHERE s.deleted = false ORDER BY s.regionCity")
    List<String> findDistinctCities();

    /** 查询某市州下每个区县的学校数量 */
    @Query("SELECT s.regionDistrict, COUNT(s) FROM School s " +
           "WHERE s.regionCity = :city AND s.deleted = false " +
           "GROUP BY s.regionDistrict ORDER BY s.regionDistrict")
    List<Object[]> findDistrictCountsByCity(@Param("city") String city);

    /** 查询某区县下所有未删除的学校 */
    List<School> findByRegionCityAndRegionDistrictAndDeletedFalseOrderBySchoolName(
            String regionCity, String regionDistrict);

    /** 关键词搜索学校（名称、市州、区县模糊匹配） */
    @Query("SELECT s FROM School s WHERE s.deleted = false AND " +
           "(s.schoolName LIKE %:keyword% OR s.regionCity LIKE %:keyword% " +
           "OR s.regionDistrict LIKE %:keyword%) ORDER BY s.schoolName")
    List<School> searchByKeyword(@Param("keyword") String keyword);

    /** 根据 school_id 查询 */
    Optional<School> findBySchoolIdAndDeletedFalse(String schoolId);

    /** 分页查询所有未删除的学校 */
    Page<School> findByDeletedFalse(Pageable pageable);

    /** 按市州区县筛选分页 */
    @Query("SELECT s FROM School s WHERE s.deleted = false " +
           "AND (:city IS NULL OR s.regionCity = :city) " +
           "AND (:district IS NULL OR s.regionDistrict = :district) " +
           "ORDER BY s.regionCity, s.regionDistrict, s.schoolName")
    Page<School> findByFilters(@Param("city") String city,
                                @Param("district") String district,
                                Pageable pageable);

    /** 统计某区县的学校总数（含软删除） */
    long countByRegionCityAndRegionDistrict(String regionCity, String regionDistrict);

    /**
     * 从 qr_code 表同步 has_qrcode 状态到 school 表。
     * <p>设置 has_qrcode=1 当 school_id 存在于 qr_code 表中，
     * 否则设为 0。返回被更新的行数。</p>
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE school s SET s.has_qrcode = " +
           "(CASE WHEN s.school_id IN (SELECT q.school_id FROM qr_code q WHERE q.school_id IS NOT NULL) " +
           "THEN 1 ELSE 0 END) WHERE s.deleted = 0", nativeQuery = true)
    int syncHasQrcodeFromQrCode();
}
