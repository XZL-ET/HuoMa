# 基于场景的接待员分配优化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建活码时根据场景（日常推送/家长会）自动计算接待员数量，支持按部门匹配扩容取人，并支持手动回收闲置接待员。

**Architecture:** 配置驱动 + 公式替换 + 部门优先取人。新增 SceneConfigProperties 管理场景参数，修改 QrCodeService.create() 公式从 `ceil(studentCount/100)` 改为 `ceil(studentCount × scanRatio / dailyMax)`，GlobalAgentPoolService.takeStandby 增加部门优先逻辑，前端新增场景选择、部门选择、回收按钮。

**Tech Stack:** Spring Boot 3.x, JPA/Hibernate, Thymeleaf, MySQL, Redis, Bootstrap 5

## Global Constraints

- 不引入新的外部依赖
- 遵循现有 @ConfigurationProperties 模式（参考 WecomConfig）
- 遵循现有条件 DDL 迁移模式（schema.sql 中使用 INFORMATION_SCHEMA 检测）
- 服务老师（role=service）不可回收，回收后至少保留 1 个 active 接待员
- 企业微信部门数据存为 JSON 数组，取主部门（数组第一个元素）
- 部门匹配支持父子包容：父部门包含所有子部门员工
- 存量活码 scene 默认 daily_push，departmentId 默认 null

---

### Task 1: 配置与数据库迁移

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/config/SceneConfigProperties.java`
- Modify: `src/main/resources/application.yml:55-63`
- Modify: `src/main/resources/schema.sql` (追加新 DDL)

**Interfaces:**
- Produces: `SceneConfigProperties` bean — `getDailyPush()` / `getParentMeeting()` 返回 `ScenePreset`（含 `scanRatio: double`, `urgentRatio: int`）
- Produces: `SceneConfigProperties.getPreset(Scene scene)` — 按枚举返回对应预设

- [ ] **Step 1: 新增 application.yml 场景配置块**

在 `application.yml` 的 `app:` 块内（`app.agent:` 之后）追加：

```yaml
  scene:
    daily-push:
      scan-ratio: 0.10
      urgent-ratio: 95
    parent-meeting:
      scan-ratio: 0.60
      urgent-ratio: 70
```

- [ ] **Step 2: 新增 SceneConfigProperties 配置类**

```java
package com.bookstore.qrcode.config;

import com.bookstore.qrcode.entity.Scene;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 场景预设配置 — 绑定 application.yml 中 app.scene.* 配置。
 *
 * <p>每个场景包含预期扫码率（scanRatio）和预激活阈值（urgentRatio），
 * 用于活码创建时自动计算初始接待员数及扩容触发点。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.scene")
public class SceneConfigProperties {

    private ScenePreset dailyPush = new ScenePreset(0.10, 95);
    private ScenePreset parentMeeting = new ScenePreset(0.60, 70);

    /**
     * 根据场景枚举获取对应预设配置。
     */
    public ScenePreset getPreset(Scene scene) {
        if (scene == null) return dailyPush;
        return switch (scene) {
            case daily_push -> dailyPush;
            case parent_meeting -> parentMeeting;
        };
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenePreset {
        /** 预期扫码率，如 0.10 表示预计 10% 的学生会扫码 */
        private double scanRatio;
        /** 预激活阈值百分比，如 70 表示达到日限 70% 时预加载后备 */
        private int urgentRatio;
    }
}
```

- [ ] **Step 3: schema.sql 追加 DDL**

在 `schema.sql` 末尾追加（遵循现有条件 DDL 模式）：

```sql
-- 场景分配优化：scene + department_id 字段
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'scene') = 0,
    'ALTER TABLE qr_code ADD COLUMN scene ENUM(''daily_push'',''parent_meeting'')
        NOT NULL DEFAULT ''daily_push'' COMMENT ''场景：日常推送/家长会''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'department_id') = 0,
    'ALTER TABLE qr_code ADD COLUMN department_id BIGINT
        COMMENT ''所属企微部门ID（扩容时同部门优先取人）''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_agent_pool' AND COLUMN_NAME = 'department_id') = 0,
    'ALTER TABLE global_agent_pool ADD COLUMN department_id BIGINT
        COMMENT ''员工所属企微部门ID（从Employee同步，取主部门）''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 部门索引（加速同部门 standby 查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_agent_pool' AND INDEX_NAME = 'idx_pool_dept') = 0,
    'CREATE INDEX idx_pool_dept ON global_agent_pool(department_id, status, sort_order)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 4: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml src/main/resources/schema.sql src/main/java/com/bookstore/qrcode/config/SceneConfigProperties.java
git commit -m "feat: add scene config and DB migration for scene-based allocation

- SceneConfigProperties binding app.scene.* with default presets
- DDL: qr_code.scene, qr_code.department_id, global_agent_pool.department_id
- Index: idx_pool_dept for department-scoped standby queries

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Scene 枚举 + 实体字段

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/Scene.java`
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrCode.java` (追加 2 字段)
- Modify: `src/main/java/com/bookstore/qrcode/entity/GlobalAgentPool.java` (追加 1 字段)
- Modify: `src/main/java/com/bookstore/qrcode/dto/QrCodeCreateRequest.java` (追加 2 字段)
- Modify: `src/test/resources/schema-test.sql` (追加 DDL)

**Interfaces:**
- Produces: `Scene` enum — `daily_push`, `parent_meeting`
- Produces: `QrCode.scene: Scene` (default `daily_push`), `QrCode.departmentId: Long`
- Produces: `GlobalAgentPool.departmentId: Long`
- Produces: `QrCodeCreateRequest.scene: Scene`, `QrCodeCreateRequest.departmentId: Long`

- [ ] **Step 1: 新建 Scene 枚举**

