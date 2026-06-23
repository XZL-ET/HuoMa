# 活码规模化管理改进 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将活码管理从 500 规模升级到 5000 规模，覆盖性能优化、批量操作、批量导入增强、全局池分页、列表导出和筛选增强。

**Architecture:** 后端 Spring Boot + JPA + Thymeleaf，纯优化不新增表。核心思路：N+1 查询改为聚合 SQL、scope 筛选下推 JPQL 子查询、树接口 DTO 投影、批量 UPDATE 走 `@Modifying`、Excel 列扩展 + POI 流式导出。

**Tech Stack:** Spring Boot 3.x, Spring Data JPA, Apache POI (XSSFWorkbook/SXSSFWorkbook), Thymeleaf, Redis

## Global Constraints

- 不新增数据库表，不修改表结构
- 遵循项目现有代码风格（JPA `@Query` + `IS NULL OR` 模式）
- 涉及 Repository 的方法签名遵循 Spring Data 命名规范

---

### Task 1: CustomerRepository 聚合查询

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java`

**Interfaces:**
- Consumes: (none — first task)
- Produces: `List<Object[]> countTotalAndTodayByQrIds(@Param("qrIds") List<Long> qrIds, @Param("todayStart") LocalDateTime todayStart)` — returns `[sourceQrId, total, today]` per row

- [ ] **Step 1: Add aggregation query method to CustomerRepository**

```java
/**
 * 批量统计活码的累计客户数和今日新增客户数。
 * <p>
 * 一次查询返回所有指定活码的统计结果，替代列表页的 N+1 循环查询。
 * </p>
 *
 * @param qrIds      活码 ID 列表
 * @param todayStart 今日起始时间（用于今日新增统计）
 * @return 每行格式：[sourceQrId, totalCount, todayCount]
 */
@Query("SELECT c.sourceQrId, COUNT(c), " +
       "SUM(CASE WHEN c.addTime >= :todayStart THEN 1 ELSE 0 END) " +
       "FROM Customer c WHERE c.sourceQrId IN :qrIds " +
       "GROUP BY c.sourceQrId")
List<Object[]> countTotalAndTodayByQrIds(@Param("qrIds") List<Long> qrIds,
                                          @Param("todayStart") LocalDateTime todayStart);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java
git commit -m "feat: add batch aggregation query for customer counts by QR code IDs

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: QrCodeRepository Scope 子查询 + 排序 + 投影

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/dto/QrCodeTreeDto.java`

**Interfaces:**
- Consumes: (none)
- Produces:
  - `Page<QrCode> searchAlliance(keyword, city, district, status, groupId, pageable)` — scope=alliance 专用
  - `Page<QrCode> searchSchool(keyword, city, district, status, groupId, pageable)` — scope=school 专用
  - `List<QrCode> findAllForExport(keyword, city, district, status, groupId, scope)` — 导出用全量查询
  - `List<QrCodeTreeDto> findAllTreeProjection()` — 树接口 DTO 投影
  - `QrCodeTreeDto(Long id, String schoolName, String regionCity, String regionDistrict, Long groupId)` — 构造函数投影

- [ ] **Step 1: Create QrCodeTreeDto**

```java
package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 活码树节点 DTO — 仅包含树接口渲染所需的 5 个字段，
 * 替代 {@code findAll()} 加载全部 25 列的浪费。
 */
@Data
@AllArgsConstructor
public class QrCodeTreeDto {
    private Long id;
    private String schoolName;
    private String regionCity;
    private String regionDistrict;
    private Long groupId;
}
```

- [ ] **Step 2: Add searchAlliance and searchSchool methods to QrCodeRepository**

Replace the existing `search()` method with three methods (keep `search` for backward compat, add two new):

```java
// Remove or deprecate the old single search() method — keep it but add:

/**
 * 分页搜索联盟活码 — id 在 QrCodeGroup 表中有记录。
 */
@Query("SELECT q FROM QrCode q WHERE "
     + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
     + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
     + "AND (:city IS NULL OR q.regionCity = :city) "
     + "AND (:district IS NULL OR q.regionDistrict = :district) "
     + "AND (:status IS NULL OR q.status = :status) "
     + "AND (:groupId IS NULL OR q.groupId = :groupId) "
     + "AND q.id IN (SELECT g.qrCodeId FROM QrCodeGroup g) "
     + "ORDER BY q.createdAt DESC")
Page<QrCode> searchAlliance(@Param("keyword") String keyword,
                             @Param("city") String city,
                             @Param("district") String district,
                             @Param("status") QrCode.QrCodeStatus status,
                             @Param("groupId") Long groupId,
                             Pageable pageable);

/**
 * 分页搜索非联盟活码 — id 不在 QrCodeGroup 表中有记录。
 */
@Query("SELECT q FROM QrCode q WHERE "
     + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
     + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
     + "AND (:city IS NULL OR q.regionCity = :city) "
     + "AND (:district IS NULL OR q.regionDistrict = :district) "
     + "AND (:status IS NULL OR q.status = :status) "
     + "AND (:groupId IS NULL OR q.groupId = :groupId) "
     + "AND q.id NOT IN (SELECT g.qrCodeId FROM QrCodeGroup g) "
     + "ORDER BY q.createdAt DESC")
Page<QrCode> searchSchool(@Param("keyword") String keyword,
                           @Param("city") String city,
                           @Param("district") String district,
                           @Param("status") QrCode.QrCodeStatus status,
                           @Param("groupId") Long groupId,
                           Pageable pageable);
```

Also update the existing `search()` method to add `groupId` filter and `ORDER BY q.createdAt DESC`:

```java
@Query("SELECT q FROM QrCode q WHERE "
     + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
     + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
     + "AND (:city IS NULL OR q.regionCity = :city) "
     + "AND (:district IS NULL OR q.regionDistrict = :district) "
     + "AND (:status IS NULL OR q.status = :status) "
     + "AND (:groupId IS NULL OR q.groupId = :groupId) "
     + "ORDER BY q.createdAt DESC")
