package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.DistrictManager;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 区县负责人配置数据访问层。
 * <p>
 * 提供对 district_manager（区县负责人配置）表的 CRUD 操作和自定义查询。
 * 用于管理各城市、区县的负责人信息，支持按城市和区县精确查找。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface DistrictManagerRepository extends JpaRepository<DistrictManager, Long> {

    /**
     * 按城市和区县精确查找负责人。
     * <p>
     * 用于根据员工所属城市和区县自动匹配对应的负责人。
     * </p>
     *
     * @param regionCity     城市
     * @param regionDistrict 区县
     * @return 匹配的负责人记录，不存在则返回 {@link Optional#empty()}
     */
    Optional<DistrictManager> findByRegionCityAndRegionDistrict(String regionCity, String regionDistrict);

    /**
     * 按城市查找该城市下所有区县的负责人列表。
     * <p>
     * 用于查看某个城市的所有区县负责人配置情况。
     * </p>
     *
     * @param regionCity 城市
     * @return 该城市的区县负责人列表
     */
    List<DistrictManager> findByRegionCity(String regionCity);

    /**
     * 检查某城市和区县是否已配置负责人。
     *
     * @param regionCity     城市
     * @param regionDistrict 区县
     * @return true 如果已配置
     */
    boolean existsByRegionCityAndRegionDistrict(String regionCity, String regionDistrict);
}