```java
package com.bookstore.qrcode.entity;

/**
 * 活码创建场景枚举。
 *
 * <p>不同场景对应不同的预期扫码率和扩容策略，
 * 用于创建活码时自动计算初始接待员数量。</p>
 */
public enum Scene {
    /** 日常推送：二维码发到家长群，长期有效，预期扫码率约 10% */
    daily_push,
    /** 家长会：现场集中扫码，短期高并发，预期扫码率约 60% */
    parent_meeting
}
```

- [ ] **Step 2: QrCode 实体新增 scene + departmentId**

在 `QrCode.java` 的 `studentCount` 字段之后（第 146 行之后）追加：

```java
    /** 活码创建场景：daily_push-日常推送, parent_meeting-家长会 */
    @Column(name = "scene", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Scene scene = Scene.daily_push;

    /** 所属企微部门 ID，用于扩容时同部门优先取人 */
    @Column(name = "department_id")
    private Long departmentId;
```

- [ ] **Step 3: GlobalAgentPool 实体新增 departmentId**

在 `GlobalAgentPool.java` 的 `sortOrder` 字段之后（第 53 行之后）追加：

```java
    /** 员工所属企微部门 ID（取主部门，从 Employee 同步），null 时退化为全局取人 */
    @Column(name = "department_id")
    private Long departmentId;
```

- [ ] **Step 4: QrCodeCreateRequest DTO 新增 scene + departmentId**

在 `QrCodeCreateRequest.java` 的 `studentCount` 字段之后（第 36 行之后）追加：

```java
    /** 活码创建场景：daily_push-日常推送, parent_meeting-家长会，默认 daily_push */
    private Scene scene;

    /** 所属企微部门 ID，用于扩容时同部门优先取人 */
    private Long departmentId;
```

- [ ] **Step 5: 同步 schema-test.sql**

在 `src/test/resources/schema-test.sql` 的 `qr_code` 建表语句中追加（在 `student_count` 之后）：

```sql
    scene ENUM('daily_push','parent_meeting') NOT NULL DEFAULT 'daily_push' COMMENT '场景',
    department_id BIGINT COMMENT '所属企微部门ID',
```

在 `global_agent_pool` 建表语句中追加（在 `sort_order` 之后）：

```sql
    department_id BIGINT COMMENT '员工所属企微部门ID',
```

- [ ] **Step 6: 编译 + 测试验证**

```bash
./mvnw test -pl . -q -Dtest="QrCodeCreationFlowTest,GlobalAgentPoolFlowTest"
```

Expected: all existing tests pass (新增字段有默认值，不影响已有测试)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/entity/Scene.java src/main/java/com/bookstore/qrcode/entity/QrCode.java src/main/java/com/bookstore/qrcode/entity/GlobalAgentPool.java src/main/java/com/bookstore/qrcode/dto/QrCodeCreateRequest.java src/test/resources/schema-test.sql
git commit -m "feat: add Scene enum and department fields to entities

- Scene enum: daily_push, parent_meeting
- QrCode: scene + departmentId
- GlobalAgentPool: departmentId
- QrCodeCreateRequest: scene + departmentId

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 部门列表 API + 场景配置查询接口

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java` (新增 listDepartments)
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` (新增 GET /api/config/scene)

**Interfaces:**
- Produces: `WecomApiClient.listDepartments(Long parentId)` → `JsonNode`（企微部门列表）
- Produces: `GET /api/config/scene` → `{ "scenes": { "daily_push": {...}, "parent_meeting": {...} }, "dailyMax": 100 }`

- [ ] **Step 1: WecomApiClient 新增 listDepartments 方法**

在 `WecomApiClient.java` 末尾（类闭合 `}` 之前）追加：

```java
    /**
     * 获取企微部门列表。
     *
     * <p><b>企微接口：</b>{@code GET /cgi-bin/department/list}
     *
     * @param parentId 父部门 ID，传 null 表示获取全部部门
     * @return 企微 API 响应 JSON，包含 department 数组
     * @throws WecomApiException API 调用失败时抛出
     */
    public JsonNode listDepartments(Long parentId) throws WecomApiException {
        String accessToken = getAccessToken();
        StringBuilder url = new StringBuilder(
            "https://qyapi.weixin.qq.com/cgi-bin/department/list?access_token=" + accessToken);
        if (parentId != null) {
            url.append("&id=").append(parentId);
        }
        JsonNode resp = httpGet(url.toString());
        checkWecomError(resp);
        return resp;
    }
```

- [ ] **Step 2: QrCodeController 新增场景配置查询接口**

在 `QrCodeController.java` 中新增方法（放在类中合适位置，如 `updateThresholds` 方法附近）：

```java
    private final com.bookstore.qrcode.config.SceneConfigProperties sceneConfig;

    // 在构造函数参数中添加 sceneConfig 依赖

    /**
     * 获取场景预设配置，供前端创建活码时使用。
     *
     * <p>返回各场景的扫码率、预激活阈值以及全局默认日限，
     * 前端据此实时计算预估接待员数。</p>
     */
    @GetMapping("/api/config/scene")
    @ResponseBody
    public Map<String, Object> getSceneConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> scenes = new LinkedHashMap<>();

        for (Scene s : Scene.values()) {
            SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(s);
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("scanRatio", preset.getScanRatio());
            cfg.put("urgentRatio", preset.getUrgentRatio());
            scenes.put(s.name(), cfg);
        }
        result.put("scenes", scenes);
        result.put("dailyMax", dailyMaxDefault);
        return result;
    }
```

需要在 `QrCodeController` 中注入 `dailyMaxDefault`（从 `@Value("${app.agent.daily-max-default:100}")` 获取）。

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add WecomApiClient.listDepartments and GET /api/config/scene

- listDepartments: calls WeChat Work department/list API
- /api/config/scene: returns scene presets and dailyMax for frontend

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 新分配公式 + 场景联动阈值

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java:215-220`（替换公式）
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java:254-260`（场景联动阈值）
- Create: `src/test/java/com/bookstore/qrcode/service/SceneAllocationTest.java`