Page<QrCode> search(@Param("keyword") String keyword,
                    @Param("city") String city,
                    @Param("district") String district,
                    @Param("status") QrCode.QrCodeStatus status,
                    @Param("groupId") Long groupId,
                    Pageable pageable);
```

- [ ] **Step 3: Add export query method to QrCodeRepository**

```java
/**
 * 不分页查询活码列表（用于导出 Excel）。
 * scope=alliance 时仅含联盟活码，scope=school 时仅含非联盟活码。
 */
@Query("SELECT q FROM QrCode q WHERE "
     + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
     + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
     + "AND (:city IS NULL OR q.regionCity = :city) "
     + "AND (:district IS NULL OR q.regionDistrict = :district) "
     + "AND (:status IS NULL OR q.status = :status) "
     + "AND (:groupId IS NULL OR q.groupId = :groupId) "
     + "AND (:allianceOnly IS NULL OR "
     + "  (:allianceOnly = true AND q.id IN (SELECT g.qrCodeId FROM QrCodeGroup g)) OR "
     + "  (:allianceOnly = false AND q.id NOT IN (SELECT g.qrCodeId FROM QrCodeGroup g))) "
     + "ORDER BY q.createdAt DESC")
List<QrCode> findAllForExport(@Param("keyword") String keyword,
                               @Param("city") String city,
                               @Param("district") String district,
                               @Param("status") QrCode.QrCodeStatus status,
                               @Param("groupId") Long groupId,
                               @Param("allianceOnly") Boolean allianceOnly);
```

- [ ] **Step 4: Add tree DTO projection query to QrCodeRepository**

```java
/**
 * 活码树节点投影查询 — 仅加载树渲染所需的 5 个字段，
 * 替代 {@code findAll()} 加载全部 25 列的浪费。
 */
@Query("SELECT new com.bookstore.qrcode.dto.QrCodeTreeDto(" +
       "q.id, q.schoolName, q.regionCity, q.regionDistrict, q.groupId) " +
       "FROM QrCode q")
List<QrCodeTreeDto> findAllTreeProjection();
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java \
        src/main/java/com/bookstore/qrcode/dto/QrCodeTreeDto.java
git commit -m "feat: add scope subquery, groupId filter, export query, and tree DTO projection

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: GlobalAgentPoolRepository 状态优先分页查询

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java`

**Interfaces:**
- Consumes: (none)
- Produces: `Page<GlobalAgentPool> findAllWithStatusPriority(Pageable pageable)` — 状态优先排序（standby → full → blocked），同状态按 sortOrder 升序

- [ ] **Step 1: Add status-priority pagination query**

```java
/**
 * 全量分页查询，按状态优先级排序（standby → full → blocked），
 * 同状态按 sortOrder 升序，替代原内存排序。
 */
@Query("SELECT p FROM GlobalAgentPool p ORDER BY "
     + "CASE p.status WHEN 'standby' THEN 0 WHEN 'full' THEN 1 ELSE 2 END, "
     + "p.sortOrder ASC")
Page<GlobalAgentPool> findAllWithStatusPriority(Pageable pageable);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java
git commit -m "feat: add status-priority pagination query for global pool

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: QrCodeRepository 批量更新方法

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java`

**Interfaces:**
- Consumes: (none)
- Produces:
  - `int batchUpdateWelcomeText(@Param("welcomeText") String welcomeText, @Param("ids") List<Long> ids)`
  - `int batchUpdateFormTemplateId(@Param("formTemplateId") Long formTemplateId, @Param("ids") List<Long> ids)`
  - `int batchUpdateRotateMode(@Param("mode") QrCode.RotateMode mode, @Param("ids") List<Long> ids)`
  - `int batchUpdateGroupId(@Param("groupId") Long groupId, @Param("ids") List<Long> ids)`
  - `int batchUpdateThresholds(@Param("warnRatio") int warnRatio, @Param("urgentRatio") int urgentRatio, @Param("ids") List<Long> ids)`
  - `int batchUpdateStatus(@Param("status") QrCode.QrCodeStatus status, @Param("ids") List<Long> ids)`

- [ ] **Step 1: Add all batch update methods**

```java
// ── 批量更新方法 ──

/** 批量更新欢迎语 */
@Modifying
@Query("UPDATE QrCode q SET q.welcomeText = :welcomeText WHERE q.id IN :ids")
int batchUpdateWelcomeText(@Param("welcomeText") String welcomeText,
                            @Param("ids") List<Long> ids);

/** 批量更新表单模板（null 表示清空） */
@Modifying
@Query("UPDATE QrCode q SET q.formTemplateId = :formTemplateId WHERE q.id IN :ids")
int batchUpdateFormTemplateId(@Param("formTemplateId") Long formTemplateId,
                               @Param("ids") List<Long> ids);

/** 批量切换轮换模式 */
@Modifying
@Query("UPDATE QrCode q SET q.rotateMode = :mode WHERE q.id IN :ids")
int batchUpdateRotateMode(@Param("mode") QrCode.RotateMode mode,
                           @Param("ids") List<Long> ids);

/** 批量改分组（null 表示取消分组） */
@Modifying
@Query("UPDATE QrCode q SET q.groupId = :groupId WHERE q.id IN :ids")
int batchUpdateGroupId(@Param("groupId") Long groupId,
                        @Param("ids") List<Long> ids);

/** 批量更新告警/紧急阈值 */
@Modifying
@Query("UPDATE QrCode q SET q.warnRatio = :warnRatio, q.urgentRatio = :urgentRatio "
     + "WHERE q.id IN :ids")
int batchUpdateThresholds(@Param("warnRatio") int warnRatio,
                           @Param("urgentRatio") int urgentRatio,
                           @Param("ids") List<Long> ids);

/** 批量更新状态（暂停/启用） */
@Modifying
@Query("UPDATE QrCode q SET q.status = :status WHERE q.id IN :ids")
int batchUpdateStatus(@Param("status") QrCode.QrCodeStatus status,
                       @Param("ids") List<Long> ids);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java
