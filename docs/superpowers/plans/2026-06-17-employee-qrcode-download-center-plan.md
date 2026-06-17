# 员工活码下载中心 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在火马平台中新增员工活码下载中心模块，企微 OAuth 登录、卡片式活码浏览与下载、下载追踪、区县负责人配置。

**Architecture:** 集成在现有 Spring Boot 3.2 + JPA + Thymeleaf + Bootstrap 5 项目中。新增 2 张表、4 个 Controller、3 个 Service、2 个 Entity、独立前端布局模板。不走独立部署，复用现有数据层和企微 API 客户端。

**Tech Stack:** Spring Boot 3.2, Spring Data JPA, Spring Security, Thymeleaf, htmx 1.9, Bootstrap 5.3, MySQL, Redis

## Global Constraints

- 所有数据库变更通过 `schema.sql` DDL 管理（`ddl-auto: none`）
- 新实体使用 Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- 实体使用 `@PrePersist` / `@PreUpdate` 自动管理时间戳，与现有模式一致
- 企微配置从现有 `WecomConfig` 读取，环境变量注入
- 前端沿用 Thymeleaf + Bootstrap 5 + htmx，下载中心使用独立布局模板
- 下载中心页面不包含管理后台导航栏

---

## Task 1: 数据库 schema — 新增两张表

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: 在 schema.sql 末尾追加 `qr_download_log` 和 `district_manager` 建表 DDL**

```sql
-- ============================================
-- 新增表：下载中心
-- ============================================

-- qr_download_log：活码下载日志
CREATE TABLE IF NOT EXISTS qr_download_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL COMMENT '活码ID',
    agent_userid VARCHAR(100) NOT NULL COMMENT '下载员工企微userid',
    downloaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时间',
    ip_address VARCHAR(50) COMMENT '下载来源IP',
    CONSTRAINT fk_download_qrcode FOREIGN KEY (qr_code_id) REFERENCES qr_code(id),
    INDEX idx_log_qrcode (qr_code_id),
    INDEX idx_log_userid (agent_userid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码下载日志';

-- district_manager：区县负责人配置
CREATE TABLE IF NOT EXISTS district_manager (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    region_city VARCHAR(50) NOT NULL COMMENT '城市',
    region_district VARCHAR(50) NOT NULL COMMENT '区/县',
    manager_userid VARCHAR(100) NOT NULL COMMENT '负责人企微userid',
    manager_name VARCHAR(100) NOT NULL COMMENT '负责人姓名',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_district (region_city, region_district),
    INDEX idx_manager_city (region_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区县负责人配置';
```

- [ ] **Step 2: 启动应用验证建表成功**

```bash
# 确保 MySQL 可连接，启动 Spring Boot
# 检查日志无 schema 错误
# 验证表已创建：
mysql -u root -p bookstore_qrcode -e "SHOW CREATE TABLE qr_download_log\G"
mysql -u root -p bookstore_qrcode -e "SHOW CREATE TABLE district_manager\G"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: 新增 qr_download_log 和 district_manager 建表 DDL

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: 实体类 — QrDownloadLog 和 DistrictManager

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/QrDownloadLog.java`
- Create: `src/main/java/com/bookstore/qrcode/entity/DistrictManager.java`

**Interfaces:**
- Produces: `QrDownloadLog` (id, qrCodeId, agentUserid, downloadedAt, ipAddress), `DistrictManager` (id, regionCity, regionDistrict, managerUserid, managerName, createdAt, updatedAt)

- [ ] **Step 1: 创建 `QrDownloadLog.java`**

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 活码下载日志实体。
 * <p>
 * 每次员工下载活码二维码时写入一条记录。
 * 同一员工多次下载同一活码产生多条记录（非 upsert），
 * 下载次数通过 COUNT 聚合计算。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Entity