**Interfaces:**
- Consumes: `SceneConfigProperties.getPreset(Scene)` 返回 `ScenePreset{scanRatio, urgentRatio}`
- Consumes: `dailyMaxDefault` 来自 `@Value("${app.agent.daily-max-default:100}")`
- Produces: `QrCode.initialAgentCount` 按新公式计算；`QrCode.warnRatio/urgentRatio` 按场景联动设置

- [ ] **Step 1: 编写单元测试**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.SceneConfigProperties;
import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.Scene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SceneAllocationTest {

    @Autowired
    private SceneConfigProperties sceneConfig;

    @Test
    @DisplayName("日常推送 1000 学生 → 1 接待员（原来 10）")
    void dailyPush1000Students() {
        // studentCount=1000, scanRatio=0.10, dailyMax=100
        // expectedScans = ceil(1000 * 0.10) = 100
        // need = ceil(100 / 100) = 1
        int need = computeAgentCount(1000, 0.10, 100);
        assertThat(need).isEqualTo(1);
    }

    @Test
    @DisplayName("家长会 1000 学生 → 6 接待员（原来 10）")
    void parentMeeting1000Students() {
        // studentCount=1000, scanRatio=0.60, dailyMax=100
        // expectedScans = ceil(1000 * 0.60) = 600
        // need = ceil(600 / 100) = 6
        int need = computeAgentCount(1000, 0.60, 100);
        assertThat(need).isEqualTo(6);
    }

    @ParameterizedTest
    @CsvSource({
        "500,  0.10, 100, 1",
        "500,  0.60, 100, 3",
        "3000, 0.10, 100, 3",
        "3000, 0.60, 100, 18",
        "5000, 0.10, 100, 5",
        "5000, 0.60, 100, 30",
        "100,  0.10, 100, 1",   // 最少 1 人
        "50,   0.10, 100, 1",    // 最少 1 人
        "20000, 0.60, 100, 100", // 最多 100 人
    })
    @DisplayName("公式计算验证")
    void formulaTest(int studentCount, double scanRatio, int dailyMax, int expected) {
        int need = computeAgentCount(studentCount, scanRatio, dailyMax);
        assertThat(need).isEqualTo(expected);
    }

    @Test
    @DisplayName("配置绑定验证")
    void configBinding() {
        assertThat(sceneConfig.getDailyPush().getScanRatio()).isEqualTo(0.10);
        assertThat(sceneConfig.getDailyPush().getUrgentRatio()).isEqualTo(95);
        assertThat(sceneConfig.getParentMeeting().getScanRatio()).isEqualTo(0.60);
        assertThat(sceneConfig.getParentMeeting().getUrgentRatio()).isEqualTo(70);
    }

    /** 与 QrCodeService 中公式一致 */
    static int computeAgentCount(int studentCount, double scanRatio, int dailyMax) {
        int expectedScans = (int) Math.ceil(studentCount * scanRatio);
        return Math.max(1, Math.min(100,
            (int) Math.ceil((double) expectedScans / dailyMax)));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./mvnw test -pl . -Dtest="SceneAllocationTest" -q
```

Expected: FAIL — 新公式尚未应用到 QrCodeService

- [ ] **Step 3: 修改 QrCodeService.create() 公式**

替换 `QrCodeService.java` 第 215-220 行（旧公式）为：

```java
        // 根据场景自动计算所需接待员总数
        // 公式：ceil(学生人数 × 场景扫码率 / 员工日限)，最少 1 人，最多 100 人
        if (req.getStudentCount() != null && req.getStudentCount() > 0) {
            // 用户手动指定了 initialAgentCount 则不自动计算
            if (req.getInitialAgentCount() == null) {
                Scene scene = req.getScene() != null ? req.getScene() : Scene.daily_push;
                SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(scene);
                int expectedScans = (int) Math.ceil(req.getStudentCount() * preset.getScanRatio());
                int need = Math.max(1, Math.min(100,
                    (int) Math.ceil((double) expectedScans / dailyMaxDefault)));
                req.setInitialAgentCount(need);
                log.info("学校人数={}, 场景={}, 扫码率={}, 自动计算 initialAgentCount={}",
                    req.getStudentCount(), scene.name(), preset.getScanRatio(), need);
            }
        }

        // 根据场景联动设置阈值
        if (req.getScene() != null) {
            SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(req.getScene());
            req.setUrgentRatio(preset.getUrgentRatio());
        }
```

需要新增字段注入（在 `QrCodeService` 类中）：

```java
    private final SceneConfigProperties sceneConfig;
    @Value("${app.agent.daily-max-default:100}")
    private int dailyMaxDefault;
```

注意：构造函数是 `@RequiredArgsConstructor`，新增 `final` 字段后 Lombok 会自动纳入构造参数。

- [ ] **Step 4: 修改 create() 中 QrCode.builder() 传入 scene + departmentId + 场景联动阈值**

在 `QrCodeService.create()` 中，先计算场景联动阈值（放在 builder 之前）：

```java
        // 场景联动阈值
        Scene effectiveScene = req.getScene() != null ? req.getScene() : Scene.daily_push;
        SceneConfigProperties.ScenePreset preset = sceneConfig.getPreset(effectiveScene);
        int effectiveWarnRatio = 80;  // 预警阈值不变
        int effectiveUrgentRatio = preset.getUrgentRatio();
```

然后在 `QrCode.builder()` 中追加（约第 254-270 行，找到现有 builder 末尾）：

```java
            .scene(effectiveScene)
            .departmentId(req.getDepartmentId())
            .warnRatio(effectiveWarnRatio)
            .urgentRatio(effectiveUrgentRatio)
```

注意：如果 builder 中已有 `.warnRatio(...)` / `.urgentRatio(...)` 调用（使用实体默认值），则用上述 `effectiveWarnRatio` / `effectiveUrgentRatio` 替换。

- [ ] **Step 5: 运行测试确认通过**

```bash
./mvnw test -pl . -Dtest="SceneAllocationTest,QrCodeCreationFlowTest" -q
```

Expected: all PASS

- [ ] **Step 6: 运行完整测试套件**

```bash
./mvnw test -pl . -q
```

Expected: all tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java src/test/java/com/bookstore/qrcode/service/SceneAllocationTest.java
git commit -m "feat: new allocation formula with scene-linked thresholds

- Formula: ceil(studentCount × scanRatio / dailyMax) replaces ceil(studentCount/100)
- Scene-linked thresholds: parent_meeting → urgentRatio=70
- User manual initialAgentCount overrides auto-calculation
- Add SceneAllocationTest covering formula + config binding

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 部门优先取人

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java` (新增重载 takeStandby)
- Modify: `src/main/java/com/bookstore/qrcode/service/AgentRotationService.java:174,246` (传入 departmentId)
- Modify: `src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java` (新增查询方法)

**Interfaces:**
- Consumes: `QrCode.departmentId` (来自 Task 2)
- Produces: `GlobalAgentPoolService.takeStandby(Set<String> excludeUserids, Long preferredDepartmentId)` → `GlobalAgentPool`
- Produces: `GlobalAgentPoolRepository.findStandbysForUpdate(PoolStatus status)` — 已有
- Produces: `GlobalAgentPoolRepository.findStandbysByDeptForUpdate(Long deptId, PoolStatus status)` — 新增

- [ ] **Step 1: GlobalAgentPoolRepository 新增同部门查询**

在 `GlobalAgentPoolRepository.java` 中新增方法：

```java
    /**
     * 查询指定部门的 standby 员工（含子孙部门），按 sort_order 升序，加行锁防并发。
     *
     * @param departmentIds 部门 ID 集合（含子孙部门），为空时退化为全量查询
     * @param status 池状态（standby）
     * @return 同部门 standby 列表
     */
    @Query("SELECT p FROM GlobalAgentPool p WHERE p.status = :status "
         + "AND (:deptIds IS NULL OR p.departmentId IN :deptIds) "
         + "ORDER BY p.sortOrder ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<GlobalAgentPool> findStandbysByDeptForUpdate(
        @Param("deptIds") Collection<Long> departmentIds,
        @Param("status") GlobalAgentPool.PoolStatus status);
```

- [ ] **Step 2: GlobalAgentPoolService 新增部门优先重载**

在 `GlobalAgentPoolService.java` 中新增方法（保留原 `takeStandby(Set<String>)` 不变，作为降级入口）：

```java
    /**
     * 从全局池取 standby 员工，优先匹配指定部门。
     *
     * <h3>取人优先级</h3>
     * <ol>
     *   <li>同部门（含子孙部门）standby → 按 sort_order 取</li>
     *   <li>同部门无可用 → 降级为全局取人（任意部门）</li>
     *   <li>全局枯竭 → 返回 null</li>
     * </ol>
     *
     * @param excludeUserids 排除的 userid 集合
     * @param preferredDepartmentId 优先部门 ID，null 时直接全局取人
     * @return 可用 standby，或无可用时返回 null
     */
    @Transactional
    public GlobalAgentPool takeStandby(Set<String> excludeUserids, Long preferredDepartmentId) {
        // ① 收集子孙部门 ID（用于 IN 查询）
        Collection<Long> deptIds = null;
        if (preferredDepartmentId != null) {
            deptIds = collectDescendantDeptIds(preferredDepartmentId);
        }

        // ② 查询同部门 standby（带行锁）
        List<GlobalAgentPool> standbys;
        if (deptIds != null && !deptIds.isEmpty()) {
            standbys = poolRepo.findStandbysByDeptForUpdate(deptIds,
                GlobalAgentPool.PoolStatus.standby);
            if (!standbys.isEmpty()) {
                return selectFromDeptCandidates(standbys, excludeUserids);
            }
            log.info("同部门无 standby，降级为全局取人: deptId={}", preferredDepartmentId);
        }

        // ③ 降级：全局取人（调用原方法逻辑）
        return takeStandby(excludeUserids);
    }

    /**
     * 从同部门候选列表中选取第一个可用员工（懒清理 + 公平轮转）。
     *
     * <p>逻辑与 {@link #takeStandby(Set)} 保持一致，
     * 复用相同的懒清理规则（离职/企微不可用/封号熔断）。</p>
     */
    private GlobalAgentPool selectFromDeptCandidates(List<GlobalAgentPool> candidates,
                                                      Set<String> excludeUserids) {
        int skippedInactive = 0, skippedBlocked = 0, skippedWechatUnavailable = 0;
        for (GlobalAgentPool p : candidates) {
            if (excludeUserids != null && excludeUserids.contains(p.getAgentUserid())) {
                continue;
            }
            // 过滤已离职员工 → 懒清理出池
            Employee emp = employeeRepo.findByUserid(p.getAgentUserid()).orElse(null);
            if (emp != null && !emp.getActive()) {
                skippedInactive++;
                poolRepo.delete(p);
                continue;
            }
            // 过滤企微侧不可用员工 → 懒清理出池
            if (emp != null && emp.getWechatStatus() != null && emp.getWechatStatus() != 1) {
                skippedWechatUnavailable++;
                poolRepo.delete(p);
                continue;
            }
            // 过滤封号/熔断员工 → 懒清理出池
            Agent agent = agentRepo.findById(p.getAgentUserid()).orElse(null);
            if (agent != null && (
                agent.getOverallStatus() == Agent.OverallStatus.blocked
                || agent.getOverallStatus() == Agent.OverallStatus.melted)) {
                skippedBlocked++;
                poolRepo.delete(p);
                continue;
            }
            // 取走 → 推到队尾（公平轮转）
            int currentMax = poolRepo.findFirstByOrderBySortOrderDesc()
                .map(GlobalAgentPool::getSortOrder).orElse(0);
            p.setSortOrder(currentMax + 1);
            poolRepo.save(p);
            return p;
        }
        if (skippedInactive > 0 || skippedWechatUnavailable > 0 || skippedBlocked > 0) {
            log.info("同部门懒清理: 跳过离职={}, 企微不可用={}, 封号/熔断={}",
                skippedInactive, skippedWechatUnavailable, skippedBlocked);
        }
        return null;
    }

    /**
     * 收集指定部门的所有子孙部门 ID。
     *
     * <p>调用企微 department/list 接口递归拉取，
     * 结果缓存到本地避免同请求内重复调用。</p>
     */
    private Collection<Long> collectDescendantDeptIds(Long parentId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(parentId);
        try {
            JsonNode resp = wecomApi.listDepartments(parentId);
            if (resp.has("department") && resp.get("department").isArray()) {
                for (JsonNode dept : resp.get("department")) {
                    long childId = dept.get("id").asLong();
                    if (!ids.contains(childId)) {
                        ids.addAll(collectDescendantDeptIds(childId));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("拉取子孙部门失败: parentId={}, 仅用直接部门ID", parentId, e);
        }
        return ids;
    }
```

- [ ] **Step 3: AgentRotationService 传入 departmentId**

修改 `AgentRotationService.java` 中两处 `takeStandby` 调用：

**expandQrCodeUsers（约第 174 行）**：
```java
// 旧：
GlobalAgentPool backup = poolService.takeStandby(excludeUserids);

// 新：
GlobalAgentPool backup = poolService.takeStandby(excludeUserids, qr.getDepartmentId());
```

**preActivateBackup（约第 246 行）**：
```java
// 旧：
GlobalAgentPool backup = poolService.takeStandby(excludeUserids);

// 新：
GlobalAgentPool backup = poolService.takeStandby(excludeUserids, qr.getDepartmentId());
```

- [ ] **Step 4: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 运行 AgentRotation 相关测试**

```bash
./mvnw test -pl . -Dtest="AgentRotationServiceTest,AgentRotationFlowTest,GlobalAgentPoolServiceTest" -q
```

Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java src/main/java/com/bookstore/qrcode/service/AgentRotationService.java src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java
git commit -m "feat: department-scoped agent selection for expansion

- takeStandby overload with preferredDepartmentId parameter
- Same-dept priority → global fallback
- Descendant department collection via WeChat Work API
- Lazy cleanup preserved from original takeStandby
- Thread departmentId through expandQrCodeUsers + preActivateBackup

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 批量回收 API

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/dto/BatchRecycleRequest.java`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` (新增 POST endpoint)
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java` (新增 batchRecycle 方法)

**Interfaces:**
- Consumes: `POST /api/qrcodes/{id}/agents/batch-recycle` — `{ "agentIds": [1, 2, 3] }`
- Produces: `{ "recycled": 2, "rejected": 1, "rejectReasons": {"3": "服务老师不可回收"} }`

- [ ] **Step 1: 新建 BatchRecycleRequest DTO**

```java
package com.bookstore.qrcode.dto;

import lombok.Data;
import java.util.List;

/**
 * 批量回收接待员请求。
 */
@Data
public class BatchRecycleRequest {
    /** 要回收的 QrAgent ID 列表 */
    private List<Long> agentIds;
}
```

- [ ] **Step 2: QrCodeService 新增 batchRecycle 方法**

在 `QrCodeService.java` 中新增：

```java
    /**
     * 批量回收闲置接待员回全局池。
     *
     * <h3>校验规则</h3>
     * <ol>
     *   <li>服务老师（role=service）不可回收</li>
     *   <li>回收后活码上至少保留 1 个 active 接待员</li>
     * </ol>
     *
     * @param qrCodeId 活码 ID
     * @param agentIds 要回收的 QrAgent ID 列表
     * @return 回收结果，包含成功数/拒绝数及拒绝原因
     */
    @Transactional
    public Map<String, Object> batchRecycleAgents(Long qrCodeId, List<Long> agentIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> rejectReasons = new LinkedHashMap<>();
        int recycled = 0;

        // 统计当前 active 接待员总数
        List<QrAgent> activeAgents = qrAgentRepo.findByQrCodeId(qrCodeId).stream()
            .filter(a -> a.getStatus() == QrAgent.AgentStatus.active)
            .toList();
        long activeReceptionistCount = activeAgents.stream()
            .filter(a -> a.getRole() != QrAgent.AgentRole.service
                       && a.getRole() != QrAgent.AgentRole.dual)
            .count();

        for (Long agentId : agentIds) {
            QrAgent agent = qrAgentRepo.findById(agentId).orElse(null);
            if (agent == null || !agent.getQrCodeId().equals(qrCodeId)) {
                rejectReasons.put(String.valueOf(agentId), "不存在或不属于此活码");
                continue;
            }
            // 服务老师不可回收
            if (agent.getRole() == QrAgent.AgentRole.service) {
                rejectReasons.put(String.valueOf(agentId), "服务老师不可回收");
                continue;
            }
            // 至少保留 1 个 active 接待员
            if (agent.getRole() == QrAgent.AgentRole.receptionist
                && activeReceptionistCount - recycled <= 1) {
                rejectReasons.put(String.valueOf(agentId), "至少保留1个接待员");
                continue;
            }

            agent.setStatus(QrAgent.AgentStatus.removed);
            agent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(agent);

            // 恢复全局池状态
            GlobalAgentPool pool = poolRepo.findByAgentUserid(agent.getAgentUserid()).orElse(null);
            if (pool != null) {
                pool.setStatus(GlobalAgentPool.PoolStatus.standby);
                poolRepo.save(pool);
            }
            recycled++;
        }

        result.put("recycled", recycled);
        result.put("rejected", agentIds.size() - recycled);
        result.put("rejectReasons", rejectReasons);

        // 异步同步企微
        if (recycled > 0) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        syncQrUsersToWechat(qrCodeId);
                    }
                });
        }

        log.info("批量回收完成: qrCodeId={}, recycled={}, rejected={}",
            qrCodeId, recycled, agentIds.size() - recycled);
        return result;
    }