git commit -m "feat: add batch update methods for QR code management

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: QrCodeService 重构 getBackups + parseExcel + executeBatchImport

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`

**Interfaces:**
- Consumes: `GlobalAgentPoolRepository.findAllWithStatusPriority(Pageable)` (Task 3), `countByStatus()` (existing)
- Produces: Updated `getBackups()`, `parseExcel()`, `executeBatchImport()`

- [ ] **Step 1: Refactor getBackups() to use DB pagination**

Replace lines 153-165:

```java
/**
 * 获取全局员工池分页数据 — 状态优先排序，DB 侧分页。
 *
 * @param qrCodeId 活码主键 ID（保留参数兼容性）
 * @param page     页码（从 0 开始）
 * @param size     每页条数
 * @return 全局池分页数据
 */
public Page<GlobalAgentPool> getBackups(Long qrCodeId, int page, int size) {
    return poolRepo.findAllWithStatusPriority(PageRequest.of(page, size));
}

/**
 * 获取全局池各状态统计 — 改用三条 COUNT 查询替代全量加载。
 *
 * @return Map 包含 standby/full/blocked 计数
 */
public Map<String, Long> getPoolStats() {
    Map<String, Long> stats = new LinkedHashMap<>();
    stats.put("standby", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby));
    stats.put("full", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.full));
    stats.put("blocked", poolRepo.countByStatus(GlobalAgentPool.PoolStatus.blocked));
    return stats;
}

/**
 * 获取全局池全部 userid 列表（轻量投影，只查 userid）。
 */
public List<String> getAllPoolUserids() {
    return poolRepo.findAllAgentUserids();
}
```

Remove the old `getBackups(Long qrCodeId)` method completely.

- [ ] **Step 2: Expand parseExcel() to read 11 columns**

Replace the existing `parseExcel()` method (lines 1143-1168):

```java
private List<Map<String, String>> parseExcel(MultipartFile file) {
    List<Map<String, String>> items = new ArrayList<>();
    try (InputStream is = file.getInputStream();
         Workbook wb = new XSSFWorkbook(is)) {
        Sheet sheet = wb.getSheetAt(0);
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("row", String.valueOf(i + 1));
            // 列索引: 0学校名称 1学校ID 2市 3区 4服务老师 5学校人数
            //          6初始上码员工数 7接待员 8服务老师日上限 9欢迎语 10备注
            item.put("schoolName", getCellString(row, 0));
            item.put("schoolId", getCellString(row, 1));
            item.put("regionCity", getCellString(row, 2));
            item.put("regionDistrict", getCellString(row, 3));
            item.put("serviceTeacherUserid", getCellString(row, 4));
            item.put("studentCount", getCellString(row, 5));
            item.put("initialAgentCount", getCellString(row, 6));
            item.put("receptionistUserid", getCellString(row, 7));
            item.put("serviceDailyMax", getCellString(row, 8));
            item.put("welcomeText", getCellString(row, 9));
            item.put("remark", getCellString(row, 10));
            // 学校名称和学校ID必填
            if (!item.get("schoolName").isEmpty() && !item.get("schoolId").isEmpty()) {
                items.add(item);
            }
        }
    } catch (Exception e) {
        throw new RuntimeException("解析 Excel 失败: " + e.getMessage(), e);
    }
    return items;
}
```

- [ ] **Step 3: Expand executeBatchImport() field mapping**

Replace the field mapping block inside `executeBatchImport()` (around lines 341-346):

```java
// 将 Excel 行数据映射为创建请求 DTO
QrCodeCreateRequest req = new QrCodeCreateRequest();
req.setSchoolName(item.get("schoolName"));
req.setSchoolId(item.get("schoolId"));
req.setRegionCity(item.get("regionCity"));
req.setRegionDistrict(item.get("regionDistrict"));
req.setServiceTeacherUserid(item.get("serviceTeacherUserid"));
req.setRemark(item.getOrDefault("remark", ""));
// 学校人数
String studentCountStr = item.get("studentCount");
if (studentCountStr != null && !studentCountStr.isEmpty()) {
    try { req.setStudentCount(Integer.valueOf(studentCountStr)); }
    catch (NumberFormatException ignored) {}
}
// 初始上码员工数（默认 1）
String initialAgentStr = item.get("initialAgentCount");
if (initialAgentStr != null && !initialAgentStr.isEmpty()) {
    try { req.setInitialAgentCount(Integer.valueOf(initialAgentStr)); }
    catch (NumberFormatException ignored) {}
}
// 接待员（逗号分隔）
req.setReceptionistUserid(item.get("receptionistUserid"));
// 服务老师日上限（默认 30）
String dailyMaxStr = item.get("serviceDailyMax");
if (dailyMaxStr != null && !dailyMaxStr.isEmpty()) {
    try { req.setServiceDailyMax(Integer.valueOf(dailyMaxStr)); }
    catch (NumberFormatException ignored) {}
}
// 欢迎语
req.setWelcomeText(item.get("welcomeText"));
// 直接复用手动创建流程（含企微 API 调用）
create(req);
success++;
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "feat: refactor backups to DB pagination, expand Excel import to 11 columns

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: QrCodeService 新增批量操作方法

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`

**Interfaces:**
- Consumes: `QrCodeRepository` batch update methods (Task 4)
- Produces: Six batch service methods with `@Transactional`

- [ ] **Step 1: Add batch service methods**

```java
// ==================== 批量操作 ====================

@Transactional
public int batchUpdateWelcomeText(List<Long> ids, String welcomeText) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateWelcomeText(welcomeText, ids);
}

@Transactional
public int batchUpdateFormTemplateId(List<Long> ids, Long formTemplateId) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateFormTemplateId(formTemplateId, ids);
}

@Transactional
public int batchUpdateRotateMode(List<Long> ids, QrCode.RotateMode mode) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateRotateMode(mode, ids);
}

