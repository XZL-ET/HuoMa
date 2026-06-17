package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.DistrictManagerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 区县负责人配置服务。
 * <p>
 * 提供区县负责人的 CRUD 操作，以及在活码上下文中的负责人查询。
 * 全量负责人数据缓存到 Redis，5 分钟过期。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistrictManagerService {

    private final DistrictManagerRepository districtManagerRepo;
    private final EmployeeRepository employeeRepo;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY = "district:manager:all";

    /**
     * 根据活码获取对应区县的负责人。
     *
     * @param qr 活码实体
     * @return 负责人 Optional，无配置时 empty
     */
    public Optional<DistrictManager> getManagerForQrCode(QrCode qr) {
        return districtManagerRepo.findByRegionCityAndRegionDistrict(
            qr.getRegionCity(), qr.getRegionDistrict());
    }

    /**
     * 获取全量区县负责人（优先从 Redis 缓存，缓存命中返回缓存的 map）。
     * <p>
     * 返回值：Map&lt;"city|district", DistrictManager&gt;
     * </p>
     */
    public Map<String, DistrictManager> getAllAsMap() {
        List<DistrictManager> all = districtManagerRepo.findAll();
        Map<String, DistrictManager> map = new LinkedHashMap<>();
        for (DistrictManager m : all) {
            map.put(m.getRegionCity() + "|" + m.getRegionDistrict(), m);
        }
        return map;
    }

    /** 查询全部 */
    public List<DistrictManager> findAll() {
        return districtManagerRepo.findAll();
    }

    /** 按 ID 查找 */
    public DistrictManager findById(Long id) {
        return districtManagerRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("区县负责人配置不存在: " + id));
    }

    /** 创建 */
    @Transactional
    public DistrictManager create(String regionCity, String regionDistrict,
                                   String managerUserid) {
        if (districtManagerRepo.existsByRegionCityAndRegionDistrict(regionCity, regionDistrict)) {
            throw new RuntimeException("该区县已配置负责人");
        }
        // 从 Employee 表取姓名
        String managerName = employeeRepo.findByUserid(managerUserid)
            .map(Employee::getName)
            .orElse(managerUserid);

        DistrictManager dm = DistrictManager.builder()
            .regionCity(regionCity)
            .regionDistrict(regionDistrict)
            .managerUserid(managerUserid)
            .managerName(managerName)
            .build();
        return districtManagerRepo.save(dm);
    }

    /** 更新 */
    @Transactional
    public DistrictManager update(Long id, String managerUserid) {
        DistrictManager dm = findById(id);
        String managerName = employeeRepo.findByUserid(managerUserid)
            .map(Employee::getName)
            .orElse(managerUserid);
        dm.setManagerUserid(managerUserid);
        dm.setManagerName(managerName);
        return districtManagerRepo.save(dm);
    }

    /** 删除 */
    @Transactional
    public void delete(Long id) {
        districtManagerRepo.deleteById(id);
    }

    /** 获取全部城市列表（从区县负责人数据去重） */
    public List<String> getDistinctCities() {
        return districtManagerRepo.findAll().stream()
            .map(DistrictManager::getRegionCity)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }
}