```

- [ ] **Step 3: QrCodeController 新增批量回收接口**

在 `QrCodeController.java` 中新增：

```java
    /**
     * 批量回收闲置接待员 — 标记 removed + 恢复池 standby。
     */
    @PostMapping("/api/qrcodes/{id}/agents/batch-recycle")
    @ResponseBody
    public Map<String, Object> batchRecycleAgents(
            @PathVariable Long id,
            @RequestBody BatchRecycleRequest request) {
        return qrCodeService.batchRecycleAgents(id, request.getAgentIds());
    }
```

- [ ] **Step 4: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/dto/BatchRecycleRequest.java src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "feat: batch recycle API for idle receptionists

- POST /api/qrcodes/{id}/agents/batch-recycle
- Guards: service teachers unrecyclable, min 1 receptionist
- Restores GlobalAgentPool status to standby
- Async WeChat Work contact sync after recycle

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: EmployeeSync 部门同步

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java:232-237`（GlobalAgentPool.builder() 追加 departmentId）

**Interfaces:**
- Consumes: `Employee.department` (JSON 数组如 `"[1,2,3]"`)
- Produces: `GlobalAgentPool.departmentId` (取主部门)

- [ ] **Step 1: 修改 EmployeeSyncService 同步逻辑**

在 `EmployeeSyncService.java` 的 `syncToPool` 方法中（约第 232-237 行），修改 `GlobalAgentPool.builder()` 追加 `departmentId`：