@Transactional
public int batchUpdateGroupId(List<Long> ids, Long groupId) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateGroupId(groupId, ids);
}

@Transactional
public int batchUpdateThresholds(List<Long> ids, int warnRatio, int urgentRatio) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateThresholds(warnRatio, urgentRatio, ids);
}

@Transactional
public int batchUpdateStatus(List<Long> ids, QrCode.QrCodeStatus status) {
    if (ids == null || ids.isEmpty()) return 0;
    return qrCodeRepo.batchUpdateStatus(status, ids);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "feat: add batch operation service methods

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: QrCodeController 重构 list() + tree() + detail()

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`

**Interfaces:**
- Consumes: `CustomerRepository.countTotalAndTodayByQrIds()` (Task 1), `QrCodeRepository.searchAlliance/searchSchool/search()` (Task 2), `QrCodeTreeDto` (Task 2), `QrCodeService.getBackups/getPoolStats/getAllPoolUserids()` (Task 5)
- Produces: Updated `list()`, `tree()`, `detail()` methods

- [ ] **Step 1: Refactor list() — scope filter + aggregation + groupId filter + sort**

Replace the `list()` method (lines 134-227) with:

```java
@GetMapping
public String list(@RequestParam(required = false) String keyword,
                   @RequestParam(required = false) String city,
                   @RequestParam(required = false) String district,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String scope,
                   @RequestParam(required = false) Long groupId,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "20") int size,
                   Model model) {

    // ---- 1. Parse status enum ----
    QrCode.QrCodeStatus qrStatus = null;
    if (status != null && !status.isEmpty()) {
        try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
        catch (IllegalArgumentException ignored) {}
    }

    // ---- 2. Search with scope pushed to DB ----
    Page<QrCode> qrCodes;
    Pageable pageable = PageRequest.of(page, size);
    if ("alliance".equals(scope)) {
        qrCodes = qrCodeRepo.searchAlliance(keyword, city, district, qrStatus, groupId, pageable);
    } else if ("school".equals(scope)) {
        qrCodes = qrCodeRepo.searchSchool(keyword, city, district, qrStatus, groupId, pageable);
    } else {
        qrCodes = qrCodeRepo.search(keyword, city, district, qrStatus, groupId, pageable);
    }

    // ---- 3. City/district/group dropdown options ----
    List<String> cities = qrCodeRepo.findDistinctRegionCity();
    List<String> districts = qrCodeRepo.findDistinctRegionDistrict();
    List<QrCodeGroup> groups = groupRepo.findAllByOrderByName();

    // ---- 4. Batch aggregation: customer counts + agent counts ----
    List<Long> pageIds = qrCodes.getContent().stream()
        .map(QrCode::getId).collect(Collectors.toList());

    LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
    Map<Long, Long> todayCountMap = new HashMap<>();
    Map<Long, Long> totalCountMap = new HashMap<>();

    if (!pageIds.isEmpty()) {
        List<Object[]> custStats = customerRepo.countTotalAndTodayByQrIds(pageIds, todayStart);
        for (Object[] row : custStats) {
            Long qrId = (Long) row[0];
            Long total = (Long) row[1];
            Long today = (Long) row[2];
            totalCountMap.put(qrId, total);
            todayCountMap.put(qrId, today);
        }
    }

    Map<Long, String> agentCountMap = new HashMap<>();
    for (QrCode qr : qrCodes.getContent()) {
        long activeCount = qrAgentRepo.findByQrCodeIdAndStatus(
            qr.getId(), QrAgent.AgentStatus.active).size();
        long poolStandby = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
        agentCountMap.put(qr.getId(), activeCount + "/" + poolStandby);
    }

    // ---- 5. Precompute alliance IDs (for badge display) ----
    Set<Long> allianceQrCodeIds = new HashSet<>();
    for (QrCodeGroup g : groups) {
        if (g.getQrCodeId() != null) allianceQrCodeIds.add(g.getQrCodeId());
    }

    // ---- 6. Fill model ----
    model.addAttribute("qrCodes", qrCodes);
    model.addAttribute("keyword", keyword);
    model.addAttribute("city", city);
    model.addAttribute("district", district);
    model.addAttribute("status", status);
    model.addAttribute("scope", scope);
    model.addAttribute("groupId", groupId);
    model.addAttribute("cities", cities);
    model.addAttribute("districts", districts);
    model.addAttribute("agentCountMap", agentCountMap);
    model.addAttribute("todayCountMap", todayCountMap);
    model.addAttribute("totalCountMap", totalCountMap);
    model.addAttribute("groups", groups);
    model.addAttribute("formTemplates", formTemplateRepo.findAllByOrderByName());
    model.addAttribute("allianceQrCodeIds", allianceQrCodeIds);

    return "qrcode/list";
}
```

- [ ] **Step 2: Refactor tree() to use DTO projection**

Replace the `tree()` method (lines 264-358). Change `List<QrCode>` to `List<QrCodeTreeDto>` throughout:

```java
@GetMapping("/tree")
@ResponseBody
public List<Map<String, Object>> tree() {
    List<QrCodeTreeDto> qrs = qrCodeRepo.findAllTreeProjection();
    List<QrCodeGroup> groups = groupRepo.findAllByOrderByName();

    Map<Long, QrCodeGroup> groupMap = new LinkedHashMap<>();
    for (QrCodeGroup g : groups) {
        groupMap.put(g.getId(), g);
    }
    Set<Long> coveredGroupIds = new LinkedHashSet<>();

    // city → district → bucketKey → [dto...]
    Map<String, Map<String, Map<String, List<QrCodeTreeDto>>>> mid = new LinkedHashMap<>();

    for (QrCodeTreeDto qr : qrs) {
        String city = qr.getRegionCity() != null ? qr.getRegionCity() : "未分类";
        String district = qr.getRegionDistrict() != null ? qr.getRegionDistrict() : "未分类";
        mid.putIfAbsent(city, new LinkedHashMap<>());
        mid.get(city).putIfAbsent(district, new LinkedHashMap<>());

        String bucketKey;
        if (qr.getGroupId() != null && groupMap.containsKey(qr.getGroupId())) {
            QrCodeGroup g = groupMap.get(qr.getGroupId());
            bucketKey = "g:" + g.getId() + "|" + g.getName();
            coveredGroupIds.add(g.getId());
        } else if (qr.getGroupId() != null) {
            bucketKey = "g:" + qr.getGroupId() + "|已删除分组";
        } else {
            bucketKey = "u:未分组";
        }

        mid.get(city).get(district).putIfAbsent(bucketKey, new ArrayList<>());
        mid.get(city).get(district).get(bucketKey).add(qr);
    }

    // Supplement empty group nodes (same as before)
    for (QrCodeGroup g : groups) {
        if (coveredGroupIds.contains(g.getId())) continue;
        String city = g.getRegionCity() != null ? g.getRegionCity() : "未分类";
        String district = g.getRegionDistrict() != null ? g.getRegionDistrict() : "未分类";
        mid.putIfAbsent(city, new LinkedHashMap<>());
        mid.get(city).putIfAbsent(district, new LinkedHashMap<>());
        String bucketKey = "g:" + g.getId() + "|" + g.getName();
        mid.get(city).get(district).putIfAbsent(bucketKey, new ArrayList<>());
    }

    // Convert to JSON tree (same logic, use dto getters)
    List<Map<String, Object>> treeList = new ArrayList<>();
    for (var cityEntry : mid.entrySet()) {
        Map<String, Object> cityNode = new LinkedHashMap<>();
        cityNode.put("type", "city");
        cityNode.put("name", cityEntry.getKey());
        List<Map<String, Object>> districtNodes = new ArrayList<>();
        for (var districtEntry : cityEntry.getValue().entrySet()) {
            Map<String, Object> districtNode = new LinkedHashMap<>();
            districtNode.put("type", "district");
            districtNode.put("name", districtEntry.getKey());
            List<Map<String, Object>> childNodes = new ArrayList<>();
            for (var bucketEntry : districtEntry.getValue().entrySet()) {
                String key = bucketEntry.getKey();
                List<QrCodeTreeDto> bucketQrs = bucketEntry.getValue();
                Map<String, Object> groupNode = new LinkedHashMap<>();
                if (key.startsWith("g:")) {
                    String[] parts = key.substring(2).split("\\|", 2);
                    groupNode.put("type", "group");
                    groupNode.put("id", Long.valueOf(parts[0]));
                    groupNode.put("name", parts[1]);
                } else {
                    groupNode.put("type", "ungrouped");
                    groupNode.put("name", "未分组");
                }
                List<Map<String, Object>> qrNodes = new ArrayList<>();
                for (QrCodeTreeDto qr : bucketQrs) {
                    Map<String, Object> qrNode = new LinkedHashMap<>();
                    qrNode.put("type", "qrcode");
                    qrNode.put("id", qr.getId());
                    qrNode.put("name", qr.getSchoolName() != null ? qr.getSchoolName() : "");
                    qrNodes.add(qrNode);
                }
                groupNode.put("children", qrNodes);
                childNodes.add(groupNode);
            }
            districtNode.put("children", childNodes);
            districtNodes.add(districtNode);
        }
        cityNode.put("children", districtNodes);
        treeList.add(cityNode);
    }
    return treeList;
}
```

- [ ] **Step 3: Refactor detail() — use DB-paginated backups**

Replace the backups section in `detail()` (lines 744-767) with:

```java
// ---- 3. Backups (DB-paginated) ----
int backupPageSize = 100;
Page<GlobalAgentPool> backupPage = qrCodeService.getBackups(id, page, backupPageSize);
// Pool status stats via COUNT queries
Map<String, Long> poolStats = qrCodeService.getPoolStats();
model.addAttribute("poolStandby", poolStats.get("standby"));
model.addAttribute("poolFull", poolStats.get("full"));
model.addAttribute("poolBlocked", poolStats.get("blocked"));
// Paginated backups
model.addAttribute("backups", backupPage.getContent());
model.addAttribute("backupPage", page);
model.addAttribute("backupTotalPages", backupPage.getTotalPages());
model.addAttribute("backupTotalItems", backupPage.getTotalElements());
model.addAttribute("backupPageSize", backupPageSize);
// Lightweight userid projection for modal dedup
model.addAttribute("allPoolUserids", qrCodeService.getAllPoolUserids());
```

Also remove the `List<GlobalAgentPool> allBackups = qrCodeService.getBackups(id);` line and all `allBackups.stream()` references.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: refactor list/tree/detail with aggregation, scope, projection and DB pagination

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: QrCodeController 新增导出 + 批量操作端点

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`

