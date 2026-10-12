# 县区码建码入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为后台操作员提供独立的「新建县区码」入口，自动生成 `county:市:区县` schoolId、自动 find-or-create「县区码默认模板」，消除手动改 schoolId / 配 tag_mapping 的魔法约定。

**Architecture:** 复用现有 `QrCodeService.create` 建码链路（县区码无服务老师，`buildContactWayJson` 回退到接待员）；新增 `FormTemplateService.ensureCountyTemplate` 幂等模板；新增 `QrCodeController` 的 `GET/POST /qrcodes/create-county` 端点与 `qrcode/create-county.html` 页面；列表页加「县区码」徽标。

**Tech Stack:** Spring Boot 3.2.5 + Spring Data JPA + Thymeleaf + Flyway(prod)/schema-test.sql(test) + H2(test, MODE=MySQL) + embedded-redis 1.4.3。

**Spec:** `docs/superpowers/specs/2026-08-26-county-qrcode-creation-entry-design.md`

## Global Constraints

- **工作目录**：本特性在 worktree `D:\ClaudeCode\HuoMa\.claude\worktrees\district-qrcode-school-selection`（分支 `worktree-district-qrcode-school-selection`）上开发，所有 `git` 与 `mvn` 命令都在该目录下执行。
- **mvn 路径**：`mvn` 不在 PATH，统一用 `/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn`（bash 语法，Windows 路径）。运行测试：`cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=<TestClass> test`。
- **县区码 schoolId 格式**：`"county:" + 市 + ":" + 县区`（如 `county:白银市:白银区`），检测用 `SchoolSelectionService.COUNTY_PREFIX`（值 `"county:"`，已存在）。
- **默认模板**：`name = "县区码默认模板"`，`fields = "[]"`，`tagMapping = "{\"grade\":\"tag\",\"class\":\"tag\"}"`。
- **schoolId 长度**：企微 `state` 上限约 30 字符，POST 校验 `schoolId.length() <= 30`。
- **无 JPA relationship annotations**（CLAUDE.md）：所有 FK 用显式 ID 字段，本计划不引入任何 `@ManyToOne` 等注解。
- **测试环境 schema**：`@ActiveProfiles("test")` + H2 `MODE=MySQL`，schema 来自 `src/test/resources/schema-test.sql`（手工表，非 Flyway）。Flyway 迁移（V15）只影响 prod MySQL。

---

### Task 1: 县区码默认模板 find-or-create（并发安全）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/FormTemplateRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/FormTemplateService.java`
- Create: `src/main/resources/db/migration/V15__form_template_name_unique.sql`
- Test: `src/test/java/com/bookstore/qrcode/service/FormTemplateServiceTest.java`

**Interfaces:**
- Consumes: `FormTemplateService.create(name, description, subtitle, cardTitle, cardDesc, cardPicUrl, fields, tagMapping, remarkTemplate)`（已存在，返回 `FormTemplate`）。
- Produces: `FormTemplateService.ensureCountyTemplate()` → `FormTemplate`（幂等）；`FormTemplateService.COUNTY_TEMPLATE_NAME`（`String` 常量）；`FormTemplateRepository.findByName(String)` → `Optional<FormTemplate>`。

- [ ] **Step 1: 写失败测试**

Create `src/test/java/com/bookstore/qrcode/service/FormTemplateServiceTest.java`:

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(FormTemplateService.class)
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("FormTemplateService 县区码默认模板")
class FormTemplateServiceTest {

    @Autowired private FormTemplateService service;
    @Autowired private FormTemplateRepository templateRepo;

    @Test
    void ensureCountyTemplate_幂等_两次调用返回同一条记录() {
        FormTemplate first = service.ensureCountyTemplate();
        FormTemplate second = service.ensureCountyTemplate();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(templateRepo.findAll().stream()
                .filter(t -> "县区码默认模板".equals(t.getName())).count()).isEqualTo(1);
    }

    @Test
    void ensureCountyTemplate_fields与tagMapping正确() {
        FormTemplate t = service.ensureCountyTemplate();
        assertThat(t.getFields()).isEqualTo("[]");
        assertThat(t.getTagMapping()).isEqualTo("{\"grade\":\"tag\",\"class\":\"tag\"}");
    }