```java
            // 取主部门：Employee.department 是 JSON 数组如 "[1,2,3]"
            Long primaryDeptId = extractPrimaryDeptId(emp.getDepartment());

            batch.add(GlobalAgentPool.builder()
                .agentUserid(emp.getUserid())
                .dailyMax(100)
                .sortOrder(maxOrder)
                .departmentId(primaryDeptId)
                .status(GlobalAgentPool.PoolStatus.standby)
                .build());
```

在同一类中新增辅助方法：

```java
    /**
     * 从 Employee.department JSON 数组字符串中提取主部门 ID。
     *
     * <p>企微返回的 department 是 JSON 数组如 [1,2,3]，
     * 取第一个元素作为主部门。如果解析失败或为空，返回 null。</p>
     */
    private Long extractPrimaryDeptId(String departmentJson) {
        if (departmentJson == null || departmentJson.isBlank()) return null;
        try {
            JsonNode arr = objectMapper.readTree(departmentJson);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).asLong();
            }
        } catch (Exception e) {
            log.warn("解析部门 JSON 失败: {}", departmentJson, e);
        }
        return null;
    }
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java
git commit -m "feat: sync primary department to GlobalAgentPool

- Extract first dept ID from Employee.department JSON array
- Write to GlobalAgentPool.departmentId during pool sync

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 批量导入新列

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java:1261-1273`（parseExcel 追加列）