**Interfaces:**
- Consumes: `QrCodeRepository.findAllForExport()` (Task 2), `CustomerRepository.countTotalAndTodayByQrIds()` (Task 1), `QrCodeService` batch methods (Task 6)
- Produces: `GET /qrcodes/export`, `POST /qrcodes/batch/welcome`, `POST /qrcodes/batch/form-template`, `POST /qrcodes/batch/rotate-mode`, `POST /qrcodes/batch/group`, `POST /qrcodes/batch/thresholds`, `POST /qrcodes/batch/status`

- [ ] **Step 1: Add export endpoint**

```java
/**
 * 导出活码列表为 Excel 文件。
 *
 * <p>GET /qrcodes/export —— 按当前筛选条件查询全量活码，生成 .xlsx 下载。
 * 使用 SXSSFWorkbook 流式写入，避免大数据量 OOM。</p>
 */
@GetMapping("/export")
public void export(@RequestParam(required = false) String keyword,
                   @RequestParam(required = false) String city,
                   @RequestParam(required = false) String district,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String scope,
                   @RequestParam(required = false) Long groupId,
                   HttpServletResponse response) throws Exception {

    QrCode.QrCodeStatus qrStatus = null;
    if (status != null && !status.isEmpty()) {
        try { qrStatus = QrCode.QrCodeStatus.valueOf(status); }
        catch (IllegalArgumentException ignored) {}
    }

    Boolean allianceOnly = null;
    if ("alliance".equals(scope)) allianceOnly = true;
    else if ("school".equals(scope)) allianceOnly = false;

    List<QrCode> qrs = qrCodeRepo.findAllForExport(
        keyword, city, district, qrStatus, groupId, allianceOnly);

    // Batch get customer counts
    List<Long> allIds = qrs.stream().map(QrCode::getId).collect(Collectors.toList());
    Map<Long, Long> totalCountMap = new LinkedHashMap<>();
    Map<Long, Long> todayCountMap = new LinkedHashMap<>();
    if (!allIds.isEmpty()) {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<Object[]> stats = customerRepo.countTotalAndTodayByQrIds(allIds, todayStart);
        for (Object[] row : stats) {
            totalCountMap.put((Long) row[0], (Long) row[1]);
            todayCountMap.put((Long) row[0], (Long) row[2]);
        }
    }

    // Build group name map
    Map<Long, String> groupNameMap = new LinkedHashMap<>();
    for (QrCodeGroup g : groupRepo.findAllByOrderByName()) {
        groupNameMap.put(g.getId(), g.getName());
    }

    // Generate Excel with SXSSFWorkbook
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition",
        "attachment; filename=qr_codes_" + java.time.LocalDate.now() + ".xlsx");

    Workbook wb = new SXSSFWorkbook(100); // 100 rows in memory, rest flushed to disk
    Sheet sheet = wb.createSheet("活码列表");
    Row header = sheet.createRow(0);
    String[] headers = {"学校名称", "学校ID", "城市", "区县", "分组", "状态",
                        "轮换模式", "今日新增", "累计客户", "创建时间"};
    for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
    }

    int rowIdx = 1;
    for (QrCode qr : qrs) {
        Row row = sheet.createRow(rowIdx++);
        Long qid = qr.getId();
        row.createCell(0).setCellValue(qr.getSchoolName() != null ? qr.getSchoolName() : "");
        row.createCell(1).setCellValue(qr.getSchoolId() != null ? qr.getSchoolId() : "");
        row.createCell(2).setCellValue(qr.getRegionCity() != null ? qr.getRegionCity() : "");
        row.createCell(3).setCellValue(qr.getRegionDistrict() != null ? qr.getRegionDistrict() : "");
        row.createCell(4).setCellValue(
            qr.getGroupId() != null ? groupNameMap.getOrDefault(qr.getGroupId(), "") : "");
        row.createCell(5).setCellValue(qr.getStatus() != null ? qr.getStatus().name() : "");
        row.createCell(6).setCellValue(qr.getRotateMode() != null ? qr.getRotateMode().name() : "");
        row.createCell(7).setCellValue(todayCountMap.getOrDefault(qid, 0L));
        row.createCell(8).setCellValue(totalCountMap.getOrDefault(qid, 0L));
        row.createCell(9).setCellValue(
            qr.getCreatedAt() != null ? qr.getCreatedAt().toString() : "");
    }

    wb.write(response.getOutputStream());
    wb.close();
}
```