    @Test
    void ensureCountyTemplate_预置同名后不新建() {
        FormTemplate existing = templateRepo.save(FormTemplate.builder()
                .name("县区码默认模板").fields("[]").tagMapping("{}").build());
        FormTemplate got = service.ensureCountyTemplate();
        assertThat(got.getId()).isEqualTo(existing.getId());
        assertThat(templateRepo.findAll().stream()
                .filter(t -> "县区码默认模板".equals(t.getName())).count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=FormTemplateServiceTest test`
Expected: 编译失败 — `ensureCountyTemplate()` 方法不存在、`findByName` 方法不存在。

- [ ] **Step 3: 加 findByName 派生查询**

In `FormTemplateRepository.java`（在 `findAllByOrderByName` 之后）：

```java
import java.util.Optional;

Optional<FormTemplate> findByName(String name);
```

- [ ] **Step 4: 实现 ensureCountyTemplate**

In `FormTemplateService.java`，在类体顶部加常量（`listAll()` 之前），并在 `create` 方法之后加 `ensureCountyTemplate`：

```java
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Optional;

/** 县区码默认表单模板的固定名称，find-or-create 锚点 */
public static final String COUNTY_TEMPLATE_NAME = "县区码默认模板";

/**
 * 幂等获取「县区码默认模板」：不存在则创建。
 * 并发安全：唯一索引（V15）兜底，insert 冲突后重查。
 */
@Transactional
public FormTemplate ensureCountyTemplate() {
    return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
        .orElseGet(() -> {
            try {
                return create(COUNTY_TEMPLATE_NAME, "县区码默认收集模板",
                    null, null, null, null,
                    "[]", "{\"grade\":\"tag\",\"class\":\"tag\"}", null);
            } catch (DataIntegrityViolationException e) {
                return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
                    .orElseThrow(() -> new RuntimeException("县区码默认模板创建失败", e));
            }
        });
}
```

（`FormTemplateService` 已有 `import com.bookstore.qrcode.repository.FormTemplateRepository;` 与 `import java.util.List;`，新增两个 import 分别补充；若 `Optional` 未用到可省略，但 `DataIntegrityViolationException` 必须 import。）

- [ ] **Step 5: 建 V15 迁移**

Create `src/main/resources/db/migration/V15__form_template_name_unique.sql`:

```sql
-- V15: form_template.name 唯一约束
-- 并发 ensureCountyTemplate 的 find-or-create 竞态会插入重复 name，
-- 导致 findByName 抛 IncorrectResultSizeDataAccessException。
-- 部署前诊断：
--   SELECT name, COUNT(*) FROM form_template GROUP BY name HAVING COUNT(*) > 1;

-- Step 1: 删除重复 name（保留最小 id 幸存行）
-- form_template 的引用（qr_code.form_template_id / qr_code_group.default_form_template_id /
-- school_category.default_form_template_id）无数据库级 FK 约束，无需重定向。
DELETE ft FROM form_template ft
JOIN (SELECT name, MIN(id) AS survivor_id FROM form_template GROUP BY name HAVING COUNT(*) > 1) ds
  ON ft.name = ds.name AND ft.id != ds.survivor_id;

-- Step 2: 建唯一索引（idempotent）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'form_template' AND INDEX_NAME = 'uk_form_template_name') = 0,
    'ALTER TABLE form_template ADD UNIQUE INDEX uk_form_template_name (name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=FormTemplateServiceTest test`
Expected: 3 个测试全部 PASS。

- [ ] **Step 7: Commit**

```bash
cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection"
git add src/main/java/com/bookstore/qrcode/repository/FormTemplateRepository.java src/main/java/com/bookstore/qrcode/service/FormTemplateService.java src/main/resources/db/migration/V15__form_template_name_unique.sql src/test/java/com/bookstore/qrcode/service/FormTemplateServiceTest.java
git commit -m "feat: 县区码默认模板 find-or-create（并发安全）"
```

---

### Task 2: QrCodeService.create 跳过县区码 school 同步

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`（约 297-315 行）
- Test: `src/test/java/com/bookstore/qrcode/integration/QrCodeCreationFlowTest.java`

**Interfaces:**
- Consumes: `SchoolSelectionService.COUNTY_PREFIX`（`public static final String`，已存在，同包）。
- Produces: 无新公共接口；仅改变 `QrCodeService.create(QrCodeCreateRequest)` 对 `schoolId` 以 `county:` 开头时的副作用（不再写 `school` 表）。

- [ ] **Step 1: 写失败测试**

在 `QrCodeCreationFlowTest.java` 顶部 `@Autowired` 区加 `SchoolRepository`，`setUp` 加清理，并新增测试方法：

```java
// import 区已有 com.bookstore.qrcode.repository.*;（通配），无需改 import。

@Autowired private SchoolRepository schoolRepo;

@BeforeEach
void setUp() {
    qrAgentRepo.deleteAll();
    poolRepo.deleteAll();
    qrCodeRepo.deleteAll();
    agentRepo.deleteAll();
    schoolRepo.deleteAll();  // 新增
}
```

新增测试方法（放在类内其它 `@Test` 之后）：

```java
@Test
@DisplayName("建县区码：schoolId=county:市:区县，接待员绑定，跳过 school 同步")
void shouldCreateCountyQrCodeAndSkipSchoolSync() {
    QrCodeCreateRequest req = new QrCodeCreateRequest();
    req.setSchoolName("白银区");
    req.setSchoolId("county:白银市:白银区");
    req.setRegionCity("白银市");
    req.setRegionDistrict("白银区");
    req.setReceptionistUserid("agent3");
    req.setInitialAgentUserids("agent3");
    req.setScene(Scene.daily_push);

    QrCode created = qrCodeService.create(req);

    assertThat(created.getSchoolId()).isEqualTo("county:白银市:白银区");
    assertThat(created.getSchoolName()).isEqualTo("白银区");
    assertThat(qrAgentRepo.findByQrCodeId(created.getId()))
        .extracting(QrAgent::getAgentUserid).contains("agent3");
    // school 表无 county 记录（school 同步被跳过）
    assertThat(schoolRepo.findBySchoolIdAndDeletedFalse("county:白银市:白银区")).isEmpty();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=QrCodeCreationFlowTest test`
Expected: 新增测试 FAIL — `schoolRepo.findBySchoolIdAndDeletedFalse("county:白银市:白银区")` 非空（当前 `create` 会写入 `school` 表）。

- [ ] **Step 3: 加 county 前缀 guard**

In `QrCodeService.java`，把约 298 行的 `if (req.getSchoolId() != null) {` 改为：

```java
if (req.getSchoolId() != null
    && !req.getSchoolId().startsWith(SchoolSelectionService.COUNTY_PREFIX)) {
```

（`SchoolSelectionService` 与 `QrCodeService` 同包 `com.bookstore.qrcode.service`，无需 import。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=QrCodeCreationFlowTest test`
Expected: 全类测试 PASS（含新增测试）。

- [ ] **Step 5: Commit**

```bash
cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection"
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java src/test/java/com/bookstore/qrcode/integration/QrCodeCreationFlowTest.java
git commit -m "feat: 建县区码跳过 school 表同步"
```

---

### Task 3: QrCodeController 县区码建码端点

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`
- Test: `src/test/java/com/bookstore/qrcode/controller/QrCodeControllerTest.java`

**Interfaces:**
- Consumes: `FormTemplateService.ensureCountyTemplate()`（Task 1）、`FormTemplateService`（需注入）、`FormTemplateRepository.findAllByOrderByName()`（已注入）、`QrCodeService.create(QrCodeCreateRequest)`（已注入）。
- Produces: `GET /qrcodes/create-county`（返回视图 `qrcode/create-county`，模型含 `userList`/`cityOptions`/`districtOptions`/`formTemplates`/`defaultTemplateId`）；`POST /qrcodes/create-county`（参数 `city`/`district`/`receptionistUserid`/`formTemplateId?`/`studentCount?`/`scene?`，AJAX 返回 `{"success":...}` 或 redirect）。

- [ ] **Step 1: 注入 FormTemplateService**

In `QrCodeController.java`，在字段区末尾（`sceneConfig` 之后）加：

```java
import com.bookstore.qrcode.service.FormTemplateService;

private final FormTemplateService formTemplateService;
```

（`FormTemplateService` 与控制器同项目 `com.bookstore.qrcode.service`，加 import；`@RequiredArgsConstructor` 自动纳入构造器。）

- [ ] **Step 2: 提取 loadRegionOptions 私有方法**

把 `createForm` 中「标签缓存 + city/district/school 下拉提取」逻辑（现约 418-485 行，从 `// ---- 调用企微接口获取全部标签` 注释起，到 `model.addAttribute("schoolOptions", schoolOptions);` 及对应的 catch 块结束）抽出为一个私有方法。`createForm` 中该段替换为 `loadRegionOptions(model);`。

新私有方法（放在 `buildUserList` 之前）：

```java
private void loadRegionOptions(Model model) {
    JsonNode wecomTagResp = null;
    boolean needApiCall = lastTagSyncTime == null
        || java.time.Duration.between(lastTagSyncTime, java.time.LocalDateTime.now())
            .toMinutes() >= TAG_CACHE_MINUTES;

    if (needApiCall) {
        try {
            wecomTagResp = wecomApiClient.getCorpTagList();
            tagService.syncTagsFromWecom(wecomTagResp);
            var extracted = extractOptionsFromGroups(wecomTagResp);
            cachedCityOptions = extracted.get("city");
            cachedDistrictOptions = extracted.get("district");
            cachedSchoolOptions = extracted.get("school");
            lastTagSyncTime = java.time.LocalDateTime.now();
        } catch (Exception e) {
            log.warn("企微标签同步失败，使用缓存数据: {}", e.getMessage());
        }
    } else {
        log.debug("标签缓存未过期，跳过企微接口调用 (上次同步: {})", lastTagSyncTime);
    }

    try {
        List<String> cityOptions = cachedCityOptions != null
            ? new ArrayList<>(cachedCityOptions) : new ArrayList<>();
        List<String> districtOptions = cachedDistrictOptions != null
            ? new ArrayList<>(cachedDistrictOptions) : new ArrayList<>();
        List<String> schoolOptions = cachedSchoolOptions != null
            ? new ArrayList<>(cachedSchoolOptions) : new ArrayList<>();

        if (cityOptions.isEmpty() && districtOptions.isEmpty() && schoolOptions.isEmpty()) {
            log.info("缓存为空，使用 DB 标签命名规则兜底");
            List<Tag> allTags = tagRepo.findAll();
            for (Tag t : allTags) {
                String name = t.getName();
                if (name.endsWith("市") || name.endsWith("州") || name.endsWith("盟")) {
                    cityOptions.add(name);
                } else if (name.endsWith("区") || name.endsWith("县")
                        || name.endsWith("旗") || name.endsWith("乡")) {
                    districtOptions.add(name);
                } else if (isSchoolName(name)) {
                    schoolOptions.add(name);
                }
            }
            Collections.sort(cityOptions);
            Collections.sort(districtOptions);
            Collections.sort(schoolOptions);
        }

        log.info("下拉选项统计: city={}, district={}, school={}",
            cityOptions.size(), districtOptions.size(), schoolOptions.size());
        model.addAttribute("cityOptions", cityOptions);
        model.addAttribute("districtOptions", districtOptions);
        model.addAttribute("schoolOptions", schoolOptions);
    } catch (Exception e) {
        log.warn("加载下拉选项数据失败: {}", e.getMessage());
        model.addAttribute("cityOptions", List.of());
        model.addAttribute("districtOptions", List.of());
        model.addAttribute("schoolOptions", List.of());
    }
}
```

（`createForm` 剩余部分保留 `suggestedSchoolId`、`userList`、`categories`、部门加载不变；仅替换 418-485 段为 `loadRegionOptions(model);`。）

- [ ] **Step 3: 加 GET /create-county**

在 `@GetMapping("/create")` 方法之后加：

```java
@GetMapping("/create-county")
public String createCountyForm(Model model) {
    model.addAttribute("userList", buildUserList());
    loadRegionOptions(model);
    model.addAttribute("formTemplates", formTemplateRepo.findAllByOrderByName());
    FormTemplate defaultTpl = formTemplateService.ensureCountyTemplate();
    model.addAttribute("defaultTemplateId", defaultTpl.getId());
    return "qrcode/create-county";
}
```

（`FormTemplate` 已 import（`com.bookstore.qrcode.entity.FormTemplate`？若未 import 需补 `import com.bookstore.qrcode.entity.FormTemplate;`。））

- [ ] **Step 4: 加 POST /create-county**

在 `@PostMapping("/create")` 方法之后加：

```java
@PostMapping("/create-county")
public Object createCounty(@RequestParam String city,
                           @RequestParam String district,
                           @RequestParam String receptionistUserid,
                           @RequestParam(required = false) Long formTemplateId,
                           @RequestParam(required = false) Integer studentCount,
                           @RequestParam(required = false) Scene scene,
                           RedirectAttributes redirect,
                           HttpServletRequest request) {
    boolean isAjax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    try {
        if (city == null || city.isBlank() || district == null || district.isBlank()
            || receptionistUserid == null || receptionistUserid.isBlank()) {
            throw new RuntimeException("市州、县区、县区接待员均不能为空");
        }
        String schoolId = "county:" + city.trim() + ":" + district.trim();
        if (schoolId.length() > 30) {
            throw new RuntimeException("市州+县区名称过长，无法生成县区码标识");
        }
        if (formTemplateId == null) {
            formTemplateId = formTemplateService.ensureCountyTemplate().getId();
        }
        QrCodeCreateRequest req = new QrCodeCreateRequest();
        req.setSchoolName(district.trim());
        req.setSchoolId(schoolId);
        req.setRegionCity(city.trim());
        req.setRegionDistrict(district.trim());
        req.setReceptionistUserid(receptionistUserid.trim());
        req.setFormTemplateId(formTemplateId);
        req.setStudentCount(studentCount);
        req.setScene(scene != null ? scene : Scene.daily_push);
        QrCode qr = qrCodeService.create(req);
        operationLogService.log(getOperator(), "create", "qrcode",
            qr.getId().toString(), "创建县区码：" + qr.getSchoolName());
        if (isAjax) {
            return ResponseEntity.ok(Map.of("success", true, "message", "县区码创建成功"));
        }
        redirect.addFlashAttribute("message", "县区码创建成功");
        return "redirect:/qrcodes";
    } catch (Exception e) {
        log.error("创建县区码失败", e);
        if (isAjax) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
        redirect.addFlashAttribute("error", e.getMessage());
        return "redirect:/qrcodes";
    }
}
```

（`QrCodeCreateRequest`、`Scene`、`QrCode`、`Map`、`ResponseEntity`、`RedirectAttributes`、`HttpServletRequest` 均已 import。）

- [ ] **Step 5: 更新控制器测试构造器并加 POST 测试**

In `QrCodeControllerTest.java`：将 `qrCodeService` 与 `formTemplateService` 从局部变量提升为字段，并在 `new QrCodeController(...)` 末尾追加 `mock(FormTemplateService.class)`：

```java
// 字段区新增
private FormTemplateService formTemplateService;

// setUp 内：
qrCodeService = mock(QrCodeService.class);
formTemplateService = mock(FormTemplateService.class);
// ... new QrCodeController(...) 最后一个参数改为 formTemplateService（替代原 mock(SceneConfigProperties.class) 之后的顺序）
QrCodeController controller = new QrCodeController(
        qrCodeService,
        mock(WecomApiClient.class),
        mock(QrAgentRepository.class),
        mock(GlobalAgentPoolRepository.class),
        mock(CustomerRepository.class),
        qrCodeRepo,
        mock(QrRotateLogRepository.class),
        mock(TagRepository.class),
        qrImageService,
        mock(TagService.class),
        mock(EmployeeRepository.class),
        mock(CustomerTransferRepository.class),
        mock(EmployeeSyncService.class),
        mock(FormTemplateRepository.class),
        mock(QrCodeGroupRepository.class),
        mock(SchoolCategoryRepository.class),
        mock(SchoolRepository.class),
        mock(SystemConfigRepository.class),
        mock(StringRedisTemplate.class),
        mock(OperationLogService.class),
        mock(ObjectMapper.class),
        mock(SceneConfigProperties.class),
        formTemplateService);
```

新增 import：`com.bookstore.qrcode.entity.FormTemplate;`、`com.bookstore.qrcode.service.FormTemplateService;`、`static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;`、`static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;`。

新增测试方法：

```java
@Test
@DisplayName("POST /qrcodes/create-county — 成功并自动命名 schoolId")
void shouldCreateCountyCode() throws Exception {
    FormTemplate tpl = FormTemplate.builder().id(10L).name("县区码默认模板").fields("[]").tagMapping("{}").build();
    when(formTemplateService.ensureCountyTemplate()).thenReturn(tpl);
    when(qrCodeService.create(any())).thenReturn(QrCode.builder().id(99L).schoolName("白银区").build());

    mockMvc.perform(post("/qrcodes/create-county")
            .param("city", "白银市")
            .param("district", "白银区")
            .param("receptionistUserid", "agent3")
            .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    ArgumentCaptor<com.bookstore.qrcode.dto.QrCodeCreateRequest> captor =
        ArgumentCaptor.forClass(com.bookstore.qrcode.dto.QrCodeCreateRequest.class);
    verify(qrCodeService).create(captor.capture());
    assertThat(captor.getValue().getSchoolId()).isEqualTo("county:白银市:白银区");
    assertThat(captor.getValue().getSchoolName()).isEqualTo("白银区");
    assertThat(captor.getValue().getFormTemplateId()).isEqualTo(10L);
}

@Test
@DisplayName("POST /qrcodes/create-county — 缺参数被拒")
void shouldRejectCountyCodeMissingParams() throws Exception {
    mockMvc.perform(post("/qrcodes/create-county")
            .param("city", "白银市")
            .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));
}
```

（`getOperator()` 依赖 `SecurityContextHolder`，MockMvc standalone 下 `getOperator()` 可能返回 null——`operationLogService.log(null, ...)` 是 mock，不会抛异常；若 `getOperator()` 内部空指针，需在测试里 mock 安全上下文，但现有 `POST /create` 测试未覆盖，本测试同样接受默认 mock 行为。若运行时 NPE，改为给 `SecurityContextHolder` 设置一个空 context，或让 `getOperator()` 内部对 null 返回占位串。）

- [ ] **Step 6: 运行测试确认通过**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q -Dtest=QrCodeControllerTest test`
Expected: 全类测试 PASS（含新增两个）。

- [ ] **Step 7: Commit**

```bash
cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection"
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/test/java/com/bookstore/qrcode/controller/QrCodeControllerTest.java
git commit -m "feat: 县区码建码端点 GET/POST /qrcodes/create-county"
```

---

### Task 4: 建码页 + 列表页前端

**Files:**
- Create: `src/main/resources/templates/qrcode/create-county.html`
- Modify: `src/main/resources/templates/qrcode/list.html`

**Interfaces:**
- Consumes: `GET /qrcodes/create-county` 模型属性 `userList`/`cityOptions`/`districtOptions`/`formTemplates`/`defaultTemplateId`（Task 3）；`POST /qrcodes/create-county` 参数（Task 3）。
- Produces: 无后端接口；纯 Thymeleaf/JS 前端。此任务无单测，靠最终手动运行验证。

- [ ] **Step 1: 新建 create-county.html**

Create `src/main/resources/templates/qrcode/create-county.html`（复用 `create.html` 的 `employee-picker`/`autocomplete` 样式与 `EmployeePicker`/`AutocompleteInput` 两个 JS 类，字段精简为市州/县区/接待员/模板/人数/场景）：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='新建县区码', content=~{::main})}">
<main>
<style>
.employee-picker { border: 1px solid #dee2e6; border-radius: 8px; background: #fff; overflow: hidden; }
.employee-picker .search-bar { padding: 8px 10px; border-bottom: 1px solid #eee; display: flex; align-items: center; gap: 8px; }
.employee-picker .search-bar input { flex: 1; border: none; outline: none; font-size: 14px; padding: 4px 0; }
.employee-picker .search-bar .count { font-size: 12px; color: #888; white-space: nowrap; }
.employee-picker .list { max-height: 220px; overflow-y: auto; scrollbar-gutter: stable; }
.employee-picker .list .row-item { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid #f5f5f5; cursor: pointer; contain: layout style; }
.employee-picker .list .row-item:hover { background: #f8f9ff; }
.employee-picker .list .row-item.selected { background: #e8f0fe; }
.employee-picker .list .row-item .form-check-input { cursor: pointer; flex-shrink: 0; }
.employee-picker .list .row-item .name-col { flex: 1; min-width: 0; }
.employee-picker .list .row-item .name-col .name { font-size: 14px; font-weight: 500; }
.employee-picker .list .row-item .name-col .uid { font-size: 11px; color: #999; }
.employee-picker .list .row-item .limit-col { flex-shrink: 0; width: 100px; }
.employee-picker .list .row-item .limit-col input { width: 100%; text-align: center; font-size: 13px; border: 1px solid #dee2e6; border-radius: 4px; padding: 3px 6px; }
.employee-picker .list .row-item .limit-col input:disabled { background: #f5f5f5; color: #bbb; }
.employee-picker .list .no-result { padding: 20px; text-align: center; color: #999; font-size: 13px; }
.autocomplete-wrapper { position: relative; }
.autocomplete-wrapper .search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: #aaa; font-size: 13px; z-index: 1; pointer-events: none; }
.autocomplete-wrapper input { padding-left: 30px; }
.autocomplete-dropdown { position: absolute; top: 100%; left: 0; width: 100%; max-height: 220px; overflow-y: auto; background: #fff; border: 1px solid #dee2e6; border-top: none; border-radius: 0 0 8px 8px; box-shadow: 0 6px 16px rgba(0,0,0,0.1); z-index: 1050; }
.autocomplete-item { padding: 7px 12px; cursor: pointer; font-size: 14px; color: #333; border-bottom: 1px solid #f5f5f5; }
.autocomplete-item:hover, .autocomplete-item.active { background: #e8f0fe; }
.autocomplete-empty { padding: 16px; text-align: center; color: #999; font-size: 13px; }
</style>

<div class="card p-4 mx-auto" style="max-width: 700px;">
    <h5 class="mb-4">新建县区码</h5>
    <div class="alert alert-info py-2 small">
        县区码用于县区级「联系我」场景：家长扫码后按「学段→学校→年级班级」选校，系统自动转接到对应学校的服务老师。学校 ID 由系统自动生成（<code>county:市:区县</code>），无需填写。
    </div>

    <form method="post" th:action="@{/qrcodes/create-county}" id="createForm">
        <div class="row g-3 mb-3">
            <div class="col-md-6">
                <label class="form-label">市州 <span class="text-danger">*</span></label>
                <div class="autocomplete-wrapper" id="cityAcWrapper">
                    <span class="search-icon">🔍</span>
                    <input type="text" name="city" class="form-control" required
                           placeholder="输入城市名" autocomplete="off">
                </div>
            </div>
            <div class="col-md-6">
                <label class="form-label">县区 <span class="text-danger">*</span></label>
                <div class="autocomplete-wrapper" id="districtAcWrapper">
                    <span class="search-icon">🔍</span>
                    <input type="text" name="district" class="form-control" required
                           placeholder="输入区县名" autocomplete="off">
                </div>
            </div>
        </div>

        <div class="mb-3">
            <label class="form-label">县区接待员 <span class="text-danger">*</span>
                <small class="text-muted fw-normal">— 客户扫码先分配给接待员，选校后自动转接</small>
            </label>
            <div id="receptionistPicker" class="employee-picker"></div>
            <input type="hidden" name="receptionistUserid" id="receptionistUserid">
        </div>

        <div class="row g-3 mb-3">
            <div class="col-md-6">
                <label class="form-label">表单模板</label>
                <select name="formTemplateId" class="form-select">
                    <option th:each="ft : ${formTemplates}" th:value="${ft.id}"
                            th:text="${ft.name}" th:selected="${ft.id == defaultTemplateId}"></option>
                </select>
                <div class="form-text">默认「县区码默认模板」：年级/班级自动打标签</div>
            </div>
            <div class="col-md-6">
                <label class="form-label">学校人数 <small class="text-muted fw-normal">— 选填</small></label>
                <input type="number" name="studentCount" class="form-control" min="1" max="99999"
                       placeholder="用于估算接待员数">
            </div>
        </div>

        <div class="mb-3">
            <label class="form-label">场景</label>
            <div class="btn-group w-100" role="group">
                <input type="radio" class="btn-check" name="scene" id="sceneDaily" value="daily_push" checked>
                <label class="btn btn-outline-primary btn-sm" for="sceneDaily">📱 日常</label>
                <input type="radio" class="btn-check" name="scene" id="sceneParentMeeting" value="parent_meeting">
                <label class="btn btn-outline-primary btn-sm" for="sceneParentMeeting">🏫 家长会</label>
            </div>
        </div>

        <div id="createError" class="alert alert-danger d-none py-2 mb-2" role="alert"></div>
        <div class="d-flex gap-2">
            <button type="submit" class="btn btn-primary">✓ 创建县区码</button>
            <a href="/qrcodes" class="btn btn-outline-secondary">✕ 取消</a>
        </div>
    </form>
</div>

<script th:inline="javascript">
    window._employeeList = /*[[${userList}]]*/ [];
    window._cityOptions = /*[[${cityOptions}]]*/ [];
    window._districtOptions = /*[[${districtOptions}]]*/ [];
</script>
<script>
/* 复制自 create.html 的两个组件类：EmployeePicker + AutocompleteInput（保持与建码页一致） */
class EmployeePicker { /* ... 与 create.html 第 287-456 行完全一致 ... */ }
class AutocompleteInput { /* ... 与 create.html 第 466-568 行完全一致 ... */ }

document.addEventListener('DOMContentLoaded', () => {
    const employees = window._employeeList || [];
    let receptionistPicker = null;
    if (employees.length > 0) {
        receptionistPicker = new EmployeePicker(document.getElementById('receptionistPicker'), {
            items: employees, defaultDailyMax: 150,
            placeholder: '搜索县区接待员姓名或账号...'
        });
    }

    document.getElementById('createForm').addEventListener('submit', function(e) {
        e.preventDefault();
        const errEl = document.getElementById('createError');
        const form = this;

        const userids = receptionistPicker ? receptionistPicker.getSelected().map(s => s.userid) : [];
        document.getElementById('receptionistUserid').value = userids.join(',');

        const city = form.querySelector('[name="city"]').value.trim();
        const district = form.querySelector('[name="district"]').value.trim();
        if (!city) { errEl.textContent = '请填写市州'; errEl.classList.remove('d-none'); return; }
        if (!district) { errEl.textContent = '请填写县区'; errEl.classList.remove('d-none'); return; }
        if (userids.length === 0) { errEl.textContent = '请至少选择一位县区接待员'; errEl.classList.remove('d-none'); return; }
        errEl.classList.add('d-none');

        const btn = form.querySelector('button[type="submit"]');
        btn.disabled = true; btn.textContent = '⏳ 创建中...';
        fetch(form.action, {
            method: 'POST',
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            body: new FormData(form)
        })
        .then(async resp => {
            const data = await resp.json();
            if (!resp.ok || !data.success) throw new Error(data.error || '创建失败，请重试');
            window.location.href = '/qrcodes';
        })
        .catch(err => {
            errEl.textContent = '创建失败：' + err.message;
            errEl.classList.remove('d-none');
            btn.disabled = false; btn.textContent = '✓ 创建县区码';
        });
    });

    new AutocompleteInput(document.getElementById('cityAcWrapper'), window._cityOptions || []);
    new AutocompleteInput(document.getElementById('districtAcWrapper'), window._districtOptions || []);
});
</script>
</main>
</html>
```

**注意**：上面 `<script>` 中 `EmployeePicker` / `AutocompleteInput` 两处 `/* ... */` 占位必须替换为 `create.html` 第 287-456 行与 466-568 行的完整类实现（逐字复制）。不得保留注释占位符。

- [ ] **Step 2: list.html 加按钮与徽标**

In `list.html`，第 46 行「手动创建」链接后加：

```html
<a href="/qrcodes/create-county" class="btn btn-outline-primary"><i class="bi bi-geo-alt"></i> 新建县区码</a>
```

第 160-162 行学校名称单元格内、链接之后加徽标（`#strings.startsWith` 是 Thymeleaf 内置工具）：

```html
<span th:if="${#strings.startsWith(qr.schoolId, 'county:')}"
      class="badge bg-info ms-1">县区码</span>
```

- [ ] **Step 3: 全量回归测试**

Run: `cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection" && "/c/Program Files/Apache/apache-maven-3.9.9/bin/mvn" -q test`
Expected: 全部测试 PASS（现有 + 本计划新增）。

- [ ] **Step 4: Commit**

```bash
cd "D:/ClaudeCode/HuoMa/.claude/worktrees/district-qrcode-school-selection"
git add src/main/resources/templates/qrcode/create-county.html src/main/resources/templates/qrcode/list.html
git commit -m "feat: 县区码建码页 + 列表页县区码徽标"
```

---

## 最终手动验证清单（无浏览器环境，实施后交用户/运维验收）

1. 列表页「新建县区码」按钮可见 → 点击进入 `create-county` 页。
2. 建码页市州/县区 autocomplete 正常；接待员可搜索多选；表单模板默认选中「县区码默认模板」。
3. 提交后跳转列表页，新县区码行显示「县区码」徽标，`schoolId = county:市:区县`。
4. 详情页 `学校ID` 展示为 `county:市:区县`。
5. 家长扫县区码 → `fill.html` 三级级联选校 → 提交后年级/班级打标签 + 在职继承转接（Phase 1 已有链路回归）。
