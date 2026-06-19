# 学校活码自助查询 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a public-facing self-service page (`/s`) where school staff scan a QR code, select city→district→school, and get their school's live QR code or the district manager's contact.

**Architecture:** Spring MVC controller + Thymeleaf HTMX partials + Caffeine local cache. No Redis dependency. New `school` table LEFT JOINs existing `qr_code`. District manager QR codes auto-created via WeChat Work API on first access. Audit logs written to new `qr_access_log` table.

**Tech Stack:** Spring Boot 3.2.5, Thymeleaf + HTMX 1.9.12, Bootstrap 5.3.3, Spring Data JPA, Caffeine Cache, ZXing, Lombok — zero new dependencies.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-06-19-school-self-service-design.md` — all requirements defined there
- **Path prefix:** `/s` for school-facing pages, `/admin/schools` / `/admin/system-config` / `/admin/school-entry` for admin
- **Cache:** Caffeine local memory only — DO NOT touch Redis or `RedisTemplate`
- **Auth:** `/s/**` is public (no login); admin paths require `ROLE_ADMIN` (existing Spring Security form login)
- **UI:** Mobile-first (375px), blue `#2563EB` primary, Bootstrap 5 cards, HTMX partial rendering
- **Naming:** `school-entry` for CSS file, `school` for template directory, `SchoolEntryController` for controller
- **Audit:** Every view/download via `/s` must write `qr_access_log` with `channel='school'`
- **No new maven deps** — Caffeine is bundled in `spring-boot-starter-cache`

---

### Task 1: Database Schema — New Tables and Extensions

**Files:**
- Modify: `src/main/resources/schema.sql` — append new DDL at end

**Produces:** `school`, `system_config`, `qr_access_log` tables; `district_manager` new columns

- [ ] **Step 1: Add school table DDL**

Append to end of `src/main/resources/schema.sql`:

```sql
-- ============================================================================
-- 学校主数据表（学校活码自助查询）
-- ============================================================================
CREATE TABLE IF NOT EXISTS school (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '学校唯一标识',
    school_name     VARCHAR(128) NOT NULL COMMENT '学校名称',
    region_city     VARCHAR(64)  NOT NULL COMMENT '市州',
    region_district VARCHAR(64)  NOT NULL COMMENT '县区',
    has_qrcode      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已有活码（冗余字段）',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_school_city_district (region_city, region_district),
    INDEX idx_school_school_id (school_id),
    INDEX idx_school_deleted (deleted),
    INDEX idx_school_name (school_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学校主数据表';
```

- [ ] **Step 2: Add system_config table DDL**

```sql
-- ============================================================================
-- 系统配置表（全局联系人等键值配置）
-- ============================================================================
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(64) PRIMARY KEY COMMENT '配置键',
    config_value TEXT         COMMENT '配置值',
    updated_at   DATETIME     DEFAULT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';
```

- [ ] **Step 3: Add qr_access_log table DDL**

```sql
-- ============================================================================
-- 活码访问日志表（统一员工下载 + 学校自助查询审计）
-- ============================================================================
CREATE TABLE IF NOT EXISTS qr_access_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id      BIGINT        COMMENT '活码ID（关联 qr_code.id）',
    action          ENUM('view','download') NOT NULL DEFAULT 'view' COMMENT '行为类型',
    channel         ENUM('employee','school') NOT NULL DEFAULT 'school' COMMENT '来源渠道',
    user_identity   VARCHAR(128)  COMMENT '身份标识（员工=企微userid，学校=IP摘要）',
    accessed_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    ip_address      VARCHAR(45)   COMMENT '客户端IP',
    user_agent      VARCHAR(512)  COMMENT '浏览器User-Agent',
    INDEX idx_qal_qr_code (qr_code_id),
    INDEX idx_qal_channel (channel),
    INDEX idx_qal_accessed (accessed_at),
    INDEX idx_qal_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码访问日志表';
```

- [ ] **Step 4: Add district_manager new columns**

```sql
-- district_manager 扩展：负责人活码
ALTER TABLE district_manager
    ADD COLUMN IF NOT EXISTS qr_config_id VARCHAR(64)  DEFAULT NULL COMMENT '企微联系我 config_id',
    ADD COLUMN IF NOT EXISTS qr_url       VARCHAR(512) DEFAULT NULL COMMENT '负责人活码图片URL';
```

- [ ] **Step 5: Insert initial system_config data**

```sql
-- 初始全局联系人配置
INSERT IGNORE INTO system_config (config_key, config_value) VALUES
('global_contact_name', '火马客服'),
('global_contact_qr_config_id', ''),
('global_contact_qr_url', '');
```

- [ ] **Step 6: Migrate existing schools from qr_code to school table**

```sql
-- 从已有活码中提取学校数据，使用 school_id 避免重复
INSERT IGNORE INTO school (school_id, school_name, region_city, region_district, has_qrcode)
SELECT DISTINCT school_id, school_name, region_city, region_district, 1
FROM qr_code
WHERE school_id IS NOT NULL AND school_name IS NOT NULL
  AND region_city IS NOT NULL AND region_district IS NOT NULL;
```

- [ ] **Step 7: Verify schema**

Run: `mysql -u root bookstore_qrcode -e "SHOW TABLES LIKE 'school'; SHOW TABLES LIKE 'system_config'; SHOW TABLES LIKE 'qr_access_log'; SHOW COLUMNS FROM district_manager LIKE 'qr_config_id';"`

Expected: All four objects exist.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: add school, system_config, qr_access_log tables + district_manager extension"
```

---

### Task 2: Entity Classes

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/School.java`
- Create: `src/main/java/com/bookstore/qrcode/entity/SystemConfig.java`
- Create: `src/main/java/com/bookstore/qrcode/entity/QrAccessLog.java`
- Modify: `src/main/java/com/bookstore/qrcode/entity/DistrictManager.java` — add 2 fields

**Consumes:** Tables from Task 1

**Produces:** JPA entities for all new/existing tables

- [ ] **Step 1: Create School entity**

Create `src/main/java/com/bookstore/qrcode/entity/School.java`:

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 学校主数据实体。
 * <p>
 * 存储所有学校（无论是否已有活码），作为学校自助查询的数据源。
 * 通过 school_id 与 {@link QrCode} 进行 LEFT JOIN 判断活码状态。
 * </p>
 */
@Entity
@Table(name = "school")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, unique = true, length = 64)
    private String schoolId;

    @Column(name = "school_name", nullable = false, length = 128)
    private String schoolName;

    @Column(name = "region_city", nullable = false, length = 64)
    private String regionCity;

    @Column(name = "region_district", nullable = false, length = 64)
    private String regionDistrict;

    @Column(name = "has_qrcode", nullable = false)
    @Builder.Default
    private Boolean hasQrcode = false;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create SystemConfig entity**

Create `src/main/java/com/bookstore/qrcode/entity/SystemConfig.java`:

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 系统配置实体（键值对存储）。
 * <p>
 * 存储全局配置项，如全局联系人信息。
 * 通过 config_key 唯一标识一个配置项。
 * </p>
 */