@Table(name = "qr_download_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrDownloadLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被下载的活码 ID */
    @Column(name = "qr_code_id", nullable = false)
    private Long qrCodeId;

    /** 下载员工的企微 userid */
    @Column(name = "agent_userid", nullable = false, length = 100)
    private String agentUserid;

    /** 下载时间 */
    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;

    /** 下载来源 IP */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @PrePersist
    void prePersist() {
        if (downloadedAt == null) {
            downloadedAt = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 2: 创建 `DistrictManager.java`**

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 区县负责人配置实体。
 * <p>
 * 城市 + 区县 唯一确定一位负责人。
 * 活码通过 regionCity + regionDistrict 自动关联展示其负责人。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Entity
@Table(name = "district_manager")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistrictManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 城市 */
    @Column(name = "region_city", nullable = false, length = 50)
    private String regionCity;

    /** 区/县 */
    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    /** 负责人企微 userid */
    @Column(name = "manager_userid", nullable = false, length = 100)
    private String managerUserid;

    /** 负责人姓名（冗余展示，从 Employee/Agent 表同步） */
    @Column(name = "manager_name", nullable = false, length = 100)
    private String managerName;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
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

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/entity/QrDownloadLog.java \
        src/main/java/com/bookstore/qrcode/entity/DistrictManager.java
git commit -m "feat: 新增 QrDownloadLog 和 DistrictManager 实体

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Repository 层

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/repository/QrDownloadLogRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/DistrictManagerRepository.java`

**Interfaces:**
- Consumes: `QrDownloadLog`, `DistrictManager` (from Task 2)
- Produces: `QrDownloadLogRepository` (countByQrCodeIdAndAgentUserid, findByAgentUseridOrderByDownloadedAtDesc, findByQrCodeId, existsByQrCodeIdAndAgentUserid, countByQrCodeId), `DistrictManagerRepository` (findByRegionCityAndRegionDistrict, findByRegionCity, findAll)

- [ ] **Step 1: 创建 `QrDownloadLogRepository.java`**

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrDownloadLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 下载日志数据访问层。
 */
public interface QrDownloadLogRepository extends JpaRepository<QrDownloadLog, Long> {

    /** 统计某员工下载某活码的总次数 */
    @Query("SELECT COUNT(d) FROM QrDownloadLog d WHERE d.qrCodeId = :qrCodeId AND d.agentUserid = :agentUserid")
    long countByQrCodeIdAndAgentUserid(@Param("qrCodeId") Long qrCodeId,
                                       @Param("agentUserid") String agentUserid);

    /** 查询某员工是否有某活码的下载记录 */
    @Query("SELECT COUNT(d) > 0 FROM QrDownloadLog d WHERE d.qrCodeId = :qrCodeId AND d.agentUserid = :agentUserid")
    boolean existsByQrCodeIdAndAgentUserid(@Param("qrCodeId") Long qrCodeId,
                                           @Param("agentUserid") String agentUserid);

    /** 某员工的下载历史（按时间倒序） */
    List<QrDownloadLog> findByAgentUseridOrderByDownloadedAtDesc(String agentUserid);

    /** 某活码的全部下载记录 */
    List<QrDownloadLog> findByQrCodeId(Long qrCodeId);

    /** 某活码的下载总次数 */
    long countByQrCodeId(Long qrCodeId);

    /** 批量查询一批活码的下载人（供统计页用） */
    List<QrDownloadLog> findByQrCodeIdIn(List<Long> qrCodeIds);
}
```

- [ ] **Step 2: 创建 `DistrictManagerRepository.java`**

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.DistrictManager;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 区县负责人配置数据访问层。
 */
public interface DistrictManagerRepository extends JpaRepository<DistrictManager, Long> {

    /** 按城市+区县精确查找负责人 */
    Optional<DistrictManager> findByRegionCityAndRegionDistrict(String regionCity, String regionDistrict);

    /** 按城市查找所有区县的负责人列表 */
    List<DistrictManager> findByRegionCity(String regionCity);

    /** 检查某城市+区县是否已配置 */
    boolean existsByRegionCityAndRegionDistrict(String regionCity, String regionDistrict);
}
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/QrDownloadLogRepository.java \
        src/main/java/com/bookstore/qrcode/repository/DistrictManagerRepository.java
git commit -m "feat: 新增下载日志和区县负责人 Repository

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: 企微 API 客户端 — 新增 getUserInfo 方法

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`

**Interfaces:**
- Produces: `WecomApiClient.getUserInfo(String code)` → `JsonNode` (含 userid, UserId 字段)

- [ ] **Step 1: 在 WecomApiClient 末尾添加 `getUserInfo` 方法**

在文件末尾（最后一个 `}` 之前）添加：

```java
    // ========================================================================
    //  网页授权 (OAuth)
    //  文档: https://developer.work.weixin.qq.com/document/path/91023
    // ========================================================================

    /**
     * 通过 OAuth code 获取企微用户身份。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/user/getuserinfo?access_token=TOKEN&code=CODE}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "UserId": "zhangsan",
     *   "DeviceId": "xxx"
     * }
     * </pre>
     * <p>注意：企微返回字段首字母大写 {@code UserId}（与通讯录 API 的 {@code userid} 不同）。</p>
     *
     * @param code OAuth 授权临时 code（有效期 5 分钟，仅可使用一次）
     * @return JsonNode 含 errcode + UserId
     * @throws RuntimeException 接口调用失败时抛出
     */
    public JsonNode getUserInfo(String code) {
        String token = getAccessToken();
        String url = BASE_URL + "/user/getuserinfo?access_token=" + token + "&code=" + code;
        try {
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(resp);
            int errcode = node.has("errcode") ? node.get("errcode").asInt() : -1;
            if (errcode != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "未知错误";
                log.error("getuserinfo 失败: errcode={}, errmsg={}", errcode, errmsg);
                throw new RuntimeException("获取用户信息失败 [" + errcode + "]: " + errmsg);
            }
            return node;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("getuserinfo 请求异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构造企微网页授权 URL。
     * <p>
     * 文档: https://developer.work.weixin.qq.com/document/path/91022
     * <p>静默授权（snsapi_base）：不弹窗，仅获取 userid，用于企业内部应用。</p>
     *
     * @param redirectUri 回调地址（需已在企微应用设置的可信域名下）
     * @param state       自定义参数（如防 CSRF token），回调时原样返回
     * @return 完整授权 URL
     */
    public String buildOAuthUrl(String redirectUri, String state) {
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
            + "?appid=" + config.getCorpId()
            + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&scope=snsapi_base"
            + "&state=" + state
            + "#wechat_redirect";
    }
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java
git commit -m "feat: WecomApiClient 新增 getUserInfo 和 buildOAuthUrl 方法

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: WecomOAuthService — 企微 OAuth 认证服务

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/WecomOAuthService.java`

**Interfaces:**
- Consumes: `WecomApiClient` (Task 4), `EmployeeRepository` (existing), `WecomConfig` (existing)
- Produces: `WecomOAuthService.authenticate(String code)` → 返回 Employee entity 或抛异常

- [ ] **Step 1: 创建 `WecomOAuthService.java`**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.WecomConfig;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 企微 OAuth 网页授权服务。
 * <p>
 * 处理企微 OAuth2.0 授权流程：构造授权 URL → 回调获取 code → 换取 userid → 写入 Session。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WecomOAuthService {

    private final WecomApiClient wecomApiClient;
    private final WecomConfig wecomConfig;
    private final EmployeeRepository employeeRepository;

    /** Session 属性名 */
    public static final String SESSION_EMPLOYEE_USERID = "employeeUserid";
    public static final String SESSION_EMPLOYEE_NAME = "employeeName";

    /**
     * 构造授权 URL 并返回。
     *
     * @param redirectUri 回调完整 URL
     * @return 企微 OAuth 授权 URL
     */
    public String buildAuthUrl(String redirectUri) {
        String state = UUID.randomUUID().toString().substring(0, 8);
        return wecomApiClient.buildOAuthUrl(redirectUri, state);
    }

    /**
     * OAuth 回调处理：用 code 换取 userid，在校验员工身份后写入 Session。
     *
     * @param code    OAuth 授权临时 code
     * @param session HTTP Session
     * @return Employee 实体
     * @throws RuntimeException 员工不存在、已离职、或企微 API 调用失败
     */
    public Employee authenticate(String code, HttpSession session) {
        // 1. 用 code 换 userid
        JsonNode result = wecomApiClient.getUserInfo(code);
        String userid = result.has("UserId") ? result.get("UserId").asText() : null;
        if (userid == null || userid.isEmpty()) {
            throw new RuntimeException("企微返回的用户 ID 为空");
        }
        log.info("OAuth 认证成功，userid={}", userid);

        // 2. 校验员工是否在本地通讯录中且在职
        Employee employee = employeeRepository.findByUserid(userid)
            .orElseThrow(() -> new RuntimeException("该企微账号未在系统中注册"));

        if (!employee.getActive()) {
            throw new RuntimeException("该员工已离职，无法访问下载中心");
        }

        // 3. 写入 Session
        session.setAttribute(SESSION_EMPLOYEE_USERID, employee.getUserid());
        session.setAttribute(SESSION_EMPLOYEE_NAME, employee.getName());
        log.info("员工已登录: userid={}, name={}", employee.getUserid(), employee.getName());

        return employee;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/WecomOAuthService.java
git commit -m "feat: 新增 WecomOAuthService 企微 OAuth 认证服务

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: DownloadLogService — 下载日志与统计服务

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/DownloadLogService.java`

**Interfaces:**
- Consumes: `QrDownloadLogRepository` (Task 3), `QrAgentRepository` (existing), `QrCodeRepository` (existing), `DistrictManagerRepository` (Task 3)
- Produces: `DownloadLogService.recordDownload(qrCodeId, agentUserid, ipAddress)`, `.getPersonalHistory(agentUserid)`, `.getDownloadStats(qrCodeId, filters)`

- [ ] **Step 1: 创建 `DownloadLogService.java`**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrDownloadLog;
import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 下载日志服务。
 * <p>
 * 管理活码下载的追踪记录、个人历史查询和全局统计。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadLogService {

    private final QrDownloadLogRepository downloadLogRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final DistrictManagerRepository districtManagerRepo;

    /**
     * 记录一次下载。
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 下载员工 userid
     * @param ipAddress   来源 IP
     */
    @Transactional
    public void recordDownload(Long qrCodeId, String agentUserid, String ipAddress) {
        QrDownloadLog log = QrDownloadLog.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(agentUserid)
            .downloadedAt(LocalDateTime.now())
            .ipAddress(ipAddress)
            .build();
        downloadLogRepo.save(log);
    }

    /**
     * 当前员工下载过哪些活码（活码 ID 集合），用于卡片"已下载"标记。
     */
    public Set<Long> getDownloadedQrCodeIds(String agentUserid) {
        return downloadLogRepo.findByAgentUseridOrderByDownloadedAtDesc(agentUserid)
            .stream()
            .map(QrDownloadLog::getQrCodeId)
            .collect(Collectors.toSet());
    }

    /**
     * 某员工下载某活码的次数。
     */
    public long getDownloadCount(Long qrCodeId, String agentUserid) {
        return downloadLogRepo.countByQrCodeIdAndAgentUserid(qrCodeId, agentUserid);
    }

    /**
     * 员工个人下载历史（每次下载一条）。
     */
    public List<Map<String, Object>> getPersonalHistory(String agentUserid) {
        List<QrDownloadLog> logs = downloadLogRepo.findByAgentUseridOrderByDownloadedAtDesc(agentUserid);
        List<Map<String, Object>> result = new ArrayList<>();

        for (QrDownloadLog log : logs) {
            QrCode qr = qrCodeRepo.findById(log.getQrCodeId()).orElse(null);
            if (qr == null) continue;

            DistrictManager manager = districtManagerRepo
                .findByRegionCityAndRegionDistrict(qr.getRegionCity(), qr.getRegionDistrict())
                .orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schoolName", qr.getSchoolName());
            row.put("regionCity", qr.getRegionCity());
            row.put("regionDistrict", qr.getRegionDistrict());
            row.put("managerName", manager != null ? manager.getManagerName() : "—");
            row.put("downloadedAt", log.getDownloadedAt());
            result.add(row);
        }
        return result;
    }

    /**
     * 全局下载统计（管理后台用）。
     * <p>
     * 返回结构：活码 → 绑定员工 → 每人下载状态和次数。
     * </p>
     */
    public Map<String, Object> getGlobalStats(String city, String district, String managerUserid,
                                               String downloadStatus, int page, int size) {
        // 1. 查询活码（带地区筛选）
        List<QrCode> allQrCodes = qrCodeRepo.findAll();
        List<QrCode> filteredQrCodes = allQrCodes.stream()
            .filter(q -> city == null || city.isEmpty() || q.getRegionCity().equals(city))
            .filter(q -> district == null || district.isEmpty() || q.getRegionDistrict().equals(district))
            .toList();

        // 2. 构建负责人缓存：city+district → DistrictManager
        Map<String, DistrictManager> managerCache = new HashMap<>();
        List<DistrictManager> allManagers = districtManagerRepo.findAll();
        for (DistrictManager m : allManagers) {
            managerCache.put(m.getRegionCity() + "|" + m.getRegionDistrict(), m);
        }

        // 3. 收集所有相关下载日志
        List<Long> qrCodeIds = filteredQrCodes.stream().map(QrCode::getId).toList();
        Map<Long, List<QrDownloadLog>> downloadLogByQrCode = new HashMap<>();
        if (!qrCodeIds.isEmpty()) {
            List<QrDownloadLog> allLogs = downloadLogRepo.findByQrCodeIdIn(qrCodeIds);
            for (QrDownloadLog log : allLogs) {
                downloadLogByQrCode.computeIfAbsent(log.getQrCodeId(), k -> new ArrayList<>()).add(log);
            }
        }

        // 4. 构建统计行
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalDownloaded = 0;
        int totalNotDownloaded = 0;

        for (QrCode qr : filteredQrCodes) {
            String key = qr.getRegionCity() + "|" + qr.getRegionDistrict();
            DistrictManager manager = managerCache.get(key);

            // 按负责人筛选
            if (managerUserid != null && !managerUserid.isEmpty()) {
                if (manager == null || !manager.getManagerUserid().equals(managerUserid)) {
                    continue;
                }
            }

            List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId());
            List<QrDownloadLog> logs = downloadLogByQrCode.getOrDefault(qr.getId(), List.of());

            for (QrAgent agent : agents) {
                if (agent.getStatus() == QrAgent.AgentStatus.removed) continue;

                long count = logs.stream()
                    .filter(l -> l.getAgentUserid().equals(agent.getAgentUserid()))
                    .count();
                boolean downloaded = count > 0;

                if (downloadStatus != null) {
                    if ("downloaded".equals(downloadStatus) && !downloaded) continue;
                    if ("not_downloaded".equals(downloadStatus) && downloaded) continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("qrCodeId", qr.getId());
                row.put("schoolName", qr.getSchoolName());
                row.put("regionCity", qr.getRegionCity());
                row.put("regionDistrict", qr.getRegionDistrict());
                row.put("managerName", manager != null ? manager.getManagerName() : "—");
                row.put("agentUserid", agent.getAgentUserid());
                row.put("downloaded", downloaded);
                row.put("downloadCount", count);
                row.put("lastDownloadAt", logs.stream()
                    .filter(l -> l.getAgentUserid().equals(agent.getAgentUserid()))
                    .map(QrDownloadLog::getDownloadedAt)
                    .max(LocalDateTime::compareTo)
                    .orElse(null));
                rows.add(row);

                if (downloaded) totalDownloaded++; else totalNotDownloaded++;
            }
        }

        // 5. 分页
        int total = rows.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> pageRows = fromIndex < total
            ? rows.subList(fromIndex, toIndex) : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", pageRows);
        result.put("totalPages", Math.max(1, (int) Math.ceil((double) total / size)));
        result.put("currentPage", page);
        result.put("totalRows", total);
        result.put("totalQrCodes", filteredQrCodes.size());
        result.put("totalDownloaded", totalDownloaded);
        result.put("totalNotDownloaded", totalNotDownloaded);
        result.put("totalDownloads", rows.stream().mapToLong(r -> (long) r.get("downloadCount")).sum());
        return result;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/DownloadLogService.java
git commit -m "feat: 新增 DownloadLogService 下载日志与统计服务

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: DistrictManagerService — 区县负责人管理服务

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/DistrictManagerService.java`

**Interfaces:**
- Consumes: `DistrictManagerRepository` (Task 3), `EmployeeRepository` (existing)
- Produces: `DistrictManagerService.getManagerForQrCode(QrCode)` → Optional&lt;DistrictManager&gt;, CRUD 方法

- [ ] **Step 1: 创建 `DistrictManagerService.java`**

```java
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

    /** 获取全部城市列表（从活码数据去重） */
    public List<String> getDistinctCities() {
        return districtManagerRepo.findAll().stream()
            .map(DistrictManager::getRegionCity)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/DistrictManagerService.java
git commit -m "feat: 新增 DistrictManagerService 区县负责人管理服务

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: 下载中心 Controller — 员工端页面 `/download`

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java`

**Interfaces:**
- Consumes: `WecomOAuthService` (Task 5), `DownloadLogService` (Task 6), `DistrictManagerService` (Task 7), `QrCodeRepository` (existing), `QrAgentRepository` (existing), `QrCodeService` (existing)
- Produces: GET `/download` (主页), GET `/download/oauth/entry` (OAuth 入口), GET `/download/oauth/callback` (回调), GET `/download/history` (历史), GET `/download/{id}/download` (下载+记录)

- [ ] **Step 1: 创建 `DownloadCenterController.java`**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工活码下载中心控制器。
 * <p>
 * 企微 OAuth 登录后，员工浏览/搜索/下载活码二维码，查看个人下载历史。
 * 所有路由挂载在 {@code /download} 下。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Controller
@RequestMapping("/download")
@RequiredArgsConstructor
public class DownloadCenterController {

    private final WecomOAuthService wecomOAuthService;
    private final DownloadLogService downloadLogService;
    private final DistrictManagerService districtManagerService;
    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeService qrCodeService;

    // ==================== OAuth 认证 ====================

    /**
     * OAuth 入口：构造授权 URL 并 302 跳转到企微。
     */
    @GetMapping("/oauth/entry")
    public String oauthEntry(HttpServletRequest request) {
        String redirectUri = request.getRequestURL().toString()
            .replace("/entry", "/callback");
        String authUrl = wecomOAuthService.buildAuthUrl(redirectUri);
        return "redirect:" + authUrl;
    }

    /**
     * OAuth 回调：企微带 code 回跳，完成认证后进入下载主页。
     */
    @GetMapping("/oauth/callback")
    public String oauthCallback(@RequestParam String code,
                                HttpSession session,
                                Model model) {
        try {
            Employee employee = wecomOAuthService.authenticate(code, session);
            return "redirect:/download";
        } catch (Exception e) {
            log.error("OAuth 认证失败: {}", e.getMessage());
            model.addAttribute("error", "认证失败：" + e.getMessage());
            return "download/error";
        }
    }

    // ==================== 下载主页 ====================

    /**
     * 活码浏览主页（卡片网格）。
     * <p>默认展示当前员工绑定的活码，可通过 mode=all 切换到全部活码。</p>
     */
    @GetMapping
    public String index(@RequestParam(required = false) String keyword,
                        @RequestParam(required = false) String mode,
                        @RequestParam(required = false) String managerUserid,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "12") int size,
                        HttpSession session,
                        Model model) {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            return "redirect:/download/oauth/entry";
        }
        String employeeName = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);

        // 1. 获取当前员工的绑定活码 ID 集合
        List<QrAgent> myAgents = qrAgentRepo.findByAgentUseridAndStatus(
            userid, QrAgent.AgentStatus.active);
        Set<Long> myQrCodeIds = myAgents.stream()
            .map(QrAgent::getQrCodeId)
            .collect(Collectors.toSet());

        // 2. 搜索活码
        Page<QrCode> qrCodePage;
        if ("all".equals(mode)) {
            // 全部活码模式
            qrCodePage = qrCodeRepo.search(keyword, null, null, QrCode.QrCodeStatus.active,
                PageRequest.of(page, size));
        } else {
            // 我的活码模式：只显示绑定的
            List<Long> ids = new ArrayList<>(myQrCodeIds);
            if (keyword != null && !keyword.isEmpty()) {
                ids = ids.stream()
                    .filter(id -> {
                        QrCode qr = qrCodeRepo.findById(id).orElse(null);
                        return qr != null && (qr.getSchoolName().contains(keyword)
                            || qr.getSchoolId().contains(keyword)
                            || qr.getRegionCity().contains(keyword)
                            || qr.getRegionDistrict().contains(keyword));
                    })
                    .toList();
            }
            int fromIdx = page * size;
            int toIdx = Math.min(fromIdx + size, ids.size());
            List<QrCode> pageContent = fromIdx < ids.size()
                ? ids.subList(fromIdx, toIdx).stream()
                    .map(qrCodeRepo::findById)
                    .filter(Optional::isPresent).map(Optional::get)
                    .toList()
                : List.of();
            qrCodePage = new org.springframework.data.domain.PageImpl<>(
                pageContent, PageRequest.of(page, size), ids.size());
        }

        // 3. 区县负责人缓存
        Map<String, DistrictManager> managerMap = districtManagerService.getAllAsMap();

        // 4. 已下载活码 ID（用于卡片标记）
        Set<Long> downloadedIds = downloadLogService.getDownloadedQrCodeIds(userid);

        // 5. 负责人筛选下拉列表
        List<Map<String, String>> managerOptions = managerMap.values().stream()
            .map(m -> Map.of("userid", m.getManagerUserid(), "name", m.getManagerName()))
            .distinct()
            .toList();

        model.addAttribute("employeeName", employeeName);
        model.addAttribute("qrCodes", qrCodePage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("mode", mode != null ? mode : "mine");
        model.addAttribute("managerUserid", managerUserid);
        model.addAttribute("managerMap", managerMap);
        model.addAttribute("downloadedIds", downloadedIds);
        model.addAttribute("managerOptions", managerOptions);
        model.addAttribute("downloadCounts", buildDownloadCounts(userid, qrCodePage.getContent()));
        return "download/index";
    }

    private Map<Long, Long> buildDownloadCounts(String userid, List<QrCode> qrCodes) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (QrCode qr : qrCodes) {
            counts.put(qr.getId(), downloadLogService.getDownloadCount(qr.getId(), userid));
        }
        return counts;
    }

    // ==================== 下载操作 ====================

    /**
     * 下载活码二维码图片（代理企微图片 + 记录日志）。
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id,
                         HttpSession session,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        QrCode qr = qrCodeService.getById(id);
        if (qr.getQrUrl() == null || qr.getQrUrl().isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "该活码暂无二维码图片");
            return;
        }

        // 记录下载日志
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        downloadLogService.recordDownload(id, userid, ip);

        // 代理下载企微图片
        URL url = new URL(qr.getQrUrl());
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.connect();
        String filename = qr.getRegionDistrict() + "-" + qr.getSchoolName() + "-" + qr.getRegionCity() + ".png";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.setContentLength(conn.getContentLength());
        try (InputStream in = conn.getInputStream()) {
            in.transferTo(response.getOutputStream());
        }
        response.getOutputStream().flush();
    }

    // ==================== 下载记录 ====================

    /**
     * 当前员工的下载历史。
     */
    @GetMapping("/history")
    public String history(HttpSession session, Model model) {
        String userid = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID);
        if (userid == null) {
            return "redirect:/download/oauth/entry";
        }
        String employeeName = (String) session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_NAME);

        List<Map<String, Object>> history = downloadLogService.getPersonalHistory(userid);
        long totalDownloads = history.size();
        long distinctSchools = history.stream()
            .map(r -> r.get("schoolName"))
            .distinct().count();

        model.addAttribute("employeeName", employeeName);
        model.addAttribute("history", history);
        model.addAttribute("totalDownloads", totalDownloads);
        model.addAttribute("distinctSchools", distinctSchools);
        return "download/history";
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/DownloadCenterController.java
git commit -m "feat: 新增 DownloadCenterController 员工下载中心

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: 管理后台 Controller — 下载统计与区县负责人配置

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/DownloadStatsController.java`
- Create: `src/main/java/com/bookstore/qrcode/controller/DistrictManagerController.java`

**Interfaces:**
- Consumes: `DownloadLogService` (Task 6), `DistrictManagerService` (Task 7), `QrCodeRepository` (existing), `EmployeeRepository` (existing)
- Produces: GET/POST `/admin/download-stats`, GET/POST `/admin/district-managers`

- [ ] **Step 1: 创建 `DownloadStatsController.java`**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DownloadLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 管理后台：下载统计页面。
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Controller
@RequestMapping("/admin/download-stats")
@RequiredArgsConstructor
public class DownloadStatsController {

    private final DownloadLogService downloadLogService;
    private final QrCodeRepository qrCodeRepo;

    @GetMapping
    public String stats(@RequestParam(required = false) String city,
                        @RequestParam(required = false) String district,
                        @RequestParam(required = false) String managerUserid,
                        @RequestParam(required = false) String downloadStatus,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {

        Map<String, Object> stats = downloadLogService.getGlobalStats(
            city, district, managerUserid, downloadStatus, page, size);

        // 城市/区县下拉列表
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        model.addAttribute("stats", stats);
        model.addAttribute("city", city);
        model.addAttribute("district", district);
        model.addAttribute("managerUserid", managerUserid);
        model.addAttribute("downloadStatus", downloadStatus);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        return "admin/download-stats";
    }
}
```

- [ ] **Step 2: 创建 `DistrictManagerController.java`**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.DistrictManager;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DistrictManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台：区县负责人配置页面。
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Controller
@RequestMapping("/admin/district-managers")
@RequiredArgsConstructor
public class DistrictManagerController {

    private final DistrictManagerService districtManagerService;
    private final QrCodeRepository qrCodeRepo;
    private final EmployeeRepository employeeRepo;

    @GetMapping
    public String list(Model model) {
        List<DistrictManager> managers = districtManagerService.findAll();
        List<String> cities = qrCodeRepo.findDistinctRegionCity();
        List<String> districts = qrCodeRepo.findDistinctRegionDistrict();

        // 员工列表（供新增/编辑弹窗选择负责人）
        List<Map<String, String>> employeeList = employeeRepo.findAllByActiveTrueOrderByName()
            .stream()
            .map(e -> Map.of("userid", e.getUserid(), "name", e.getName()))
            .collect(Collectors.toList());

        model.addAttribute("managers", managers);
        model.addAttribute("cities", cities);
        model.addAttribute("districts", districts);
        model.addAttribute("employeeList", employeeList);
        return "admin/district-managers";
    }

    @PostMapping("/create")
    public String create(@RequestParam String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam String managerUserid,
                         RedirectAttributes redirect) {
        try {
            districtManagerService.create(regionCity, regionDistrict, managerUserid);
            redirect.addFlashAttribute("message", "区县负责人已添加");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String managerUserid,
                         RedirectAttributes redirect) {
        try {
            districtManagerService.update(id, managerUserid);
            redirect.addFlashAttribute("message", "区县负责人已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            districtManagerService.delete(id);
            redirect.addFlashAttribute("message", "区县负责人已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/district-managers";
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/DownloadStatsController.java \
        src/main/java/com/bookstore/qrcode/controller/DistrictManagerController.java
git commit -m "feat: 新增下载统计和区县负责人管理 Controller

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: Security 配置 — 新增 /download/** 安全规则

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`

**Interfaces:**
- Consumes: 现有 Spring Security 配置
- Produces: `/download/**` 公开访问（OAuth 入口 + 回调），`/download/**` 受 Session 保护（通过自定义 Filter），`/admin/download-stats/**` 和 `/admin/district-managers/**` 受现有 admin/operator 认证保护

- [ ] **Step 1: 修改 `SecurityConfig.java`**

将 `securityFilterChain` 方法中的 `.authorizeHttpRequests` 修改为：

```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        // 企微回调：URL 验证 + 事件推送，必须公开
                        .requestMatchers("/api/wecom/callback/**").permitAll()
                        // Actuator 健康检查：供 K8s 探针使用
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 下载中心全部路径：由 DownloadAuthenticationFilter 独立处理认证
                        .requestMatchers("/download/**").permitAll()
                        // 用户管理：仅 admin 可访问
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        // 区县负责人配置：仅 admin 可访问
                        .requestMatchers("/admin/district-managers/**").hasRole("ADMIN")
                        // 登录页面及静态资源
                        .requestMatchers("/login", "/css/**", "/js/**").permitAll()
                        // 其余所有请求需要登录
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );
        return http.build();
    }
```

- [ ] **Step 2: 创建下载中心认证过滤器 `DownloadAuthenticationFilter.java`**

```java
package com.bookstore.qrcode.config;

import com.bookstore.qrcode.service.WecomOAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 下载中心 Session 认证过滤器。
 * <p>
 * 检查访问 /download/** 的请求是否持有有效的企微 OAuth Session。
 * 未认证的请求重定向到 OAuth 入口，已认证的放行。
 * OAuth 入口和回调路径跳过本过滤器。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Component
@Order(1)
public class DownloadAuthenticationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 只处理 /download/** 路径
        if (!path.startsWith("/download/")) {
            chain.doFilter(request, response);
            return;
        }

        // OAuth 入口和回调不需要认证
        if (path.startsWith("/download/oauth/")) {
            chain.doFilter(request, response);
            return;
        }

        // 检查 Session 中是否有 employeeUserid
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute(WecomOAuthService.SESSION_EMPLOYEE_USERID) == null) {
            // 未认证：重定向到 OAuth 入口
            String entryUrl = req.getContextPath() + "/download/oauth/entry";
            resp.sendRedirect(entryUrl);
            return;
        }

        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/SecurityConfig.java \
        src/main/java/com/bookstore/qrcode/config/DownloadAuthenticationFilter.java
git commit -m "feat: 配置下载中心安全规则和 OAuth Session 过滤器

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 11: 前端模板 — 下载中心页面

**Files:**
- Create: `src/main/resources/templates/download/layout.html`
- Create: `src/main/resources/templates/download/index.html`
- Create: `src/main/resources/templates/download/history.html`
- Create: `src/main/resources/templates/download/error.html`

- [ ] **Step 1: 创建下载中心独立布局 `download/layout.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN" th:fragment="layout(title, content)">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${title} ?: '火马 · 活码下载中心'">火马 · 活码下载中心</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.css">
    <script src="https://unpkg.com/htmx.org@1.9.12"></script>
    <link rel="stylesheet" th:href="@{/css/download-center.css}">
    <style>
        body { background: #f0f2f5; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        .top-bar { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
        .top-bar .brand { font-size: 1.25rem; font-weight: 700; }
    </style>
</head>
<body>
    <div class="top-bar">
        <div class="brand">📚 火马 · 活码下载中心</div>
        <div class="d-flex align-items-center gap-3">
            <a th:href="@{/download/history}" class="text-white text-decoration-none">
                <i class="bi bi-clock-history"></i> 我的下载记录
            </a>
            <span th:if="${employeeName != null}" class="text-white-50">
                <i class="bi bi-person-circle"></i> <span th:text="${employeeName}"></span>
            </span>
        </div>
    </div>

    <div class="container-fluid py-4" style="max-width: 1320px;">
        <th:block th:if="${message}">
            <div class="alert alert-success alert-dismissible fade show" role="alert">
                <i class="bi bi-check-circle"></i> <span th:text="${message}"></span>
                <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
            </div>
        </th:block>
        <th:block th:if="${error}">
            <div class="alert alert-danger alert-dismissible fade show" role="alert">
                <i class="bi bi-exclamation-circle"></i> <span th:text="${error}"></span>
                <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
            </div>
        </th:block>
        <th:block th:replace="${content}"></th:block>
    </div>

    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建下载主页 `download/index.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN"
      th:replace="~{download/layout :: layout(title='活码下载中心', content=~{::#content})}">
<body>
<div id="content">
    <!-- 搜索栏 -->
    <div class="search-section text-center mb-4">
        <form th:action="@{/download}" method="get" class="d-flex justify-content-center gap-2 flex-wrap">
            <div class="btn-group" role="group">
                <a th:href="@{/download(mode='mine',keyword=${keyword})}"
                   class="btn" th:classappend="${mode == 'mine' ? 'btn-primary' : 'btn-outline-primary'}">
                    <i class="bi bi-person-check"></i> 我的活码
                </a>
                <a th:href="@{/download(mode='all',keyword=${keyword})}"
                   class="btn" th:classappend="${mode == 'all' ? 'btn-primary' : 'btn-outline-primary'}">
                    <i class="bi bi-grid"></i> 全部活码
                </a>
            </div>
            <input type="hidden" name="mode" th:value="${mode}">
            <input type="search" name="keyword" class="form-control" style="max-width: 400px;"
                   th:value="${keyword}" placeholder="🔍 搜索学校名称、城市、区县…">
            <button type="submit" class="btn btn-primary">
                <i class="bi bi-search"></i> 搜索
            </button>
        </form>
    </div>

    <!-- 卡片网格 -->
    <div class="row g-4" th:if="${qrCodes != null && !qrCodes.content.isEmpty()}">
        <div class="col-12 col-sm-6 col-lg-4 col-xl-3" th:each="qr : ${qrCodes.content}">
            <div class="card qr-card h-100 border-0 shadow-sm">
                <div th:if="${downloadedIds.contains(qr.id)}" class="badge-downloaded">
                    <i class="bi bi-check-circle-fill"></i> 已下载
                </div>
                <div class="card-body text-center">
                    <h6 class="card-title mb-1" th:text="${qr.schoolName}">学校名</h6>
                    <p class="text-muted small mb-2">
                        <i class="bi bi-geo-alt"></i>
                        <span th:text="${qr.regionCity + ' · ' + qr.regionDistrict}">城市 · 区县</span>
                    </p>
                    <!-- 负责人 -->
                    <p class="text-muted small mb-2" th:if="${managerMap != null}">
                        <th:block th:with="key=${qr.regionCity + '|' + qr.regionDistrict},
                            dm=${managerMap.get(key)}">
                            <th:block th:if="${dm != null}">
                                👤 负责人：
                                <a th:href="'wxwork://openconversation?userid=' + ${dm.managerUserid}"
                                   class="text-decoration-none" th:text="${dm.managerName}">负责人</a>
                            </th:block>
                        </th:block>
                    </p>
                    <!-- 活码预览 -->
                    <div class="qr-preview mb-3" th:if="${qr.qrUrl != null}">
                        <img th:src="${qr.qrUrl}" class="img-fluid rounded" style="max-height: 160px;"
                             th:alt="${qr.schoolName}" loading="lazy">
                    </div>
                    <div class="qr-preview-placeholder mb-3" th:unless="${qr.qrUrl != null}">
                        <i class="bi bi-qr-code" style="font-size: 4rem; color: #ccc;"></i>
                    </div>
                    <!-- 下载按钮 -->
                    <a th:href="@{'/download/' + ${qr.id} + '/download'}"
                       class="btn btn-primary btn-download w-100"
                       th:classappend="${downloadedIds.contains(qr.id) ? 'btn-success' : 'btn-primary'}">
                        <i class="bi" th:classappend="${downloadedIds.contains(qr.id) ? 'bi-check-circle' : 'bi-download'}"></i>
                        <th:block th:if="${downloadedIds.contains(qr.id)}">再次下载</th:block>
                        <th:block th:unless="${downloadedIds.contains(qr.id)}">下载高清图</th:block>
                    </a>
                    <small class="text-muted" th:if="${downloadCounts.get(qr.id) > 0}">
                        已下载 <span th:text="${downloadCounts.get(qr.id)}"></span> 次
                    </small>
                </div>
            </div>
        </div>
    </div>

    <!-- 空状态 -->
    <div class="text-center py-5" th:unless="${qrCodes != null && !qrCodes.content.isEmpty()}">
        <i class="bi bi-inbox" style="font-size: 4rem; color: #ccc;"></i>
        <p class="text-muted mt-3" th:if="${mode == 'mine'}">你还没有绑定任何活码，切换到「全部活码」浏览</p>
        <p class="text-muted mt-3" th:unless="${mode == 'mine'}">没有找到匹配的活码</p>
    </div>

    <!-- 分页 -->
    <nav th:if="${qrCodes != null && qrCodes.totalPages > 1}" class="mt-4">
        <ul class="pagination justify-content-center">
            <li class="page-item" th:classappend="${qrCodes.first ? 'disabled' : ''}">
                <a class="page-link" th:href="@{/download(mode=${mode},keyword=${keyword},page=${qrCodes.number - 1})}">← 上一页</a>
            </li>
            <li class="page-item disabled"><span class="page-link"
                th:text="${'第 ' + (qrCodes.number + 1) + ' / ' + qrCodes.totalPages + ' 页'}"></span></li>
            <li class="page-item" th:classappend="${qrCodes.last ? 'disabled' : ''}">
                <a class="page-link" th:href="@{/download(mode=${mode},keyword=${keyword},page=${qrCodes.number + 1})}">下一页 →</a>
            </li>
        </ul>
    </nav>
</div>
</body>
</html>
```

- [ ] **Step 3: 创建下载历史 `download/history.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN"
      th:replace="~{download/layout :: layout(title='我的下载记录', content=~{::#content})}">
<body>
<div id="content">
    <div class="card border-0 shadow-sm">
        <div class="card-header bg-white border-0 d-flex justify-content-between align-items-center">
            <h5 class="mb-0"><i class="bi bi-clock-history"></i> 我的下载记录</h5>
            <div>
                <span class="badge bg-info me-2" th:text="'共下载 ' + ${distinctSchools} + ' 所学校'"></span>
                <span class="badge bg-secondary" th:text="'累计 ' + ${totalDownloads} + ' 次'"></span>
                <a th:href="@{/download}" class="btn btn-sm btn-outline-primary ms-2">
                    <i class="bi bi-arrow-left"></i> 返回下载中心
                </a>
            </div>
        </div>
        <div class="card-body p-0">
            <table class="table table-hover mb-0" th:if="${!history.isEmpty()}">
                <thead class="table-light">
                    <tr>
                        <th>学校</th>
                        <th>城市</th>
                        <th>区县</th>
                        <th>负责人</th>
                        <th>下载时间</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="row : ${history}">
                        <td th:text="${row.schoolName}"></td>
                        <td th:text="${row.regionCity}"></td>
                        <td th:text="${row.regionDistrict}"></td>
                        <td th:text="${row.managerName}"></td>
                        <td th:text="${#temporals.format(row.downloadedAt, 'yyyy-MM-dd HH:mm')}"></td>
                    </tr>
                </tbody>
            </table>
            <div class="text-center py-5" th:unless="${!history.isEmpty()}">
                <i class="bi bi-inbox" style="font-size: 3rem; color: #ccc;"></i>
                <p class="text-muted mt-2">还没有下载记录</p>
                <a th:href="@{/download}" class="btn btn-primary btn-sm">去下载</a>
            </div>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 4: 创建错误页 `download/error.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>认证失败 - 火马下载中心</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="d-flex align-items-center justify-content-center vh-100 bg-light">
    <div class="text-center">
        <i class="bi bi-exclamation-triangle" style="font-size: 4rem; color: #dc3545;"></i>
        <h4 class="mt-3">认证失败</h4>
        <p class="text-muted" th:text="${error}">请重新登录</p>
        <a th:href="@{/download/oauth/entry}" class="btn btn-primary">重新登录</a>
    </div>
</body>
</html>
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/download/
git commit -m "feat: 新增下载中心前端模板（主页、历史、错误页、布局）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 12: 前端模板 — 管理后台页面

**Files:**
- Create: `src/main/resources/templates/admin/download-stats.html`
- Create: `src/main/resources/templates/admin/district-managers.html`

- [ ] **Step 1: 创建下载统计页 `admin/download-stats.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN"
      th:replace="~{layout :: layout(title='下载统计', content=~{::#content})}">
<body>
<div id="content">
    <h4 class="mb-3"><i class="bi bi-download"></i> 活码下载统计</h4>

    <!-- 汇总卡片 -->
    <div class="row g-3 mb-4">
        <div class="col-6 col-md-3">
            <div class="card border-0 shadow-sm text-center">
                <div class="card-body">
                    <h2 class="text-primary mb-0" th:text="${stats.totalQrCodes}">0</h2>
                    <small class="text-muted">活码总数</small>
                </div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card border-0 shadow-sm text-center">
                <div class="card-body">
                    <h2 class="text-success mb-0" th:text="${stats.totalDownloaded}">0</h2>
                    <small class="text-muted">已下载员工</small>
                </div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card border-0 shadow-sm text-center">
                <div class="card-body">
                    <h2 class="text-warning mb-0" th:text="${stats.totalNotDownloaded}">0</h2>
                    <small class="text-muted">未下载员工</small>
                </div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card border-0 shadow-sm text-center">
                <div class="card-body">
                    <h2 class="text-info mb-0" th:text="${stats.totalDownloads}">0</h2>
                    <small class="text-muted">总下载次数</small>
                </div>
            </div>
        </div>
    </div>

    <!-- 筛选栏 -->
    <div class="card border-0 shadow-sm mb-3">
        <div class="card-body">
            <form th:action="@{/admin/download-stats}" method="get" class="row g-2 align-items-end">
                <div class="col-md-2">
                    <label class="form-label small">城市</label>
                    <select name="city" class="form-select form-select-sm">
                        <option value="">全部</option>
                        <option th:each="c : ${cities}" th:value="${c}" th:text="${c}"
                                th:selected="${city == c}"></option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">区县</label>
                    <select name="district" class="form-select form-select-sm">
                        <option value="">全部</option>
                        <option th:each="d : ${districts}" th:value="${d}" th:text="${d}"
                                th:selected="${district == d}"></option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">下载状态</label>
                    <select name="downloadStatus" class="form-select form-select-sm">
                        <option value="">全部</option>
                        <option value="downloaded" th:selected="${downloadStatus == 'downloaded'}">已下载</option>
                        <option value="not_downloaded" th:selected="${downloadStatus == 'not_downloaded'}">未下载</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <button type="submit" class="btn btn-primary btn-sm w-100">
                        <i class="bi bi-search"></i> 筛选
                    </button>
                </div>
            </form>
        </div>
    </div>

    <!-- 数据表格 -->
    <div class="card border-0 shadow-sm">
        <div class="table-responsive">
            <table class="table table-hover mb-0">
                <thead class="table-light">
                    <tr>
                        <th>学校</th>
                        <th>城市·区县</th>
                        <th>负责人</th>
                        <th>员工</th>
                        <th>下载状态</th>
                        <th>下载次数</th>
                        <th>最近下载</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="row : ${stats.rows}">
                        <td th:text="${row.schoolName}"></td>
                        <td th:text="${row.regionCity + ' · ' + row.regionDistrict}"></td>
                        <td th:text="${row.managerName}"></td>
                        <td><code th:text="${row.agentUserid}"></code></td>
                        <td>
                            <span class="badge bg-success" th:if="${row.downloaded}">✓ 已下载</span>
                            <span class="badge bg-secondary" th:unless="${row.downloaded}">✗ 未下载</span>
                        </td>
                        <td th:text="${row.downloadCount}"></td>
                        <td>
                            <th:block th:if="${row.lastDownloadAt != null}">
                                <span th:text="${#temporals.format(row.lastDownloadAt, 'yyyy-MM-dd HH:mm')}"></span>
                            </th:block>
                            <span class="text-muted" th:unless="${row.lastDownloadAt != null}">—</span>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="text-center py-4" th:if="${stats.rows == null || stats.rows.isEmpty()}">
            <p class="text-muted">暂无下载数据</p>
        </div>
    </div>

    <!-- 分页 -->
    <nav th:if="${stats.totalPages > 1}" class="mt-3">
        <ul class="pagination justify-content-center">
            <li class="page-item" th:classappend="${stats.currentPage == 0 ? 'disabled' : ''}">
                <a class="page-link" th:href="@{/admin/download-stats(city=${city},district=${district},downloadStatus=${downloadStatus},page=${stats.currentPage - 1})}">←</a>
            </li>
            <li class="page-item disabled"><span class="page-link"
                th:text="${'第 ' + (stats.currentPage + 1) + ' / ' + stats.totalPages + ' 页 (' + stats.totalRows + ' 条)'}"></span></li>
            <li class="page-item" th:classappend="${stats.currentPage >= stats.totalPages - 1 ? 'disabled' : ''}">
                <a class="page-link" th:href="@{/admin/download-stats(city=${city},district=${district},downloadStatus=${downloadStatus},page=${stats.currentPage + 1})}">→</a>
            </li>
        </ul>
    </nav>
</div>
</body>
</html>
```

- [ ] **Step 2: 创建区县负责人配置页 `admin/district-managers.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN"
      th:replace="~{layout :: layout(title='区县负责人配置', content=~{::#content})}">
<body>
<div id="content">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h4 class="mb-0"><i class="bi bi-person-gear"></i> 区县负责人配置</h4>
        <button class="btn btn-primary btn-sm" data-bs-toggle="modal" data-bs-target="#addModal">
            <i class="bi bi-plus-lg"></i> 新增配置
        </button>
    </div>

    <div class="card border-0 shadow-sm">
        <div class="table-responsive">
            <table class="table table-hover mb-0">
                <thead class="table-light">
                    <tr>
                        <th>ID</th>
                        <th>城市</th>
                        <th>区县</th>
                        <th>负责人</th>
                        <th>负责人 UserID</th>
                        <th>操作</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="m : ${managers}">
                        <td th:text="${m.id}"></td>
                        <td th:text="${m.regionCity}"></td>
                        <td th:text="${m.regionDistrict}"></td>
                        <td th:text="${m.managerName}"></td>
                        <td><code th:text="${m.managerUserid}"></code></td>
                        <td>
                            <button class="btn btn-sm btn-outline-secondary me-1"
                                    data-bs-toggle="modal" data-bs-target="#editModal"
                                    th:attr="data-id=${m.id},data-userid=${m.managerUserid}">
                                <i class="bi bi-pencil"></i>
                            </button>
                            <form th:action="@{'/admin/district-managers/' + ${m.id} + '/delete'}"
                                  method="post" class="d-inline"
                                  onsubmit="return confirm('确认删除该区县负责人配置？')">
                                <button type="submit" class="btn btn-sm btn-outline-danger">
                                    <i class="bi bi-trash"></i>
                                </button>
                            </form>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="text-center py-4" th:if="${managers.isEmpty()}">
            <p class="text-muted">暂无区县负责人配置</p>
        </div>
    </div>

    <!-- 新增模态框 -->
    <div class="modal fade" id="addModal" tabindex="-1">
        <div class="modal-dialog">
            <form th:action="@{/admin/district-managers/create}" method="post" class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">新增区县负责人</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body">
                    <div class="mb-3">
                        <label class="form-label">城市</label>
                        <select name="regionCity" class="form-select" required>
                            <option value="">请选择城市</option>
                            <option th:each="c : ${cities}" th:value="${c}" th:text="${c}"></option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">区县</label>
                        <select name="regionDistrict" class="form-select" required>
                            <option value="">请选择区县</option>
                            <option th:each="d : ${districts}" th:value="${d}" th:text="${d}"></option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">负责人</label>
                        <select name="managerUserid" class="form-select" required>
                            <option value="">请选择负责人</option>
                            <option th:each="e : ${employeeList}" th:value="${e.userid}"
                                    th:text="${e.name + ' (' + e.userid + ')'}"></option>
                        </select>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">取消</button>
                    <button type="submit" class="btn btn-primary">确认添加</button>
                </div>
            </form>
        </div>
    </div>

    <!-- 编辑模态框（通过 JS 填充） -->
    <div class="modal fade" id="editModal" tabindex="-1">
        <div class="modal-dialog">
            <form th:action="@{/admin/district-managers/{id}/update(id='')}" method="post"
                  class="modal-content" id="editForm">
                <div class="modal-header">
                    <h5 class="modal-title">编辑区县负责人</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body">
                    <div class="mb-3">
                        <label class="form-label">负责人</label>
                        <select name="managerUserid" class="form-select" required id="editManagerSelect">
                            <option value="">请选择负责人</option>
                            <option th:each="e : ${employeeList}" th:value="${e.userid}"
                                    th:text="${e.name + ' (' + e.userid + ')'}"></option>
                        </select>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">取消</button>
                    <button type="submit" class="btn btn-primary">确认更新</button>
                </div>
            </form>
        </div>
    </div>
</div>

<script>
// 编辑模态框：从按钮 data 属性填充 ID 和选中值
document.getElementById('editModal').addEventListener('show.bs.modal', function(e) {
    var btn = e.relatedTarget;
    var id = btn.getAttribute('data-id');
    var userid = btn.getAttribute('data-userid');
    this.querySelector('#editForm').action = '/admin/district-managers/' + id + '/update';
    this.querySelector('#editManagerSelect').value = userid;
});
</script>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/admin/download-stats.html \
        src/main/resources/templates/admin/district-managers.html
git commit -m "feat: 新增管理后台下载统计和区县负责人配置页面

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 13: 下载中心 CSS + 导航入口

**Files:**
- Create: `src/main/resources/static/css/download-center.css`
- Modify: `src/main/resources/templates/layout.html`

- [ ] **Step 1: 创建下载中心专用 CSS**

```css
/* 活码卡片动效 */
.qr-card {
    border-radius: 12px;
    transition: transform 0.2s ease, box-shadow 0.2s ease;
    position: relative;
    overflow: hidden;
}

.qr-card:hover {
    transform: translateY(-4px);
    box-shadow: 0 8px 25px rgba(0, 0, 0, 0.12) !important;
}

/* 已下载角标 */
.badge-downloaded {
    position: absolute;
    top: 8px;
    left: 8px;
    background: #198754;
    color: white;
    font-size: 0.75rem;
    padding: 4px 10px;
    border-radius: 20px;
    z-index: 2;
}

/* 下载按钮 */
.btn-download {
    border-radius: 8px;
    font-weight: 500;
    transition: all 0.2s ease;
}

.btn-download:hover {
    transform: scale(1.02);
}

/* 活码预览图 */
.qr-preview img {
    border: 1px solid #eee;
    padding: 4px;
    background: white;
}

/* 搜索区域 */
.search-section {
    padding: 24px 0;
}

/* 响应式调整 */
@media (max-width: 576px) {
    .top-bar {
        flex-direction: column;
        gap: 8px;
        text-align: center;
    }
    .qr-card:hover {
        transform: none;
    }
}
```

- [ ] **Step 2: 在 layout.html 导航栏新增下载统计入口**

在 `layout.html` 的导航菜单中，系统设置 `<a>` 标签之前添加：

```html
<a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/download-stats"><i class="bi bi-download"></i> 下载统计</a>
```

和区县负责人入口（在系统设置下拉或同级）：

```html
<a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/district-managers"><i class="bi bi-person-gear"></i> 区县负责人</a>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/css/download-center.css \
        src/main/resources/templates/layout.html
git commit -m "feat: 下载中心 CSS 样式 和 管理后台导航入口

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 14: GlobalControllerAdvice — 兼容员工 Session 的 currentUser 展示

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/GlobalControllerAdvice.java`

- [ ] **Step 1: 修改 `currentUser()` 方法，当 Spring Security 无认证时回退读取 OAuth Session**

```java
    /**
     * 向所有视图暴露当前登录用户名。
     * <p>优先使用 Spring Security 认证（管理后台），
     * 若无认证则回退读取企微 OAuth Session（下载中心）。</p>
     *
     * @return 当前用户名，未登录时返回 null
     */
    @ModelAttribute("currentUser")
    public String currentUser(HttpSession session) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        // 下载中心 OAuth Session 回退
        if (session != null) {
            Object name = session.getAttribute(
                com.bookstore.qrcode.service.WecomOAuthService.SESSION_EMPLOYEE_NAME);
            if (name != null) return name.toString();
        }
        return null;
    }
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/GlobalControllerAdvice.java
git commit -m "feat: GlobalControllerAdvice 兼容下载中心 OAuth Session 用户名展示

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 15: 集成测试 — 端到端验证

- [ ] **Step 1: 启动应用并验证**

```bash
./mvnw spring-boot:run
```

- [ ] **Step 2: 验证管理后台功能**

```bash
# 1. 登录管理后台 admin
# 2. 访问 /admin/district-managers，新增一条区县负责人配置
# 3. 访问 /admin/download-stats，确认统计页面正常展示
```

- [ ] **Step 3: 验证下载中心功能**

```bash
# 1. 模拟企微 OAuth（直接在浏览器访问 /download/oauth/entry）
#    注意：需要在企微环境中或手动设置 Session 绕过 OAuth
# 2. 访问 /download 确认卡片网格正常展示
# 3. 点击下载按钮确认文件下载成功
# 4. 访问 /download/history 确认下载记录展示
```

- [ ] **Step 4: 验证数据库**

```bash
mysql -u root -p bookstore_qrcode -e "SELECT * FROM qr_download_log ORDER BY downloaded_at DESC LIMIT 5;"
mysql -u root -p bookstore_qrcode -e "SELECT * FROM district_manager;"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: 下载中心功能端到端验证

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 实施顺序建议

依赖关系图：

```
Task 1 (schema.sql)
  └─► Task 2 (entities)
        └─► Task 3 (repositories)
              ├─► Task 6 (DownloadLogService)
              │     └─► Task 8 (DownloadCenterController)
              └─► Task 7 (DistrictManagerService)
                    └─► Task 9 (DistrictManagerController)

Task 4 (WecomApiClient.getUserInfo)
  └─► Task 5 (WecomOAuthService)
        └─► Task 8 (DownloadCenterController)

Task 10 (Security) ──► 独立，建议在 Task 8 之前完成

Task 11/12 (Templates) ──► 依赖 Task 8/9 定义的 Model 属性

Task 13 (CSS + Nav) ──► 最后

Task 14 (GlobalControllerAdvice) ──► 独立，可随时完成

Task 15 (集成测试) ──► 全部完成后
```

建议的执行顺序：**1 → 2 → 3 → 4 → 5 → 10 → 6 → 7 → 8 → 9 → 11 → 12 → 13 → 14 → 15**