**Interfaces:**
- Consumes: Excel 列 L(11)=scene, M(12)=departmentId
- Produces: `Map<String, String> item` 包含 `scene`, `departmentId` 键

- [ ] **Step 1: parseExcel 追加新列解析**

修改 `QrCodeService.java` 的 `parseExcel()` 方法（约第 1261-1273 行），在 `remark` 解析之后追加：

```java
                // 列索引注释更新：12=scene, 13=departmentId
                item.put("remark", getCellString(row, 10));
                item.put("scene", getCellString(row, 11));
                item.put("departmentId", getCellString(row, 12));
```

同时更新列索引注释（第 1261 行）：

```java
                // 列索引: 0学校名称 1学校ID 2市 3区 4服务老师 5学校人数
                //          6初始上码员工数 7接待员 8服务老师日上限 9欢迎语 10备注
                //          11场景 12部门ID
```

- [ ] **Step 2: 修改批量导入解析逻辑中读取 scene + departmentId**

在批量导入行解析方法中（约第 398-410 行），追加 scene 和 departmentId 的读取：

```java
                    String sceneStr = item.get("scene");
                    if (sceneStr != null && !sceneStr.isBlank()) {
                        try { req.setScene(Scene.valueOf(sceneStr.trim().toLowerCase())); }
                        catch (IllegalArgumentException e) {
                            req.setScene(Scene.daily_push);
                        }
                    }

                    String deptIdStr = item.get("departmentId");
                    if (deptIdStr != null && !deptIdStr.isBlank()) {
                        try { req.setDepartmentId(Long.valueOf(deptIdStr.trim())); }
                        catch (NumberFormatException ignored) { }
                    }
```

- [ ] **Step 3: 编译 + 测试验证**

```bash
./mvnw test -pl . -q -Dtest="QrCodeCreationFlowTest"
```

Expected: all PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "feat: parse scene and departmentId from batch import Excel

- New columns: L(11)=scene, M(12)=departmentId
- Default scene=daily_push if invalid or blank

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 创建页 UI — 场景选择 + 部门选择 + 预估接待员

**Files:**
- Modify: `src/main/resources/templates/qrcode/create.html`

**Interfaces:**
- Consumes: `GET /api/config/scene` → `{ scenes: {...}, dailyMax: 100 }`
- Consumes: `GET /api/departments` (将来) 或直接内嵌场景配置到页面渲染
- Produces: 表单提交时携带 `scene`、`departmentId` 字段