@Entity
@Table(name = "system_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Create QrAccessLog entity**

Create `src/main/java/com/bookstore/qrcode/entity/QrAccessLog.java`:

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码访问日志实体。
 * <p>
 * 统一记录员工下载和学校自助查询两类渠道的查看/下载行为。
 * channel='employee' 对应下载中心员工操作；
 * channel='school' 对应学校自助查询页面操作。
 * </p>
 */
@Entity
@Table(name = "qr_access_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrAccessLog {

    public enum Action { view, download }
    public enum Channel { employee, school }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    @Builder.Default
    private Action action = Action.view;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    @Builder.Default
    private Channel channel = Channel.school;

    @Column(name = "user_identity", length = 128)
    private String userIdentity;

    @Column(name = "accessed_at", updatable = false)
    private LocalDateTime accessedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @PrePersist
    void prePersist() {
        accessedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: Extend DistrictManager entity**

Modify `src/main/java/com/bookstore/qrcode/entity/DistrictManager.java` — add after `managerName` field:

```java
    /** 负责人企微联系我 config_id（自动创建，用于学校端兜底展示） */
    @Column(name = "qr_config_id", length = 64)
    private String qrConfigId;

    /** 负责人活码图片 URL */
    @Column(name = "qr_url", length = 512)
    private String qrUrl;
```

- [ ] **Step 5: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/entity/
git commit -m "feat: add School, SystemConfig, QrAccessLog entities; extend DistrictManager"
```

---

### Task 3: Repository Interfaces

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/repository/SchoolRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/SystemConfigRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/QrAccessLogRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/DistrictManagerRepository.java` — add find with qr fields

**Consumes:** Entity classes from Task 2

**Produces:** Spring Data JPA repositories

- [ ] **Step 1: Create SchoolRepository**

Create `src/main/java/com/bookstore/qrcode/repository/SchoolRepository.java`:

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.School;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
```

- [ ] **Step 2: Create SystemConfigRepository**

Create `src/main/java/com/bookstore/qrcode/repository/SystemConfigRepository.java`:

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
```

- [ ] **Step 3: Create QrAccessLogRepository**

Create `src/main/java/com/bookstore/qrcode/repository/QrAccessLogRepository.java`:

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface QrAccessLogRepository extends JpaRepository<QrAccessLog, Long> {

    /** 统计指定活码的学校自助查看次数 */
    @Query("SELECT COUNT(a) FROM QrAccessLog a WHERE a.qrCodeId = :qrCodeId " +
           "AND a.channel = 'school' AND a.action = 'view'")
    long countSchoolViewsByQrCodeId(@Param("qrCodeId") Long qrCodeId);

    /** 统计指定活码的学校自助下载次数 */
    @Query("SELECT COUNT(a) FROM QrAccessLog a WHERE a.qrCodeId = :qrCodeId " +
           "AND a.channel = 'school' AND a.action = 'download'")
    long countSchoolDownloadsByQrCodeId(@Param("qrCodeId") Long qrCodeId);

    /** 按渠道分页查询日志 */
    @Query("SELECT a FROM QrAccessLog a WHERE " +
           "(:channel IS NULL OR a.channel = :channel) " +
           "AND (:qrCodeId IS NULL OR a.qrCodeId = :qrCodeId) " +
           "ORDER BY a.accessedAt DESC")
    Page<QrAccessLog> findByFilters(@Param("channel") String channel,
                                     @Param("qrCodeId") Long qrCodeId,
                                     Pageable pageable);

    /** 统计学校自助查询入口的总访问量（以首页view计） */
    long countByChannel(QrAccessLog.Channel channel);
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/
git commit -m "feat: add SchoolRepository, SystemConfigRepository, QrAccessLogRepository"
```

---

### Task 4: DTOs for School Self-Service

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/dto/SchoolCityDTO.java`
- Create: `src/main/java/com/bookstore/qrcode/dto/SchoolDistrictDTO.java`
- Create: `src/main/java/com/bookstore/qrcode/dto/SchoolDetailDTO.java`

**Consumes:** SchoolRepository queries from Task 3

**Produces:** DTOs for template rendering — no JPA entities in views

- [ ] **Step 1: Create SchoolCityDTO**

Create `src/main/java/com/bookstore/qrcode/dto/SchoolCityDTO.java`:

```java
package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 市州列表项：城市名 + 下辖区县数量 */
@Data
@AllArgsConstructor
public class SchoolCityDTO {
    private String cityName;
    private long districtCount;
}
```

- [ ] **Step 2: Create SchoolDistrictDTO**

Create `src/main/java/com/bookstore/qrcode/dto/SchoolDistrictDTO.java`:

```java
package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 区县列表项：区县名 + 下辖学校数量 */
@Data
@AllArgsConstructor
public class SchoolDistrictDTO {
    private String districtName;
    private long schoolCount;
}
```

- [ ] **Step 3: Create SchoolDetailDTO**

Create `src/main/java/com/bookstore/qrcode/dto/SchoolDetailDTO.java`:

```java
package com.bookstore.qrcode.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 学校详情（供模板渲染）。
 * 合并 school 表 + qr_code 表 + district_manager 表的数据。
 */
@Data
@Builder
public class SchoolDetailDTO {
    // 学校信息
    private String schoolId;
    private String schoolName;
    private String regionCity;
    private String regionDistrict;

    // 活码状态
    private boolean hasQrcode;          // 是否有活码记录
    private String qrStatus;            // 活码状态: active/paused/full/no_agent/null
    private String qrUrl;               // 活码图片 URL（active 时有效）

    // 联系人信息（有活码时关联的接待老师，取第一个 active 的 service agent name）
    private String contactName;

    // 兜底信息（无活码或非 active 时）
    private String fallbackManagerName;    // 区县负责人姓名
    private String fallbackQrUrl;          // 区县负责人活码 URL
    private boolean isGlobalFallback;      // true=使用了全局联系人兜底

    // 状态标签文案
    public String getStatusLabel() {
        if (hasQrcode) {
            if ("active".equals(qrStatus)) return "活码已就绪";
            if ("paused".equals(qrStatus)) return "活码维护中";
            if ("full".equals(qrStatus)) return "咨询人数较多";
            if ("no_agent".equals(qrStatus)) return "暂未分配接待人员";
            return "活码暂不可用";
        }
        return "活码尚未创建";
    }

    public boolean isQrAvailable() {
        return hasQrcode && "active".equals(qrStatus) && qrUrl != null && !qrUrl.isEmpty();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/dto/
git commit -m "feat: add school self-service DTOs (City, District, Detail)"
```

---

### Task 5: Caffeine Cache Configuration

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/config/CacheConfig.java`

**Consumes:** None

**Produces:** `citiesCache`, `districtsCache` — Caffeine caches with 5-min TTL, no Redis

- [ ] **Step 1: Create CacheConfig**

Create `src/main/java/com/bookstore/qrcode/config/CacheConfig.java`:

```java
package com.bookstore.qrcode.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置（Caffeine 内存缓存，不依赖 Redis）。
 * <p>
 * 用于学校自助查询的市州/区县列表缓存，与核心打标业务的 Redis 完全隔离。
 * </p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(Arrays.asList(
                buildCache("cities", 5),
                buildCache("districts", 5)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, int ttlMinutes) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats()
                .build());
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS (Caffeine is already available via `spring-boot-starter-cache`)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/CacheConfig.java
git commit -m "feat: add Caffeine local cache config for school self-service"
```

---

### Task 6: SchoolService — Core Business Logic

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/SchoolService.java`

**Consumes:** SchoolRepository, SystemConfigRepository, DistrictManagerRepository, QrCodeRepository, WecomApiClient (existing), CacheConfig

**Produces:** `SchoolService` with methods for city/district listing, school search, detail with fallback chain, and auto-creation of manager QR codes

- [ ] **Step 1: Create SchoolService**

Create `src/main/java/com/bookstore/qrcode/service/SchoolService.java`:

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.SchoolCityDTO;
import com.bookstore.qrcode.dto.SchoolDetailDTO;
import com.bookstore.qrcode.dto.SchoolDistrictDTO;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.DistrictManagerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 学校自助查询核心服务。
 * <p>
 * 提供市州/区县/学校三级查询、活码详情组装、
 * 以及区县负责人/全局联系人活码的自动创建与降级逻辑。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final QrCodeRepository qrCodeRepository;
    private final DistrictManagerRepository districtManagerRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final WecomApiClient wecomApiClient;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // 市州 & 区县查询（带缓存）
    // ========================================================================

    @Cacheable(value = "cities", unless = "#result == null || #result.isEmpty()")
    public List<SchoolCityDTO> getCities() {
        List<String> cityNames = schoolRepository.findDistinctCities();
        return cityNames.stream().map(city -> {
            // 统计该市下所有未删除学校属于多少个不同的区县
            List<Object[]> districtCounts = schoolRepository.findDistrictCountsByCity(city);
            long distinctDistricts = districtCounts.size();
            return new SchoolCityDTO(city, distinctDistricts);
        }).collect(Collectors.toList());
    }

    @Cacheable(value = "districts", unless = "#result == null || #result.isEmpty()")
    public List<SchoolDistrictDTO> getDistricts(String city) {
        List<Object[]> rows = schoolRepository.findDistrictCountsByCity(city);
        return rows.stream()
                .map(row -> new SchoolDistrictDTO((String) row[0], (Long) row[1]))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // 学校查询
    // ========================================================================

    public List<School> getSchools(String city, String district) {
        return schoolRepository.findByRegionCityAndRegionDistrictAndDeletedFalseOrderBySchoolName(city, district);
    }

    public List<School> searchSchools(String keyword) {
        return schoolRepository.searchByKeyword(keyword);
    }

    // ========================================================================
    // 学校详情（核心：LEFT JOIN qr_code + 降级链）
    // ========================================================================

    @Transactional
    public SchoolDetailDTO getSchoolDetail(String schoolId) {
        School school = schoolRepository.findBySchoolIdAndDeletedFalse(schoolId)
                .orElseThrow(() -> new NoSuchElementException("学校不存在: " + schoolId));

        Optional<QrCode> qrCodeOpt = qrCodeRepository.findBySchoolId(schoolId);

        SchoolDetailDTO.SchoolDetailDTOBuilder builder = SchoolDetailDTO.builder()
                .schoolId(school.getSchoolId())
                .schoolName(school.getSchoolName())
                .regionCity(school.getRegionCity())
                .regionDistrict(school.getRegionDistrict());

        if (qrCodeOpt.isPresent()) {
            QrCode qr = qrCodeOpt.get();
            builder.hasQrcode(true)
                   .qrStatus(qr.getStatus().name())
                   .qrUrl(qr.getQrUrl());
            // 取第一个 active 的服务老师作为联系人
            String contactName = qrCodeRepository.findFirstServiceAgentName(qr.getId());
            builder.contactName(contactName != null ? contactName : "");
        } else {
            builder.hasQrcode(false).qrStatus(null).qrUrl(null).contactName("");
        }

        // 如果活码不可用，走降级链
        boolean qrAvailable = builder.build().isQrAvailable();
        if (!qrAvailable) {
            applyFallback(builder, school.getRegionCity(), school.getRegionDistrict());
        }

        return builder.build();
    }

    // ========================================================================
    // 降级链：区县负责人 → 全局联系人
    // ========================================================================

    private void applyFallback(SchoolDetailDTO.SchoolDetailDTOBuilder builder,
                                String city, String district) {
        // 第一级：区县负责人
        Optional<DistrictManager> dmOpt =
                districtManagerRepository.findByRegionCityAndRegionDistrict(city, district);
        if (dmOpt.isPresent()) {
            DistrictManager dm = dmOpt.get();
            String qrUrl = ensureManagerQrCode(dm);
            builder.fallbackManagerName(dm.getManagerName())
                   .fallbackQrUrl(qrUrl)
                   .isGlobalFallback(false);
            return;
        }

        // 第二级：全局联系人
        builder.fallbackManagerName(getGlobalConfig("global_contact_name"))
               .fallbackQrUrl(ensureGlobalContactQrCode())
               .isGlobalFallback(true);
    }

    // ========================================================================
    // 负责人活码自动创建
    // ========================================================================

    private String ensureManagerQrCode(DistrictManager dm) {
        if (dm.getQrUrl() != null && !dm.getQrUrl().isEmpty()) {
            return dm.getQrUrl();
        }
        // 自动创建
        try {
            String requestJson = buildContactWayJson(dm.getManagerUserid(), "school_fallback_" + dm.getId());
            JsonNode resp = wecomApiClient.createContactWay(requestJson);
            String configId = resp.get("config_id").asText();
            String qrUrl = resp.get("qr_code").asText();

            dm.setQrConfigId(configId);
            dm.setQrUrl(qrUrl);
            districtManagerRepository.save(dm);

            log.info("Created fallback QR for district manager: {} (district={}-{})",
                    dm.getManagerName(), dm.getRegionCity(), dm.getRegionDistrict());
            return qrUrl;
        } catch (Exception e) {
            log.error("Failed to create fallback QR for district manager: {}", dm.getManagerUserid(), e);
            return null; // 创建失败返回 null，前端展示空状态
        }
    }

    private String ensureGlobalContactQrCode() {
        String existingUrl = getGlobalConfig("global_contact_qr_url");
        if (existingUrl != null && !existingUrl.isEmpty()) {
            return existingUrl;
        }
        // 全局联系人的活码手动在后台创建（无关联企微 userid）
        log.warn("Global contact QR not configured — please create manually in admin panel");
        return null;
    }

    private String buildContactWayJson(String userid, String state) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", 1);        // 单人
            body.put("scene", 2);       // 联系我
            body.put("state", state);
            body.put("user", List.of(userid));
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build contact way JSON", e);
        }
    }

    // ========================================================================
    // 全局联系人配置
    // ========================================================================

    public String getGlobalContactName() {
        return getGlobalConfig("global_contact_name");
    }

    public String getGlobalContactQrUrl() {
        return getGlobalConfig("global_contact_qr_url");
    }

    private String getGlobalConfig(String key) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .orElse("");
    }

    public void updateGlobalConfig(String key, String value) {
        SystemConfig config = systemConfigRepository.findById(key)
                .orElse(new SystemConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        systemConfigRepository.save(config);
    }
}
```

- [ ] **Step 2: Add helper query to QrCodeRepository**

Modify `src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java` — add:

```java
    /** 查询活码的第一个 active 服务老师姓名 */
    @Query(value = "SELECT a.name FROM qr_agent qa " +
           "JOIN agent a ON a.userid = qa.agent_userid " +
           "WHERE qa.qr_code_id = :qrCodeId AND qa.role IN ('service', 'dual') " +
           "AND qa.status = 'active' LIMIT 1", nativeQuery = true)
    String findFirstServiceAgentName(@Param("qrCodeId") Long qrCodeId);
```

- [ ] **Step 3: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/SchoolService.java src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java
git commit -m "feat: add SchoolService with city/district query, fallback chain, and manager QR auto-creation"
```

---

### Task 7: SchoolAccessLogService

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/SchoolAccessLogService.java`

**Consumes:** QrAccessLogRepository from Task 3

**Produces:** `SchoolAccessLogService` — records view/download events from school channel

- [ ] **Step 1: Create SchoolAccessLogService**

Create `src/main/java/com/bookstore/qrcode/service/SchoolAccessLogService.java`:

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 学校自助查询访问日志服务。
 * <p>
 * 异步记录学校端的查看和下载行为，用于等保三级审计和管理后台统计。
 * 日志写入不影响请求响应性能。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SchoolAccessLogService {

    private final QrAccessLogRepository logRepository;

    /** 记录学校端查看活码 */
    @Async
    public void logView(Long qrCodeId, HttpServletRequest request) {
        save(QrAccessLog.Action.view, qrCodeId, request);
    }

    /** 记录学校端下载活码 */
    @Async
    public void logDownload(Long qrCodeId, HttpServletRequest request) {
        save(QrAccessLog.Action.download, qrCodeId, request);
    }

    /** 记录全局联系人查看 */
    @Async
    public void logGlobalContactView(HttpServletRequest request) {
        save(QrAccessLog.Action.view, null, request);
    }

    private void save(QrAccessLog.Action action, Long qrCodeId, HttpServletRequest request) {
        QrAccessLog log = QrAccessLog.builder()
                .qrCodeId(qrCodeId)
                .action(action)
                .channel(QrAccessLog.Channel.school)
                .userIdentity(request.getRemoteAddr())
                .ipAddress(request.getRemoteAddr())
                .userAgent(truncate(request.getHeader("User-Agent"), 512))
                .build();
        logRepository.save(log);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/SchoolAccessLogService.java
git commit -m "feat: add SchoolAccessLogService for async school-channel audit logging"
```

---

### Task 8: SchoolEntryController — Public `/s` Endpoints

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/SchoolEntryController.java`

**Consumes:** SchoolService (Task 6), SchoolAccessLogService (Task 7)

**Produces:** All `/s/**` endpoints returning Thymeleaf + HTMX partial HTML

- [ ] **Step 1: Create SchoolEntryController**

Create `src/main/java/com/bookstore/qrcode/controller/SchoolEntryController.java`:

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.SchoolDetailDTO;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.SchoolAccessLogService;
import com.bookstore.qrcode.service.SchoolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 学校自助查询入口控制器。
 * <p>
 * 面向学校人员的公开页面（/s），无需登录。
 * 所有接口返回 HTMX 局部 HTML 片段，实现阶梯式卡片选择交互。
 * </p>
 */
@Controller
@RequestMapping("/s")
@RequiredArgsConstructor
public class SchoolEntryController {

    private final SchoolService schoolService;
    private final SchoolAccessLogService logService;
    private final QrCodeRepository qrCodeRepository;

    // ========================================================================
    // 首页：市州列表 + 全局联系人
    // ========================================================================

    @GetMapping
    public String index(Model model, HttpServletRequest request, HttpSession session) {
        ensureSession(session);
        model.addAttribute("cities", schoolService.getCities());
        model.addAttribute("globalContactName", schoolService.getGlobalContactName());
        return "school/cities";
    }

    // ========================================================================
    // HTMX 局部刷新：县区列表
    // ========================================================================

    @GetMapping("/districts")
    public String districts(@RequestParam String city, Model model) {
        model.addAttribute("city", city);
        model.addAttribute("districts", schoolService.getDistricts(city));
        return "school/districts";
    }

    // ========================================================================
    // HTMX 局部刷新：学校列表
    // ========================================================================

    @GetMapping("/schools")
    public String schools(@RequestParam String city,
                          @RequestParam String district,
                          Model model) {
        List<School> schoolList = schoolService.getSchools(city, district);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("schools", schoolList);
        return "school/schools";
    }

    // ========================================================================
    // HTMX 局部刷新：学校详情（完整页面）
    // ========================================================================

    @GetMapping("/school/{schoolId}")
    public String schoolDetail(@PathVariable String schoolId,
                                Model model,
                                HttpServletRequest request) {
        SchoolDetailDTO detail = schoolService.getSchoolDetail(schoolId);

        // 记录审计日志
        if (detail.isQrAvailable()) {
            QrCode qr = qrCodeRepository.findBySchoolId(schoolId).orElse(null);
            if (qr != null) {
                logService.logView(qr.getId(), request);
            }
        }

        model.addAttribute("detail", detail);
        return "school/detail";
    }

    // ========================================================================
    // 搜索
    // ========================================================================

    @GetMapping("/search")
    public String search(@RequestParam String keyword, Model model) {
        List<School> results = schoolService.searchSchools(keyword);
        model.addAttribute("keyword", keyword);
        model.addAttribute("schools", results);
        model.addAttribute("globalContactName", schoolService.getGlobalContactName());
        return "school/search-results";
    }

    // ========================================================================
    // 全局联系人详情
    // ========================================================================

    @GetMapping("/global-contact")
    public String globalContact(Model model, HttpServletRequest request) {
        model.addAttribute("contactName", schoolService.getGlobalContactName());
        model.addAttribute("qrUrl", schoolService.getGlobalContactQrUrl());
        logService.logGlobalContactView(request);
        return "school/global-contact";
    }

    // ========================================================================
    // 下载活码图片（代理下载 + 记录日志）
    // ========================================================================

    @GetMapping("/school/{schoolId}/download")
    public ResponseEntity<?> downloadQrCode(@PathVariable String schoolId,
                                             HttpServletRequest request) {
        SchoolDetailDTO detail = schoolService.getSchoolDetail(schoolId);
        if (!detail.isQrAvailable() || detail.getQrUrl() == null) {
            return ResponseEntity.notFound().build();
        }

        // 记录下载日志
        QrCode qr = qrCodeRepository.findBySchoolId(schoolId).orElse(null);
        if (qr != null) {
            logService.logDownload(qr.getId(), request);
        }

        // 代理下载企微活码图片
        try {
            URL url = URI.create(detail.getQrUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                ByteArrayResource resource = new ByteArrayResource(bytes);
                String filename = detail.getSchoolName() + "_活码.png";
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, "UTF-8"))
                        .body(resource);
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // ========================================================================
    // 临时会话
    // ========================================================================

    private void ensureSession(HttpSession session) {
        if (session.getAttribute("school_visitor_id") == null) {
            session.setAttribute("school_visitor_id", UUID.randomUUID().toString());
            session.setMaxInactiveInterval(1800); // 30 分钟
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/SchoolEntryController.java
git commit -m "feat: add SchoolEntryController with /s endpoints for school self-service"
```

---

### Task 9: School Templates — Thymeleaf + HTMX Views

**Files:**
- Create: `src/main/resources/templates/school/layout.html`
- Create: `src/main/resources/templates/school/cities.html`
- Create: `src/main/resources/templates/school/districts.html`
- Create: `src/main/resources/templates/school/schools.html`
- Create: `src/main/resources/templates/school/detail.html`
- Create: `src/main/resources/templates/school/search-results.html`
- Create: `src/main/resources/templates/school/global-contact.html`

**Consumes:** SchoolEntryController (Task 8), DTOs (Task 4)

**Produces:** Complete HTMX-driven UI

- [ ] **Step 1: Create layout template**

Create `src/main/resources/templates/school/layout.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>火马 · 学校活码查询</title>
    <link rel="stylesheet" th:href="@{/css/bootstrap.min.css}">
    <link rel="stylesheet" th:href="@{/css/bootstrap-icons.css}">
    <link rel="stylesheet" th:href="@{/css/school-entry.css}">
    <script th:src="@{/js/htmx.min.js}"></script>
</head>
<body class="school-body">
    <header class="school-topbar">
        <div class="school-topbar-brand">火马 · 学校活码查询</div>
    </header>
    <main class="school-main" id="school-main" hx-target="#school-main" hx-swap="innerHTML">
        <th:block th:insert="${contentTemplate} ?: ~{}" />
    </main>
    <script th:src="@{/js/bootstrap.bundle.min.js}"></script>
    <script th:inline="javascript">
        // 微信内置浏览器检测
        (function() {
            var isWechat = /MicroMessenger/i.test(navigator.userAgent);
            document.body.classList.toggle('is-wechat', isWechat);
        })();
    </script>
</body>
</html>
```

- [ ] **Step 2: Create cities page (index)**

Create `src/main/resources/templates/school/cities.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>火马 · 学校活码查询</title>
    <link rel="stylesheet" th:href="@{/css/bootstrap.min.css}">
    <link rel="stylesheet" th:href="@{/css/school-entry.css}">
    <script th:src="@{/js/htmx.min.js}"></script>
</head>
<body class="school-body">
    <header class="school-topbar">
        <div class="school-topbar-brand">火马 · 学校活码查询</div>
    </header>

    <main class="school-main" id="school-main" hx-target="#school-main" hx-swap="innerHTML">
        <!-- Search -->
        <div class="school-search">
            <form th:action="@{/s/search}" hx-get="/s/search" hx-target="#school-main" hx-trigger="submit">
                <div class="school-search-input">
                    <i class="bi bi-search"></i>
                    <input type="text" name="keyword" placeholder="搜索学校名称快速定位…"
                           autocomplete="off" required minlength="1">
                </div>
            </form>
        </div>

        <div class="school-divider"><span>或按地区选择</span></div>

        <!-- City cards -->
        <div class="school-card-list">
            <a th:each="city : ${cities}"
               th:href="@{/s/districts(city=${city.cityName})}"
               hx-get="/s/districts"
               hx-vals='{"city": "[[${city.cityName}]]"}'
               class="school-card">
                <span class="school-card-icon">📍</span>
                <div class="school-card-body">
                    <span class="school-card-title" th:text="${city.cityName}">武汉</span>
                    <span class="school-card-sub" th:text="${city.districtCount} + ' 个区县'">12 个区县</span>
                </div>
                <span class="school-card-arrow">›</span>
            </a>
        </div>

        <!-- "Or" divider -->
        <div class="school-divider"><span>或</span></div>

        <!-- Global contact card -->
        <div class="school-global-card">
            <a th:href="@{/s/global-contact}" hx-get="/s/global-contact" class="school-global-link">
                <div class="school-global-avatar">🎓</div>
                <div class="school-global-info">
                    <span class="school-global-hint">找不到学校？直接联系</span>
                    <span class="school-global-name" th:text="${globalContactName}">火马客服</span>
                    <span class="school-global-action">点击查看联系方式 →</span>
                </div>
            </a>
        </div>
    </main>

    <script th:inline="javascript">
        document.body.classList.toggle('is-wechat', /MicroMessenger/i.test(navigator.userAgent));
    </script>
</body>
</html>
```

- [ ] **Step 3: Create districts page (HTMX fragment)**

Create `src/main/resources/templates/school/districts.html`:

```html
<!-- HTMX partial: 替换 #school-main -->
<header class="school-topbar">
    <div class="school-topbar-brand">火马 · 学校活码查询</div>
</header>

<main class="school-main" id="school-main" hx-target="#school-main" hx-swap="innerHTML">
    <div class="school-breadcrumb">
        <a href="/s" hx-get="/s" class="school-back-link">← 市州</a>
        <span class="school-breadcrumb-divider">|</span>
        <span class="school-breadcrumb-current" th:text="${city}">武汉</span>
    </div>

    <div class="school-card-list">
        <a th:each="d : ${districts}"
           th:href="@{/s/schools(city=${city},district=${d.districtName})}"
           hx-get="/s/schools"
           th:hx-vals='{"city": "${city}", "district": "${d.districtName}"}'
           class="school-card">
            <span class="school-card-body">
                <span class="school-card-title" th:text="${d.districtName}">江汉区</span>
            </span>
            <span class="school-card-badge" th:text="${d.schoolCount} + ' 所学校'">8 所学校</span>
        </a>
    </div>

    <div th:if="${#lists.isEmpty(districts)}" class="school-empty">
        <p>该地区暂无学校数据</p>
        <a href="/s/global-contact" hx-get="/s/global-contact" class="school-global-link">
            联系全局负责人 →
        </a>
    </div>
</main>
```

- [ ] **Step 4: Create schools page (HTMX fragment)**

Create `src/main/resources/templates/school/schools.html`:

```html
<header class="school-topbar">
    <div class="school-topbar-brand">火马 · 学校活码查询</div>
</header>

<main class="school-main" id="school-main" hx-target="#school-main" hx-swap="innerHTML">
    <div class="school-breadcrumb">
        <a th:href="@{/s/districts(city=${city})}" hx-get="/s/districts" th:hx-vals='{"city": "${city}"}'
           class="school-back-link">← 区县</a>
        <span class="school-breadcrumb-divider">|</span>
        <span class="school-breadcrumb-current" th:text="${city} + ' · ' + ${district}">武汉 · 江汉区</span>
    </div>

    <div class="school-card-list">
        <a th:each="s : ${schools}"
           th:href="@{/s/school/{id}(id=${s.schoolId})}"
           hx-get="/s/school/{id}"
           th:hx-vals='{"id": "${s.schoolId}"}'
           class="school-card school-card-sm">
            <span class="school-card-body">
                <span class="school-card-title" th:text="${s.schoolName}">武汉第一小学</span>
            </span>
            <span th:if="${s.hasQrcode}" class="badge badge-success">已有活码</span>
            <span th:unless="${s.hasQrcode}" class="badge badge-warning">待创建</span>
        </a>
    </div>

    <div th:if="${#lists.isEmpty(schools)}" class="school-empty">
        <p>该地区暂无学校数据</p>
        <a href="/s/global-contact" hx-get="/s/global-contact" class="school-global-link">
            联系全局负责人 →
        </a>
    </div>
</main>
```

- [ ] **Step 5: Create detail page (HTMX fragment — full page reload)**

Create `src/main/resources/templates/school/detail.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>火马 · 学校活码查询</title>
    <link rel="stylesheet" th:href="@{/css/bootstrap.min.css}">
    <link rel="stylesheet" th:href="@{/css/school-entry.css}">
    <script th:src="@{/js/htmx.min.js}"></script>
</head>
<body class="school-body">
    <header class="school-topbar">
        <div class="school-topbar-brand">火马 · 学校活码查询</div>
    </header>

    <main class="school-main" id="school-main">
        <div class="school-breadcrumb">
            <a href="javascript:history.back()" class="school-back-link">← 返回学校列表</a>
        </div>

        <div class="school-detail">
            <h1 class="school-detail-name" th:text="${detail.schoolName}">武汉市第一小学</h1>
            <span class="badge"
                  th:classappend="${detail.qrAvailable} ? 'badge-success' : 'badge-warning'"
                  th:text="${detail.statusLabel}">活码已就绪</span>

            <!-- 有活码：QR + 下载按钮 -->
            <th:block th:if="${detail.qrAvailable}">
                <div class="school-qr-card school-qr-active">
                    <img th:src="${detail.qrUrl}" alt="学校活码" class="school-qr-img"
                         onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%22180%22 height=%22180%22><rect fill=%22%23F1F5F9%22 width=%22180%22 height=%22180%22 rx=%228%22/><text x=%2290%22 y=%2295%22 text-anchor=%22middle%22 fill=%22%2394A3B8%22 font-size=%2213%22>图片加载失败</text></svg>'">
                    <p class="school-qr-contact" th:if="${detail.contactName}">
                        联系人：<span th:text="${detail.contactName}">张老师</span>
                    </p>
                </div>

                <a th:href="@{/s/school/{id}/download(id=${detail.schoolId})}"
                   class="btn-download">
                    ⬇ 保存活码到手机
                </a>
                <p class="school-hint">或长按上方图片保存</p>
                <p class="school-hint school-wechat-hint">
                    💡 微信内请点击右上角 ··· → 在浏览器中打开
                </p>
            </th:block>

            <!-- 无活码：负责人兜底 -->
            <th:block th:unless="${detail.qrAvailable}">
                <div class="school-qr-card school-qr-fallback">
                    <img th:if="${detail.fallbackQrUrl}"
                         th:src="${detail.fallbackQrUrl}" alt="负责人企业微信二维码"
                         class="school-qr-img">
                    <div th:unless="${detail.fallbackQrUrl}" class="school-qr-placeholder">
                        暂未配置
                    </div>
                    <p class="school-fallback-name" th:text="${detail.fallbackManagerName}">李老师</p>
                    <p class="school-fallback-role" th:unless="${detail.isGlobalFallback}">区县负责人</p>
                    <p class="school-fallback-role" th:if="${detail.isGlobalFallback}">全局联系人</p>
                </div>

                <!-- 操作指引 -->
                <div class="school-guide">
                    <p class="school-guide-title">📋 如何添加负责人？</p>
                    <div class="school-guide-steps">
                        <div class="school-guide-step">
                            <span class="school-step-num">1</span>
                            <span>长按上方二维码保存到相册</span>
                        </div>
                        <div class="school-guide-step">
                            <span class="school-step-num">2</span>
                            <span>打开微信 → 扫一扫 → 相册选择图片</span>
                        </div>
                        <div class="school-guide-step">
                            <span class="school-step-num">3</span>
                            <span>发送好友申请，等待通过</span>
                        </div>
                    </div>
                </div>
                <p class="school-note" th:if="${!detail.isGlobalFallback}">
                    添加时请备注：<strong th:text="${detail.schoolName}">武汉市新华小学</strong>
                </p>
                <p class="school-hint school-wechat-hint" th:if="${!detail.isGlobalFallback}">
                    💡 微信内可截图后到扫一扫选择图片
                </p>
            </th:block>
        </div>
    </main>

    <script th:inline="javascript">
        document.body.classList.toggle('is-wechat', /MicroMessenger/i.test(navigator.userAgent));
    </script>
</body>
</html>
```

- [ ] **Step 6: Create search results page**

Create `src/main/resources/templates/school/search-results.html`:

```html
<header class="school-topbar">
    <div class="school-topbar-brand">火马 · 学校活码查询</div>
</header>

<main class="school-main" id="school-main" hx-target="#school-main" hx-swap="innerHTML">
    <div class="school-breadcrumb">
        <a href="/s" hx-get="/s" class="school-back-link">← 返回首页</a>
    </div>

    <div class="school-search-title" th:text="'搜索：' + ${keyword}">搜索：第一小学</div>

    <div class="school-card-list" th:if="${!#lists.isEmpty(schools)}">
        <a th:each="s : ${schools}"
           th:href="@{/s/school/{id}(id=${s.schoolId})}"
           class="school-card school-card-sm">
            <span class="school-card-body">
                <span class="school-card-title" th:text="${s.schoolName}">武汉第一小学</span>
                <span class="school-card-sub" th:text="${s.regionCity} + ' · ' + ${s.regionDistrict}">武汉 · 江汉区</span>
            </span>
            <span th:if="${s.hasQrcode}" class="badge badge-success">已有活码</span>
            <span th:unless="${s.hasQrcode}" class="badge badge-warning">待创建</span>
        </a>
    </div>

    <div th:if="${#lists.isEmpty(schools)}" class="school-empty">
        <p>未找到匹配学校</p>
        <a href="/s/global-contact" hx-get="/s/global-contact" class="school-global-link">
            联系全局负责人 →
        </a>
    </div>
</main>
```

- [ ] **Step 7: Create global contact page**

Create `src/main/resources/templates/school/global-contact.html`:

```html
<header class="school-topbar">
    <div class="school-topbar-brand">火马 · 学校活码查询</div>
</header>

<main class="school-main" id="school-main">
    <div class="school-breadcrumb">
        <a href="/s" hx-get="/s" class="school-back-link">← 返回首页</a>
    </div>

    <div class="school-detail">
        <h1 class="school-detail-name" th:text="${contactName}">火马客服</h1>
        <span class="badge badge-info">全局联系人</span>

        <div class="school-qr-card school-qr-active"
             th:if="${qrUrl != null and !qrUrl.isEmpty()}">
            <img th:src="${qrUrl}" alt="全局联系人二维码" class="school-qr-img">
            <p class="school-qr-contact">扫码添加联系人</p>
        </div>

        <div th:unless="${qrUrl != null and !qrUrl.isEmpty()}" class="school-empty">
            <p>暂未配置全局联系人活码</p>
            <p class="school-hint">请联系管理员配置</p>
        </div>
    </div>
</main>
```

- [ ] **Step 8: Verify templates are parseable**

Run: `mvnw spring-boot:run` — browse `http://localhost:8080/s` (app must start without template errors)

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/templates/school/
git commit -m "feat: add school self-service Thymeleaf templates (cities, districts, schools, detail, search, global-contact)"
```

---

### Task 10: School Entry CSS — Mobile-First Styles

**Files:**
- Create: `src/main/resources/static/css/school-entry.css`

**Consumes:** Task 9 templates (class names referenced)

**Produces:** Complete responsive stylesheet for school-facing pages

- [ ] **Step 1: Create school-entry.css**

Create `src/main/resources/static/css/school-entry.css`:

```css
/* ==========================================================================
   学校活码自助查询 — 移动端优先样式
   ========================================================================== */

/* -- Body -- */
.school-body {
    background: #F8FAFC;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: #1E293B;
    margin: 0;
    padding: 0;
    min-height: 100vh;
    -webkit-text-size-adjust: 100%;
}

/* -- Top bar -- */
.school-topbar {
    background: #2563EB;
    color: #fff;
    padding: 14px 16px;
    text-align: center;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 0.5px;
    position: sticky;
    top: 0;
    z-index: 10;
}

/* -- Breadcrumb -- */
.school-breadcrumb {
    background: #fff;
    padding: 12px 16px;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    border-bottom: 1px solid #F1F5F9;
}
.school-back-link {
    color: #2563EB;
    text-decoration: none;
    font-weight: 500;
}
.school-breadcrumb-divider { color: #CBD5E1; }
.school-breadcrumb-current { font-weight: 600; color: #1E293B; }

/* -- Main content -- */
.school-main {
    max-width: 480px;
    margin: 0 auto;
    padding-bottom: 32px;
}

/* -- Search -- */
.school-search {
    background: #fff;
    padding: 16px;
}
.school-search form { margin: 0; }
.school-search-input {
    display: flex;
    align-items: center;
    gap: 8px;
    background: #F1F5F9;
    border-radius: 10px;
    padding: 12px 14px;
    border: 1px solid #E2E8F0;
}
.school-search-input i,
.school-search-input svg { color: #94A3B8; flex-shrink: 0; }
.school-search-input input {
    border: none;
    background: transparent;
    outline: none;
    font-size: 14px;
    width: 100%;
    color: #1E293B;
}
.school-search-input input::placeholder { color: #94A3B8; }
.school-search-title {
    padding: 16px;
    font-size: 14px;
    color: #64748B;
    background: #fff;
    border-bottom: 1px solid #F1F5F9;
}

/* -- Divider -- */
.school-divider {
    text-align: center;
    padding: 4px 16px 8px;
    font-size: 12px;
    color: #94A3B8;
    display: flex;
    align-items: center;
    gap: 12px;
}
.school-divider::before,
.school-divider::after {
    content: '';
    flex: 1;
    height: 1px;
    background: #E2E8F0;
}

/* -- Card list -- */
.school-card-list { padding: 0 16px 8px; }

/* -- Card -- */
.school-card {
    background: #fff;
    border-radius: 12px;
    padding: 18px 16px;
    margin-bottom: 10px;
    display: flex;
    align-items: center;
    gap: 12px;
    border: 1.5px solid #E2E8F0;
    text-decoration: none;
    color: inherit;
    transition: border-color 0.15s, box-shadow 0.15s;
    cursor: pointer;
}
.school-card:hover,
.school-card:active {
    border-color: #93C5FD;
    box-shadow: 0 2px 8px rgba(37,99,235,0.10);
}
.school-card-icon { font-size: 20px; flex-shrink: 0; }
.school-card-sm { padding: 16px; }
.school-card-body { flex: 1; }
.school-card-title { font-size: 16px; font-weight: 600; color: #1E293B; display: block; }
.school-card-sub { font-size: 12px; color: #94A3B8; display: block; margin-top: 2px; }
.school-card-arrow { color: #2563EB; font-size: 22px; font-weight: 300; }
.school-card-badge { font-size: 12px; color: #64748B; }

/* -- Badges -- */
.badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    font-weight: 600;
    padding: 5px 12px;
    border-radius: 20px;
    white-space: nowrap;
}
.badge-success { background: #DCFCE7; color: #16A34A; border: 1px solid #BBF7D0; }
.badge-warning { background: #FFF7ED; color: #EA580C; border: 1px solid #FED7AA; }
.badge-info    { background: #DBEAFE; color: #2563EB; border: 1px solid #BFDBFE; }

/* -- Detail page -- */
.school-detail {
    padding: 28px 20px;
    text-align: center;
}
.school-detail-name {
    font-size: 20px;
    font-weight: 700;
    color: #0F172A;
    margin: 0 0 6px 0;
}
.school-detail .badge { margin-bottom: 24px; }

/* -- QR card -- */
.school-qr-card {
    border-radius: 16px;
    padding: 20px;
    margin-bottom: 20px;
}
.school-qr-active {
    background: #fff;
    border: 2px solid #E2E8F0;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.school-qr-fallback {
    background: #FFFBEB;
    border: 2px solid #FCD34D;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.school-qr-img {
    width: 180px;
    height: 180px;
    border-radius: 10px;
    object-fit: contain;
    margin: 0 auto 14px;
    display: block;
}
.school-qr-contact {
    font-size: 13px;
    color: #64748B;
    margin: 0;
}
.school-qr-placeholder {
    width: 180px;
    height: 180px;
    border-radius: 10px;
    background: #fff;
    border: 1px solid #FDE68A;
    margin: 0 auto 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #D97706;
    font-size: 13px;
}
.school-fallback-name { font-size: 14px; font-weight: 600; color: #1E293B; margin: 0; }
.school-fallback-role { font-size: 12px; color: #92400E; margin: 4px 0 0 0; }

/* -- Download button -- */
.btn-download {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    background: #2563EB;
    color: #fff;
    border-radius: 12px;
    padding: 15px 28px;
    font-size: 16px;
    font-weight: 600;
    text-decoration: none;
    box-shadow: 0 4px 12px rgba(37,99,235,0.30);
    transition: box-shadow 0.15s, transform 0.15s;
}
.btn-download:hover { box-shadow: 0 6px 16px rgba(37,99,235,0.40); transform: translateY(-1px); }

/* -- Hints -- */
.school-hint {
    font-size: 12px;
    color: #94A3B8;
    margin-top: 10px;
}
.school-wechat-hint { display: none; }
.is-wechat .school-wechat-hint { display: block; }

/* -- Guide card -- */
.school-guide {
    background: #FEF3C7;
    border-radius: 12px;
    padding: 14px 16px;
    margin-bottom: 12px;
    text-align: left;
}
.school-guide-title {
    font-size: 13px;
    font-weight: 600;
    color: #92400E;
    margin: 0 0 8px 0;
}
.school-guide-steps { display: flex; flex-direction: column; gap: 5px; }
.school-guide-step {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: #78350F;
}
.school-step-num {
    width: 20px;
    height: 20px;
    background: #F59E0B;
    color: #fff;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    font-weight: 700;
    flex-shrink: 0;
}
.school-note {
    font-size: 13px;
    color: #B45309;
    font-weight: 500;
}

/* -- Empty state -- */
.school-empty {
    text-align: center;
    padding: 48px 16px;
    color: #94A3B8;
    font-size: 14px;
}
.school-empty p { margin: 0 0 16px 0; }

/* -- Global contact card -- */
.school-global-card { padding: 4px 16px 20px; }
.school-global-link {
    display: flex;
    align-items: center;
    gap: 14px;
    background: linear-gradient(135deg, #EFF6FF, #F0F9FF);
    border: 1.5px solid #BFDBFE;
    border-radius: 14px;
    padding: 18px;
    text-decoration: none;
    color: inherit;
    transition: border-color 0.15s;
}
.school-global-link:hover { border-color: #93C5FD; }
.school-global-avatar {
    width: 52px;
    height: 52px;
    background: #2563EB;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
    flex-shrink: 0;
}
.school-global-info { flex: 1; display: flex; flex-direction: column; }
.school-global-hint { font-size: 12px; color: #64748B; }
.school-global-name { font-size: 16px; font-weight: 700; color: #1E293B; margin: 2px 0; }
.school-global-action { font-size: 13px; color: #2563EB; }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/school-entry.css
git commit -m "feat: add school-entry.css — mobile-first styles for school self-service"
```

---

### Task 11: Security Configuration — Permit `/s` and Add RateLimitFilter

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`
- Create: `src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java`

**Consumes:** None (standalone filter)

**Produces:** `/s/**` publicly accessible; basic IP rate limiting applied

- [ ] **Step 1: Add `/s/**` permit and admin paths to SecurityConfig**

Modify `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java` — in the `authorizeHttpRequests` block, add:

```java
                        // 学校自助查询：公开页面（由 SchoolRateLimitFilter 提供频控）
                        .requestMatchers("/s/**").permitAll()
                        // 学校管理后台：仅 admin 可访问
                        .requestMatchers("/admin/schools/**").hasRole("ADMIN")
                        // 系统配置管理：仅 admin 可访问
                        .requestMatchers("/admin/system-config/**").hasRole("ADMIN")
                        // 学校入口二维码管理：仅 admin 可访问
                        .requestMatchers("/admin/school-entry/**").hasRole("ADMIN")
```

Place these BEFORE `.anyRequest().authenticated()`.

- [ ] **Step 2: Create SchoolRateLimitFilter**

Create `src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java`:

```java
package com.bookstore.qrcode.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学校自助查询 IP 频控过滤器。
 * <p>
 * 仅拦截 /s/** 路径，对同一 IP 做滑动窗口限流。
 * 后续可升级为 Redis 滑动窗口 + 图形验证码。
 * </p>
 */
@Slf4j
public class SchoolRateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000;
    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        String path = request.getRequestURI();
        if (!path.startsWith("/s")) {
            chain.doFilter(req, resp);
            return;
        }

        String ip = request.getRemoteAddr();
        SlidingWindow window = windows.computeIfAbsent(ip, k -> new SlidingWindow());

        synchronized (window) {
            long now = System.currentTimeMillis();
            window.prune(now);
            if (window.count >= MAX_REQUESTS_PER_MINUTE) {
                response.setStatus(429);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("请求过于频繁，请稍后再试");
                log.warn("Rate limit exceeded for IP: {}", ip);
                return;
            }
            window.hits[window.head] = now;
            window.head = (window.head + 1) % window.hits.length;
            window.count++;
        }

        chain.doFilter(req, resp);
    }

    private static class SlidingWindow {
        long[] hits = new long[MAX_REQUESTS_PER_MINUTE];
        int head = 0;
        int count = 0;

        void prune(long now) {
            long cutoff = now - WINDOW_MS;
            int newCount = 0;
            int tail = (head - count + hits.length) % hits.length;
            for (int i = 0; i < count; i++) {
                int idx = (tail + i) % hits.length;
                if (hits[idx] >= cutoff) {
                    hits[(head - newCount + hits.length) % hits.length] = hits[idx];
                    newCount++;
                }
            }
            count = newCount;
        }
    }
}
```

- [ ] **Step 3: Register filter in SecurityConfig**

In `SecurityConfig.java`, add before `return http.build()`:

```java
                .addFilterBefore(new SchoolRateLimitFilter(),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class);
```

- [ ] **Step 4: Verify compilation**

Run: `mvnw compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/SecurityConfig.java src/main/java/com/bookstore/qrcode/config/SchoolRateLimitFilter.java
git commit -m "feat: permit /s/** publicly; add IP rate limiting filter for school entry"
```

---

### Task 12: Admin School Management Page

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` — or create a new controller
- Create: `src/main/resources/templates/admin/schools.html`

**Consumes:** SchoolRepository (Task 3), SchoolService (Task 6)

**Produces:** Admin page for CRUD + Excel import of schools

- [ ] **Step 1: Create AdminSchoolController**

Create `src/main/java/com/bookstore/qrcode/controller/AdminSchoolController.java`:

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 学校管理后台控制器。
 */
@Controller
@RequestMapping("/admin/schools")
@RequiredArgsConstructor
public class AdminSchoolController {

    private final SchoolRepository schoolRepository;

    /** 列表页 */
    @GetMapping
    public String list(@RequestParam(required = false) String city,
                       @RequestParam(required = false) String district,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<School> schools = schoolRepository.findByFilters(city, district,
                PageRequest.of(page, 20));
        model.addAttribute("schools", schools);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("cities", schoolRepository.findDistinctCities());
        return "admin/schools";
    }

    /** 保存（新增/编辑） */
    @PostMapping("/save")
    public String save(@ModelAttribute School school, RedirectAttributes ra) {
        school.setDeleted(false);
        schoolRepository.save(school);
        ra.addFlashAttribute("message", "保存成功");
        return "redirect:/admin/schools";
    }

    /** 软删除 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        schoolRepository.findById(id).ifPresent(s -> {
            s.setDeleted(true);
            schoolRepository.save(s);
        });
        ra.addFlashAttribute("message", "已删除");
        return "redirect:/admin/schools";
    }

    /** 同步 has_qrcode 状态 */
    @PostMapping("/sync-status")
    public String syncStatus(RedirectAttributes ra) {
        // Will be implemented with QrCodeRepository in a separate step
        ra.addFlashAttribute("message", "状态同步已触发");
        return "redirect:/admin/schools";
    }

    /** CSV 批量导入 */
    @PostMapping("/import")
    public String importCsv(@RequestParam("file") MultipartFile file,
                            RedirectAttributes ra) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            int count = 0;
            String line;
            reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",", 5);
                if (cols.length < 5) continue;
                School school = School.builder()
                        .schoolId(cols[0].trim())
                        .schoolName(cols[1].trim())
                        .regionCity(cols[2].trim())
                        .regionDistrict(cols[3].trim())
                        .hasQrcode(false)
                        .deleted(false)
                        .build();
                schoolRepository.save(school);
                count++;
            }
            ra.addFlashAttribute("message", "成功导入 " + count + " 所学校");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "导入失败: " + e.getMessage());
        }
        return "redirect:/admin/schools";
    }
}
```

- [ ] **Step 2: Create admin schools template**

Create `src/main/resources/templates/admin/schools.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: main(~{::title}, ~{::#content})}">
<head>
    <title>学校管理 - 火马管理后台</title>
</head>
<body>
<div id="content">
    <h3 class="mb-3">学校管理</h3>

    <div th:if="${message}" class="alert alert-success" th:text="${message}"></div>
    <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>

    <!-- Filter -->
    <form class="row g-2 mb-3" method="get">
        <div class="col-auto">
            <select name="city" class="form-select form-select-sm">
                <option value="">全部市州</option>
                <option th:each="c : ${cities}" th:value="${c}" th:text="${c}"
                        th:selected="${city == c}"></option>
            </select>
        </div>
        <div class="col-auto">
            <button type="submit" class="btn btn-sm btn-primary">筛选</button>
        </div>
        <div class="col-auto ms-auto">
            <button type="button" class="btn btn-sm btn-outline-success"
                    data-bs-toggle="modal" data-bs-target="#importModal">📥 CSV导入</button>
        </div>
    </form>

    <!-- Table -->
    <table class="table table-sm table-hover">
        <thead>
            <tr><th>学校ID</th><th>名称</th><th>市州</th><th>区县</th><th>有活码</th><th>操作</th></tr>
        </thead>
        <tbody>
            <tr th:each="s : ${schools.content}">
                <td th:text="${s.schoolId}"></td>
                <td th:text="${s.schoolName}"></td>
                <td th:text="${s.regionCity}"></td>
                <td th:text="${s.regionDistrict}"></td>
                <td>
                    <span th:if="${s.hasQrcode}" class="badge bg-success">是</span>
                    <span th:unless="${s.hasQrcode}" class="badge bg-secondary">否</span>
                </td>
                <td>
                    <form th:action="@{/admin/schools/{id}/delete(id=${s.id})}"
                          method="post" style="display:inline"
                          onsubmit="return confirm('确定删除？')">
                        <button class="btn btn-sm btn-outline-danger">删除</button>
                    </form>
                </td>
            </tr>
        </tbody>
    </table>

    <!-- Pagination -->
    <div th:if="${schools.totalPages > 1}">
        <span th:text="'共 ' + ${schools.totalElements} + ' 条，第 ' + (${schools.number}+1) + '/' + ${schools.totalPages} + ' 页'"></span>
    </div>

    <!-- Import Modal -->
    <div class="modal fade" id="importModal" tabindex="-1">
        <div class="modal-dialog">
            <div class="modal-content">
                <form th:action="@{/admin/schools/import}" method="post" enctype="multipart/form-data">
                    <div class="modal-header"><h5>CSV 批量导入学校</h5></div>
                    <div class="modal-body">
                        <p class="text-muted small">格式：school_id,school_name,region_city,region_district（无表头）</p>
                        <input type="file" name="file" accept=".csv" class="form-control" required>
                    </div>
                    <div class="modal-footer">
                        <button type="submit" class="btn btn-primary">导入</button>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 3: Add nav link in layout.html**

Modify `src/main/resources/templates/layout.html` — add in the admin nav sidebar/navbar:

```html
                            <li><a class="dropdown-item" href="/admin/schools">🏫 学校管理</a></li>
                            <li><a class="dropdown-item" href="/admin/system-config">⚙️ 系统配置</a></li>
                            <li><a class="dropdown-item" href="/admin/school-entry">📱 入口二维码</a></li>
```

Place alongside the existing `/admin/district-managers` and `/admin/download-stats` links.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/AdminSchoolController.java src/main/resources/templates/admin/schools.html src/main/resources/templates/layout.html
git commit -m "feat: add admin school management page with CSV import"
```

---

### Task 13: Admin System Config Page

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/AdminSystemConfigController.java`
- Create: `src/main/resources/templates/admin/system-config.html`

**Consumes:** SystemConfigRepository (Task 3)

**Produces:** Simple admin page to edit global contact name

- [ ] **Step 1: Create AdminSystemConfigController**

Create `src/main/java/com/bookstore/qrcode/controller/AdminSystemConfigController.java`:

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/system-config")
@RequiredArgsConstructor
public class AdminSystemConfigController {

    private final SystemConfigRepository configRepository;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("configs", configRepository.findAll());
        return "admin/system-config";
    }

    @PostMapping("/save")
    public String save(@RequestParam String configKey,
                       @RequestParam String configValue,
                       RedirectAttributes ra) {
        SystemConfig config = configRepository.findById(configKey)
                .orElse(new SystemConfig());
        config.setConfigKey(configKey);
        config.setConfigValue(configValue);
        configRepository.save(config);
        ra.addFlashAttribute("message", "配置已更新");
        return "redirect:/admin/system-config";
    }
}
```

- [ ] **Step 2: Create admin system-config template**

Create `src/main/resources/templates/admin/system-config.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: main(~{::title}, ~{::#content})}">
<head><title>系统配置 - 火马管理后台</title></head>
<body>
<div id="content">
    <h3 class="mb-3">系统配置</h3>
    <div th:if="${message}" class="alert alert-success" th:text="${message}"></div>

    <table class="table table-sm">
        <thead><tr><th>配置键</th><th>配置值</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
            <tr th:each="c : ${configs}">
                <td th:text="${c.configKey}"></td>
                <td>
                    <form th:action="@{/admin/system-config/save}" method="post" class="d-flex gap-2">
                        <input type="hidden" name="configKey" th:value="${c.configKey}">
                        <input type="text" name="configValue" th:value="${c.configValue}"
                               class="form-control form-control-sm" style="max-width:400px">
                        <button type="submit" class="btn btn-sm btn-primary">保存</button>
                    </form>
                </td>
                <td th:text="${c.updatedAt}"></td>
                <td></td>
            </tr>
        </tbody>
    </table>
</div>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/AdminSystemConfigController.java src/main/resources/templates/admin/system-config.html
git commit -m "feat: add admin system config page for global contact settings"
```

---

### Task 14: Admin School Entry QR Page

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/AdminSchoolEntryController.java`
- Create: `src/main/resources/templates/admin/school-entry.html`

**Consumes:** ZXing (existing dependency), QrAccessLogRepository (Task 3)

**Produces:** Admin page showing entry QR code + download + visit stats

- [ ] **Step 1: Create AdminSchoolEntryController**

Create `src/main/java/com/bookstore/qrcode/controller/AdminSchoolEntryController.java`:

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;

@Controller
@RequestMapping("/admin/school-entry")
@RequiredArgsConstructor
public class AdminSchoolEntryController {

    private final QrAccessLogRepository logRepository;

    @GetMapping
    public String index(Model model) {
        long viewCount = logRepository.countByChannel(QrAccessLog.Channel.school);
        model.addAttribute("entryUrl", "/s");
        model.addAttribute("viewCount", viewCount);
        return "admin/school-entry";
    }

    /** 动态生成入口二维码 PNG */
    @GetMapping(value = "/qr-image", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] qrImage() throws Exception {
        String baseUrl = "/s"; // In production, prepend the actual domain
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(baseUrl, BarcodeFormat.QR_CODE, 300, 300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }
}
```

- [ ] **Step 2: Create admin school-entry template**

Create `src/main/resources/templates/admin/school-entry.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: main(~{::title}, ~{::#content})}">
<head><title>学校入口二维码 - 火马管理后台</title></head>
<body>
<div id="content">
    <h3 class="mb-3">学校自助查询入口</h3>

    <div class="card mb-3" style="max-width:400px">
        <div class="card-body text-center">
            <img th:src="@{/admin/school-entry/qr-image}" alt="入口二维码"
                 class="img-fluid mb-3" style="max-width:300px">
            <p class="text-muted small">访问地址：<code th:text="${entryUrl}">/s</code></p>
            <a th:href="@{/admin/school-entry/qr-image}" download="school-entry-qr.png"
               class="btn btn-primary">📥 下载二维码</a>
        </div>
    </div>

    <div class="mt-3">
        <p class="text-muted">累计访问次数：<strong th:text="${viewCount}">0</strong></p>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/AdminSchoolEntryController.java src/main/resources/templates/admin/school-entry.html
git commit -m "feat: add admin school entry QR page with ZXing generation and visit stats"
```

---

### Task 15: Integration — Wire Everything Together and Test

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java` — final review

**Consumes:** All previous tasks

**Produces:** Working end-to-end feature

- [ ] **Step 1: Full compilation check**

Run: `mvnw compile`

Expected: BUILD SUCCESS with zero errors

- [ ] **Step 2: Start application**

Run: `mvnw spring-boot:run`

- [ ] **Step 3: Manual smoke test — public pages**

```
1. GET http://localhost:8080/s → Should show city list + global contact card
2. Click a city → HTMX replaces with district list + breadcrumb
3. Click a district → HTMX replaces with school list + status badges
4. Click a school with active QR → detail page with QR image + download button
5. Click a school without QR → detail page with manager QR + guide steps
6. Click "保存活码到手机" → download PNG file
7. Click global contact → shows global contact page
8. Search "第一" → shows matching results
```

- [ ] **Step 4: Manual smoke test — admin pages**

```
1. GET http://localhost:8080/admin/schools → requires login → school list
2. POST CSV import → schools appear in list
3. GET http://localhost:8080/admin/system-config → edit global_contact_name
4. GET http://localhost:8080/admin/school-entry → QR image renders + download works
```

- [ ] **Step 5: Test rate limiting**

```
Run: for i in $(seq 1 35); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/s; done
Expected: First 30 return 200, subsequent return 429
```

- [ ] **Step 6: Test audit logging**

```
1. Visit a school detail page → check DB:
   SELECT * FROM qr_access_log WHERE channel='school' ORDER BY accessed_at DESC LIMIT 1;
2. Download a QR → should create a 'download' action log
```

- [ ] **Step 7: Commit final integration**

```bash
git add -A
git commit -m "feat: final integration — school self-service end-to-end working"
```

---

### Task 16: Production Polish

**Files:**
- Modify: `src/main/resources/templates/school/detail.html` — fix QR image URL to use full WeChat CDN URL
- Modify: `src/main/java/com/bookstore/qrcode/controller/AdminSchoolEntryController.java` — use configurable base URL

**Consumes:** All previous tasks

**Produces:** Production-ready deployment

- [ ] **Step 1: Make entry QR URL configurable**

Modify `AdminSchoolEntryController.java` — inject base URL:

```java
    @Value("${app.school-entry-url:http://localhost:8080/s}")
    private String schoolEntryUrl;

    // In qrImage(), use schoolEntryUrl instead of "/s"
```

Add to `application.yml`:

```yaml
app:
  school-entry-url: https://yourdomain.com/s
```

- [ ] **Step 2: Verify all HTMX paths work behind a reverse proxy**

Test: All `hx-get` URLs are relative paths (e.g., `/s/districts` not `http://localhost:8080/s/districts`).

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "chore: production polish for school self-service — configurable entry URL"
```

---

## Implementation Summary

| Phase | Tasks | Files Created | Files Modified |
|---|---|---|---|
| Foundation | 1-3 | 9 | 2 |
| Business Logic | 4-6 | 4 | 1 |
| Web Layer | 7-10 | 9 | 0 |
| Security | 11 | 1 | 1 |
| Admin | 12-14 | 6 | 1 |
| Integration | 15-16 | 0 | 3 |

**Total: ~16 tasks, 29 files created, 8 files modified, zero new Maven dependencies.**