- [ ] **Step 2: Add batch operation endpoints**

```java
/** 批量更新欢迎语 */
@PostMapping("/batch/welcome")
@ResponseBody
public Map<String, Object> batchUpdateWelcome(@RequestParam List<Long> ids,
                                               @RequestParam String welcomeText) {
    int n = qrCodeService.batchUpdateWelcomeText(ids, welcomeText);
    return Map.of("ok", true, "count", n);
}

/** 批量更新表单模板 */
@PostMapping("/batch/form-template")
@ResponseBody
public Map<String, Object> batchUpdateFormTemplate(@RequestParam List<Long> ids,
                                                    @RequestParam(required = false) Long formTemplateId) {
    int n = qrCodeService.batchUpdateFormTemplateId(ids, formTemplateId);
    return Map.of("ok", true, "count", n);
}

/** 批量切换轮换模式 */
@PostMapping("/batch/rotate-mode")
@ResponseBody
public Map<String, Object> batchUpdateRotateMode(@RequestParam List<Long> ids,
                                                  @RequestParam String mode) {
    QrCode.RotateMode rm = QrCode.RotateMode.valueOf(mode);
    int n = qrCodeService.batchUpdateRotateMode(ids, rm);
    return Map.of("ok", true, "count", n);
}

/** 批量改分组 */
@PostMapping("/batch/group")
@ResponseBody
public Map<String, Object> batchUpdateGroup(@RequestParam List<Long> ids,
                                             @RequestParam(required = false) Long groupId) {
    int n = qrCodeService.batchUpdateGroupId(ids, groupId);
    return Map.of("ok", true, "count", n);
}

/** 批量改阈值 */
@PostMapping("/batch/thresholds")
@ResponseBody
public Map<String, Object> batchUpdateThresholds(@RequestParam List<Long> ids,
                                                  @RequestParam int warnRatio,
                                                  @RequestParam int urgentRatio) {
    int n = qrCodeService.batchUpdateThresholds(ids, warnRatio, urgentRatio);
    return Map.of("ok", true, "count", n);
}

/** 批量更新状态 */
@PostMapping("/batch/status")
@ResponseBody
public Map<String, Object> batchUpdateStatus(@RequestParam List<Long> ids,
                                              @RequestParam String status) {
    QrCode.QrCodeStatus s = QrCode.QrCodeStatus.valueOf(status);
    int n = qrCodeService.batchUpdateStatus(ids, s);
    return Map.of("ok", true, "count", n);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add export endpoint and batch operation endpoints

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 批量导入模板下载端点

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`

**Interfaces:**
- Consumes: (none)
- Produces: `GET /qrcodes/batch-import/template` — 下载预置表头 + 示例行的 .xlsx 模板

- [ ] **Step 1: Add template download endpoint**