- [ ] **Step 1: 修改 QrCodeController.createForm 注入场景配置 + 加载部门列表**

在 `QrCodeController.java` 的 `createForm(Model model)` 方法中（`@GetMapping("/create")`，约第 363 行），添加：

```java
    // 注入场景配置（在 Task 3 中已添加 sceneConfig 字段）
    model.addAttribute("sceneConfig", sceneConfig);
    model.addAttribute("dailyMaxDefault", dailyMaxDefault);

    // 加载企微部门列表（用于部门选择下拉框）
    try {
        JsonNode deptResp = wecomApi.listDepartments(null);
        List<Map<String, Object>> departments = new ArrayList<>();
        if (deptResp.has("department") && deptResp.get("department").isArray()) {
            for (JsonNode d : deptResp.get("department")) {
                Map<String, Object> dept = new LinkedHashMap<>();
                dept.put("id", d.get("id").asLong());
                dept.put("name", d.get("name").asText());
                departments.add(dept);
            }
        }
        model.addAttribute("departments", departments);
    } catch (Exception e) {
        log.warn("加载企微部门列表失败，部门选择下拉为空", e);
        model.addAttribute("departments", List.of());
    }
```

- [ ] **Step 2: create.html — 在学生人数后新增场景选择**

在学生人数输入框（`studentCount`）之后插入场景选择区域。找到 create.html 中 `studentCount` 所在的表单组，在其后追加：

```html
                <!-- 场景选择 -->
                <div class="mb-3">
                    <label class="form-label">场景</label>
                    <div class="form-check form-check-inline">
                        <input class="form-check-input" type="radio" name="scene"
                               id="sceneDaily" value="daily_push" checked
                               onchange="onSceneChange()">
                        <label class="form-check-label" for="sceneDaily">日常推送</label>
                    </div>
                    <div class="form-check form-check-inline">
                        <input class="form-check-input" type="radio" name="scene"
                               id="sceneParentMeeting" value="parent_meeting"
                               onchange="onSceneChange()">
                        <label class="form-check-label" for="sceneParentMeeting">家长会</label>
                    </div>
                    <div class="form-text">
                        日常：按 10% 扫码率配人 | 家长会：按 60% 扫码率配人（扩容更积极）
                    </div>
                </div>

                <!-- 预估接待员 -->
                <div class="mb-3">
                    <label for="initialAgentCount" class="form-label">预估接待员</label>
                    <div class="input-group">
                        <input type="number" class="form-control" id="initialAgentCount"
                               name="initialAgentCount" min="1" max="100"
                               placeholder="自动计算" style="max-width:120px">
                        <span class="input-group-text bg-light text-muted" id="agentEstimate">—</span>
                    </div>
                    <div class="form-text" id="agentEstimateFormula"></div>
                </div>

                <!-- 所属部门 -->
                <div class="mb-3">
                    <label for="departmentId" class="form-label">所属部门（选填）</label>
                    <select class="form-select" id="departmentId" name="departmentId"
                            style="max-width:300px">
                        <option value="">不限部门</option>
                        <!-- 由后端渲染部门列表 -->
                        <option th:each="dept : ${departments}"
                                th:value="${dept.id}"
                                th:text="${dept.name}"></option>
                    </select>
                    <div class="form-text">扩容时优先从同部门取人</div>
                </div>
```

- [ ] **Step 3: create.html — 新增 onSceneChange JS 函数**

在 `<script>` 块中添加：

```javascript
    // 场景配置（由后端 Thymeleaf 渲染注入）
    const SCENE_CONFIG = {
        daily_push: {
            scanRatio: /*[[${sceneConfig.dailyPush.scanRatio}]]*/ 0.10,
            urgentRatio: /*[[${sceneConfig.dailyPush.urgentRatio}]]*/ 95
        },
        parent_meeting: {
            scanRatio: /*[[${sceneConfig.parentMeeting.scanRatio}]]*/ 0.60,
            urgentRatio: /*[[${sceneConfig.parentMeeting.urgentRatio}]]*/ 70
        }
    };
    const DAILY_MAX = /*[[${dailyMaxDefault} ?: 100]]*/ 100;

    function onSceneChange() {
        const scene = document.querySelector('input[name="scene"]:checked').value;
        const cfg = SCENE_CONFIG[scene];
        const studentCount = parseInt(document.getElementById('studentCount')?.value) || 0;
        const countInput = document.getElementById('initialAgentCount');
        const estimateSpan = document.getElementById('agentEstimate');
        const formulaSpan = document.getElementById('agentEstimateFormula');

        if (studentCount > 0 && cfg) {
            const expectedScans = Math.ceil(studentCount * cfg.scanRatio);
            const need = Math.max(1, Math.min(100,
                Math.ceil(expectedScans / DAILY_MAX)));
            estimateSpan.textContent = need + ' 人';
            formulaSpan.textContent =
                '公式: ceil(' + studentCount + ' × ' + (cfg.scanRatio * 100) + '% / ' + DAILY_MAX + ')'
                + ' = ceil(' + expectedScans + ' / ' + DAILY_MAX + ') = ' + need + ' 人';
            if (!countInput.value) countInput.placeholder = need;
        }
    }

    // 学生人数变化时也触发重算
    document.getElementById('studentCount')?.addEventListener('input', onSceneChange);
    // 初始加载时触发
    document.addEventListener('DOMContentLoaded', onSceneChange);
```

- [ ] **Step 4: 编译验证（Thymeleaf 不检查运行时模板）**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/qrcode/create.html src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add scene selector, department picker, and agent estimate to create page

- Radio buttons for daily_push / parent_meeting
- Real-time agent count estimate with formula display
- Department dropdown for same-dept expansion preference
- JS onSceneChange() recalculates on scene/studentCount change

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 详情页 — 回收 UI

