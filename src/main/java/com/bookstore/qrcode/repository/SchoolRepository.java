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

    /**
     * 从 qr_code 表导入学校数据到 school 表。
     * <p>将 qr_code 中含有 school_id/name/city/district 的活码
     * 作为新学校 INSERT IGNORE 到 school 表，返回新增行数。</p>
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO school (school_id, school_name, region_city, region_district, has_qrcode) " +
           "SELECT DISTINCT q.school_id, q.school_name, q.region_city, q.region_district, 1 " +
           "FROM qr_code q WHERE q.school_id IS NOT NULL AND q.school_name IS NOT NULL " +
           "AND q.region_city IS NOT NULL AND q.region_district IS NOT NULL", nativeQuery = true)
    int importSchoolsFromQrCode();
}