```java
/**
 * 下载批量导入 Excel 模板。
 *
 * <p>GET /qrcodes/batch-import/template —— 返回预置表头（11列）+ 一行示例数据的 .xlsx。</p>
 */
@GetMapping("/batch-import/template")
public void downloadTemplate(HttpServletResponse response) throws Exception {
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition",
        "attachment; filename=qr_code_import_template.xlsx");

    Workbook wb = new XSSFWorkbook();
    Sheet sheet = wb.createSheet("活码导入");
    Row header = sheet.createRow(0);
    String[] headers = {"学校名称", "学校ID", "市", "区", "服务老师(userid)",
                        "学校人数", "初始上码员工数", "接待员(userid,逗号分隔)",
                        "服务老师日上限", "欢迎语", "备注"};
    for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
    }

    // Example row
    Row example = sheet.createRow(1);
    example.createCell(0).setCellValue("示例中学");
    example.createCell(1).setCellValue("SCH001");
    example.createCell(2).setCellValue("武汉");
    example.createCell(3).setCellValue("武昌区");
    example.createCell(4).setCellValue("zhangsan");
    example.createCell(5).setCellValue("500");
    example.createCell(6).setCellValue("1");
    example.createCell(7).setCellValue("lisi,wangwu");
    example.createCell(8).setCellValue("30");
    example.createCell(9).setCellValue("欢迎来到示例中学！");
    example.createCell(10).setCellValue("备注示例");

    // Auto-size columns
    for (int i = 0; i < headers.length; i++) {
        sheet.autoSizeColumn(i);
    }

    wb.write(response.getOutputStream());
    wb.close();
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add batch import Excel template download endpoint

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 前端 — 批量导入页面更新

**Files:**
- Modify: `src/main/resources/templates/qrcode/batch-import.html`

**Interfaces:**
- Consumes: Template download endpoint (Task 9)
- Produces: Updated batch import page with template download button and 11-column format description

- [ ] **Step 1: Update format description and add template download button**

Replace lines 8-24 (the alert and upload form area):

```html
<div class="card p-4 mx-auto" style="max-width: 700px;">
    <h5 class="mb-3">批量导入活码</h5>
    <!-- Excel 格式说明 -->
    <div class="alert alert-info">
        <strong>Excel 格式要求：</strong><br>
        第1行：表头<br>
        第2行起：数据行，每行一个学校<br>
        <table class="table table-sm table-bordered mt-2 mb-0">
            <thead><tr>
                <th>学校名称*</th><th>学校ID*</th><th>市*</th><th>区*</th>
                <th>服务老师*</th><th>学校人数*</th><th>初始上码数</th>
                <th>接待员</th><th>日上限</th><th>欢迎语</th><th>备注</th>
            </tr></thead>
            <tbody><tr>
                <td colspan="11" class="text-muted small">
                    * 必填列 | 接待员多个月逗号分隔 | 初始上码数默认1 | 日上限默认30
                </td>
            </tr></tbody>
        </table>
    </div>

    <!-- 模板下载 + 上传表单 -->
    <div class="mb-3" th:if="${taskId == null}">
        <a href="/qrcodes/batch-import/template" class="btn btn-outline-info mb-2">
            📥 下载模板
        </a>
    </div>

    <form method="post" th:action="@{/qrcodes/batch-import}" enctype="multipart/form-data"
          th:if="${taskId == null}">
        <div class="mb-3">
            <label class="form-label">选择 Excel 文件 (.xlsx)</label>
            <input type="file" name="file" class="form-control" accept=".xlsx" required>
        </div>
        <button type="submit" class="btn btn-primary">📤 开始导入</button>
        <a href="/qrcodes" class="btn btn-outline-secondary">返回列表</a>
    </form>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/qrcode/batch-import.html
git commit -m "feat: update batch import page with template download and 11-column format

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: 前端 — 列表页更新（批量模式 + 筛选增强 + 导出 + 累计客户数）

**Files:**
- Modify: `src/main/resources/templates/qrcode/list.html`

**Interfaces:**
- Consumes: Updated `list()` model attributes (Task 7), batch endpoints (Task 8), export endpoint (Task 8)

- [ ] **Step 1: Add group filter and export button in filter section**

Before the search button in the filter form (around line 80-85):

```html
<!-- 分组筛选 -->
<div class="col-md-2 mb-2">
    <select class="form-select" name="groupId">
        <option value="">全部分组</option>
        <option th:each="g : ${groups}" th:value="${g.id}"
                th:selected="${groupId != null && groupId == g.id}"
                th:text="${g.name}"></option>
    </select>
</div>
<!-- 每页条数 -->
<div class="col-md-1 mb-2">
    <select class="form-select" name="size" onchange="this.form.submit()">
        <option value="20" th:selected="${param.size == null || param.size[0] == '20'}">20</option>
        <option value="50" th:selected="${param.size != null && param.size[0] == '50'}">50</option>
        <option value="100" th:selected="${param.size != null && param.size[0] == '100'}">100</option>
    </select>
</div>
```

Add export button next to the search button:

```html
<button type="submit" class="btn btn-primary">🔍 搜索</button>
<a th:href="@{/qrcodes/export(keyword=${keyword},city=${city},district=${district},
    status=${status},scope=${scope},groupId=${groupId})}"
   class="btn btn-outline-success">📊 导出</a>
```

- [ ] **Step 2: Add batch mode toggle and toolbar**

Add before the table (around line 94):

```html
<!-- 批量模式切换 -->
<div class="mb-2">
    <button id="batchModeBtn" class="btn btn-outline-warning btn-sm"
            onclick="toggleBatchMode()">🔧 批量操作模式</button>
    <span id="batchToolbar" style="display:none;" class="ms-2">
        已选 <strong id="selectedCount">0</strong> 个活码
        <button class="btn btn-sm btn-outline-primary ms-2" onclick="batchAction('welcome')">改欢迎语</button>
        <button class="btn btn-sm btn-outline-primary" onclick="batchAction('form-template')">改表单模板</button>
        <button class="btn btn-sm btn-outline-primary" onclick="batchAction('rotate-mode')">切换轮换</button>
        <button class="btn btn-sm btn-outline-primary" onclick="batchAction('group')">改分组</button>
        <button class="btn btn-sm btn-outline-primary" onclick="batchAction('status')">暂停/启用</button>
    </span>
</div>
```