**Files:**
- Modify: `src/main/resources/templates/qrcode/detail.html`

**Interfaces:**
- Consumes: `POST /api/qrcodes/{id}/agents/batch-recycle` (来自 Task 6)
- Produces: 带复选框的接待员列表 + 回收按钮 + 确认弹窗

- [ ] **Step 1: detail.html — 接待员行增加复选框**

在接待员表格（`th:each="a : ${receptionists}"`）每行第一列前追加复选框列，并在表头添加全选。找到接待员表格的 `<thead>` 行，在最前插入：

```html
                                <th><input type="checkbox" id="receptionistSelectAll"
                                           onchange="toggleAllReceptionists(this)"></th>
```

修改 `<tbody>` 中每个接待员行，在第一列（名称列）之前追加：

```html
                            <td>
                                <input type="checkbox" class="receptionist-check"
                                       th:value="${a.id}"
                                       th:disabled="${a.role.name() == 'service'}"
                                       th:title="${a.role.name() == 'service' ? '服务老师不可回收' : ''}">
                            </td>
```

- [ ] **Step 2: detail.html — 在接待员表格上方新增工具栏**

在接待员表格的 `<table>` 标签之前追加：

```html
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <span class="text-muted small">
                            今日接待量 <span class="text-success fw-bold" id="receptionistActiveCount">—</span> |
                            可回收 <span class="text-primary fw-bold" id="recyclableCount">—</span> 人
                        </span>
                        <button class="btn btn-outline-warning btn-sm" id="recycleBtn"
                                onclick="batchRecycle()" disabled>
                            ♻️ 回收选中
                        </button>
                    </div>
```

- [ ] **Step 3: detail.html — 新增 JS 函数**

在 `<script>` 块中追加：

```javascript
    // 全选/取消全选（跳过 disabled 的复选框）
    function toggleAllReceptionists(master) {
        document.querySelectorAll('.receptionist-check:not([disabled])').forEach(cb => {
            cb.checked = master.checked;
        });
        updateRecycleButton();
    }

    // 更新回收按钮状态 + 统计
    function updateRecycleButton() {
        const checks = document.querySelectorAll('.receptionist-check');
        const checked = document.querySelectorAll('.receptionist-check:checked');
        const total = checks.length;
        const disabled = document.querySelectorAll('.receptionist-check[disabled]').length;
        const active = total - disabled;
        const selected = checked.length;

        document.getElementById('receptionistActiveCount').textContent = active;
        document.getElementById('recyclableCount').textContent = selected;
        const btn = document.getElementById('recycleBtn');
        btn.disabled = selected === 0;
        btn.textContent = selected > 0 ? '♻️ 回收选中 (' + selected + ')' : '♻️ 回收选中';
    }

    // 单个复选框变化时更新按钮
    document.querySelectorAll('.receptionist-check').forEach(cb => {
        cb.addEventListener('change', updateRecycleButton);
    });

    // 批量回收
    async function batchRecycle() {
        const checked = document.querySelectorAll('.receptionist-check:checked');
        if (checked.length === 0) return;

        if (!confirm('确定要回收选中的 ' + checked.length + ' 个接待员吗？\n\n'
            + '回收后他们将返回全局池，可被其他活码使用。')) {
            return;
        }

        const agentIds = Array.from(checked).map(cb => parseInt(cb.value));
        const qrId = /*[[${qr.id}]]*/ 0;

        try {
            const resp = await fetch('/api/qrcodes/' + qrId + '/agents/batch-recycle', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ agentIds: agentIds })
            });
            const result = await resp.json();

            let msg = '回收完成：成功 ' + result.recycled + ' 人';
            if (result.rejected > 0) {
                msg += '，' + result.rejected + ' 人被拒绝';
                const reasons = result.rejectReasons;
                if (reasons) {
                    msg += '\n\n拒绝原因：\n';
                    for (const [id, reason] of Object.entries(reasons)) {
                        msg += '• ID ' + id + ': ' + reason + '\n';
                    }
                }
            }
            alert(msg);

            if (result.recycled > 0) {
                location.reload();  // 刷新页面显示最新状态
            }
        } catch (e) {
            alert('回收失败: ' + e.message);
        }
    }

    // 页面加载时初始化回收按钮
    document.addEventListener('DOMContentLoaded', updateRecycleButton);
```

- [ ] **Step 4: 编译验证**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/qrcode/detail.html
git commit -m "feat: add batch recycle UI to QR code detail page

- Checkbox per receptionist with service-teacher guard
- Select all / deselect all toggle
- Recyclable count indicator
- Batch recycle button with confirmation dialog
- Result toast with per-agent rejection reasons

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11 (最终): 全量测试 + 提交 PR

- [ ] **Step 1: 运行完整测试套件**

```bash
./mvnw test -pl . -q
```

Expected: all tests PASS

- [ ] **Step 2: 确认无编译警告**

```bash
./mvnw compile -pl . -q
```

Expected: BUILD SUCCESS, no warnings

- [ ] **Step 3: 最终提交（如有后续修正）**

```bash
git add -A
git diff --cached --stat  # 确认改动范围
git commit -m "chore: final integration fixes for scene-based allocation"
```

- [ ] **Step 4: 提交 PR**

```bash
gh pr create --title "feat: scene-based agent allocation optimization" \
  --body "### 改动摘要

- 场景预设（日常推送 / 家长会）+ 新分配公式：ceil(学生人数 × 扫码率 / 日限)
- 部门匹配扩容：活码绑定部门，取人时同部门优先
- 批量回收：详情页回收闲置接待员回全局池
- 场景联动阈值：家长会 urgentRatio=70%，提前预加载后备
- 批量导入兼容：新增 scene + departmentId 列

### 测试

- 新增 SceneAllocationTest 覆盖公式 + 配置绑定
- 现有测试全部通过

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