- [ ] **Step 3: Add total customer count column in table**

In the table header (around line 100-115), add a column after 今日新增:

```html
<th>今日新增</th>
<th>累计客户</th>
```

In the table body, add the corresponding cell:

```html
<td th:text="${todayCountMap.getOrDefault(qr.id, 0L)}">0</td>
<td th:text="${totalCountMap.getOrDefault(qr.id, 0L)}">0</td>
```

- [ ] **Step 4: Add batch mode JavaScript**

Add before the closing `</script>` tag (around line 540):

```javascript
let batchMode = false;
const selectedIds = new Set();

function toggleBatchMode() {
    batchMode = !batchMode;
    document.getElementById('batchToolbar').style.display = batchMode ? 'inline' : 'none';
    document.getElementById('batchModeBtn').className =
        batchMode ? 'btn btn-warning btn-sm' : 'btn btn-outline-warning btn-sm';
    document.getElementById('batchModeBtn').textContent =
        batchMode ? '🔧 退出批量模式' : '🔧 批量操作模式';
    // Show/hide checkboxes
    document.querySelectorAll('.batch-checkbox').forEach(cb =>
        cb.style.display = batchMode ? 'inline' : 'none');
    if (!batchMode) { selectedIds.clear(); updateSelectedCount(); }
}

function onCheck(id) {
    if (selectedIds.has(id)) selectedIds.delete(id); else selectedIds.add(id);
    updateSelectedCount();
}

function updateSelectedCount() {
    document.getElementById('selectedCount').textContent = selectedIds.size;
}

function batchAction(type) {
    if (selectedIds.size === 0) { alert('请先选择活码'); return; }
    const ids = Array.from(selectedIds);
    let url, body;

    switch(type) {
        case 'welcome':
            const text = prompt('请输入新欢迎语：');
            if (!text) return;
            body = 'ids=' + ids.join('&ids=') + '&welcomeText=' + encodeURIComponent(text);
            url = '/qrcodes/batch/welcome';
            break;
        case 'form-template':
            const ftId = prompt('请输入表单模板ID（留空清空）：');
            if (ftId === null) return;
            body = 'ids=' + ids.join('&ids=') + (ftId ? '&formTemplateId=' + ftId : '');
            url = '/qrcodes/batch/form-template';
            break;
        case 'rotate-mode':
            const mode = prompt('请输入轮换模式（auto/manual）：');
            if (!mode) return;
            body = 'ids=' + ids.join('&ids=') + '&mode=' + mode;
            url = '/qrcodes/batch/rotate-mode';
            break;
        case 'group':
            const gid = prompt('请输入分组ID（留空取消分组）：');
            if (gid === null) return;
            body = 'ids=' + ids.join('&ids=') + (gid ? '&groupId=' + gid : '');
            url = '/qrcodes/batch/group';
            break;
        case 'status':
            const st = prompt('请输入状态（active/paused）：');
            if (!st) return;
            body = 'ids=' + ids.join('&ids=') + '&status=' + st;
            url = '/qrcodes/batch/status';
            break;
    }

    fetch(url, { method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: body })
        .then(r => r.json())
        .then(d => {
            if (d.ok) { alert('成功更新 ' + d.count + ' 个活码'); location.reload(); }
            else alert('操作失败');
        });
}
```

Add `class="batch-checkbox" style="display:none;"` and `onclick="onCheck(QR_ID)"` to each row's checkbox. The existing checkbox input needs to be updated:

```html
<input type="checkbox" class="form-check-input batch-checkbox" style="display:none;"
       th:value="${qr.id}" onclick="onCheck([[${qr.id}]])">
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/qrcode/list.html
git commit -m "feat: add batch mode, group filter, export button, total count column to list page

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 12: 集成验证 + 全链路冒烟测试

**Files:**
- No new files — verification only

- [ ] **Step 1: 启动应用并验证列表页**

```bash
# Start app (adjust command per project)
mvn spring-boot:run
```

验证点：
1. 列表页加载 — 检查今日新增和累计客户数显示正确
2. Scope 筛选（alliance/school）— 确认不走全量加载（日志观察 SQL）
3. 分组筛选下拉 — 选分组后正确过滤
4. 切换每页 20/50/100 — 分页功能正常
5. 导出按钮 — 下载的 Excel 文件内容完整、列正确

- [ ] **Step 2: 验证批量操作**

验证点：
1. 点击"批量操作模式"按钮 — checkbox 显示，工具栏出现
2. 勾选若干活码 — 已选计数正确
3. 每一项批量操作（改欢迎语、改表单模板、切换轮换、改分组、改状态）— 执行后活码状态更新正确

- [ ] **Step 3: 验证批量导入**

验证点：
1. 点击"下载模板" — 下载的 .xlsx 表头为 11 列、有示例行
2. 按模板填写数据上传 — 检查异步进度正常、创建成功
3. 缺少必填列 — 数据库验证字段正确填充

- [ ] **Step 4: 验证全局池分页**

验证点：
1. 详情页后备池 — 分页切换正常（上一页/下一页）
2. 池状态统计（standby/full/blocked 数字）— 与数据库一致
3. 弹窗去重 userid — 新增后备时已有 userid 不出现在下拉中

- [ ] **Step 5: 验证树接口**

验证点：
1. 左侧树导航 — 展开/折叠正常，节点点击跳转正常
2. 分组节点 — 显示正确
3. 未分组节点 — 归入正确 city/district

- [ ] **Step 6: Commit if any fixes applied**

```bash
git add -A
git commit -m "chore: integration verification fixes

Co-Authored-By: Claude <noreply@anthropic.com>"
```
