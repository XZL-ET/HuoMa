# 县区活码 — 扫码选学校/年级/班级 + 在职继承转接 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增县区码（企微联系我活码）：家长扫码加县区接待员后，在收集表单里三级级联选择【学段→学校→年级→班级】，打标签，再在职继承转接到对应学校的服务老师名下。

**Architecture:** 县区码是 `QrCode`，用合成 `school_id`（`county:` 前缀）标识、`regionDistrict` 定位县区。表单页 `fill.html` 升级为三级级联 + 搜索 + localStorage 记忆；新增 `SchoolSelectionService` 负责「县区→学校列表」「学段→年级枚举」「学校→服务老师」解析链；转接沿用现有 `TRANSFER_STREAM_KEY` 异步事件，由 `TransferWorker` 消费。

**Tech Stack:** Spring Boot 3.2.5、Spring Data JPA、Thymeleaf、Redis Stream、Bootstrap 5.3、原生 JS（pinyin-pro CDN 做拼音首字母搜索）。

**Spec:** `docs/superpowers/specs/2026-08-25-district-qrcode-school-selection-design.md`

## Global Constraints

- 无 JPA 关系注解：实体用显式 FK ID 字段（`Long qrCodeId`、`String agentUserid`），不引入 `@ManyToOne`/`@OneToMany`。
- `qr_code.school_id` 为 `NOT NULL UNIQUE`，县区码用合成 `school_id`（`"county:" + regionDistrict`）标记，检测靠 `startsWith("county:")`。
- 年级/班级打企微标签（不是备注），复用 `TagService.applyFormTags` 的 `tag_mapping` 机制，**无需改打标代码**，只需表单模板配置 `{"grade":"tag","class":"tag"}`。
- 学校标签走 `TagService` 三级地理标签 + `applyFormTags` 的 `schoolName` 参数，无需改动。
- 转接沿用 `RedisConfig.TRANSFER_STREAM_KEY` 事件（`{customer_id, from_userid, to_userid, external_userid, state}`），不直接同步调 `transfer_customer`。
- 测试基类 `BaseIntegrationTest`（`@SpringBootTest(webEnvironment=NONE)` + H2 + Embedded Redis + `@Import(WecomApiMockConfig.class)`），新集成测试沿用此模式。

---

### Task 1: SchoolRepository / QrCodeGroupRepository 查询方法

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/SchoolRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/repository/QrCodeGroupRepository.java`
- Test: `src/test/java/com/bookstore/qrcode/integration/SchoolSelectionRepositoryTest.java`

**Interfaces:**
- Consumes: 无（纯新增查询方法）。
- Produces:
  - `SchoolRepository.findByRegionDistrictAndDeletedFalseOrderBySchoolName(String)` → `List<School>`
  - `SchoolRepository.findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName(String, Long)` → `List<School>`
  - `SchoolRepository.findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName(String)` → `List<School>`
  - `QrCodeGroupRepository.findByGroupType(String)` → `List<QrCodeGroup>`

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/com/bookstore/qrcode/integration/SchoolSelectionRepositoryTest.java`：

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Import(WecomApiMockConfig.class)
@DisplayName("县区学校查询 Repository 测试")
class SchoolSelectionRepositoryTest extends BaseIntegrationTest {

    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private QrCodeGroupRepository groupRepo;

    @BeforeEach
    void clean() {
        schoolRepo.deleteAll();
        groupRepo.deleteAll();
    }

    @Test
    void 按区县和分类查学校() {
        SchoolCategory primary = categoryRepo.save(SchoolCategory.builder()
            .name("小学").sortOrder(1).build());
        SchoolCategory middle = categoryRepo.save(SchoolCategory.builder()
            .name("初中").sortOrder(2).build());

        schoolRepo.save(School.builder().schoolId("s1").schoolName("白银一小")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s2").schoolName("白银一中")
            .regionCity("白银市").regionDistrict("白银区").categoryId(middle.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s3").schoolName("无分类学校")
            .regionCity("白银市").regionDistrict("白银区").categoryId(null).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s4").schoolName("已删除学校")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(true).build());
        schoolRepo.save(School.builder().schoolId("s5").schoolName("别区小学")
            .regionCity("白银市").regionDistrict("平川区").categoryId(primary.getId()).deleted(false).build());

        List<School> primarySchools =
            schoolRepo.findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName("白银区", primary.getId());
        assertThat(primarySchools).extracting(School::getSchoolName).containsExactly("白银一小");

        List<School> uncategorized =
            schoolRepo.findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName("白银区");
        assertThat(uncategorized).extracting(School::getSchoolName).containsExactly("无分类学校");

        List<School> allInDistrict =
            schoolRepo.findByRegionDistrictAndDeletedFalseOrderBySchoolName("白银区");
        assertThat(allInDistrict).hasSize(3);
    }

    @Test
    void 按组类型查联盟() {
        groupRepo.save(QrCodeGroup.builder().name("白银联盟").regionCity("白银市")
            .regionDistrict("白银区").groupType("alliance")
            .schoolList("白银一小\n白银二小").build());
        groupRepo.save(QrCodeGroup.builder().name("别的组").regionCity("白银市")
            .regionDistrict("白银区").groupType("other").schoolList("x").build());

        List<QrCodeGroup> alliances = groupRepo.findByGroupType("alliance");
        assertThat(alliances).hasSize(1);
        assertThat(alliances.get(0).getName()).isEqualTo("白银联盟");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SchoolSelectionRepositoryTest test`
Expected: FAIL（编译错误：`findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName` / `findByRegionDistrictAndDeletedFalseOrderBySchoolName` / `findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName` / `findByGroupType` 方法不存在）。

- [ ] **Step 3: 实现查询方法**

在 `SchoolRepository.java` 末尾（`findByCategoryIdAndDeletedFalse` 之后）追加：

```java
    /** 按区县查未删除学校（县区码：无学段筛选） */
    List<School> findByRegionDistrictAndDeletedFalseOrderBySchoolName(String regionDistrict);

    /** 按区县 + 分类查未删除学校（县区码：按学段收窄） */
    List<School> findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName(
            String regionDistrict, Long categoryId);

    /** 按区县查未分类（categoryId 为 null）的未删除学校 */
    List<School> findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName(
            String regionDistrict);
```

在 `QrCodeGroupRepository.java` 追加：

```java
    /** 按组类型查分组（县区码解析链：查 alliance 联盟，schoolList 在 Java 侧精确匹配） */
    List<QrCodeGroup> findByGroupType(String groupType);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SchoolSelectionRepositoryTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/repository/SchoolRepository.java \
        src/main/java/com/bookstore/qrcode/repository/QrCodeGroupRepository.java \
        src/test/java/com/bookstore/qrcode/integration/SchoolSelectionRepositoryTest.java
git commit -m "feat: 新增县区学校查询与联盟分组查询方法"
```

---

### Task 2: SchoolSelectionService — isCountyCode / listSchools / listGrades

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/SchoolSelectionService.java`
- Test: `src/test/java/com/bookstore/qrcode/integration/SchoolSelectionServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的四个查询方法；`SchoolCategoryRepository.findByName(String)`、`QrCodeRepository.findById(Long)`、`QrCodeRepository.findBySchoolId(String)`、`QrAgentRepository.findByQrCodeId(Long)`、`QrCodeGroupRepository.findByGroupType(String)`。
- Produces:
  - `boolean isCountyCode(QrCode qr)`
  - `List<SchoolOption> listSchools(Long qrCodeId, String categoryName)`（`SchoolOption(String schoolId, String schoolName)` record）
  - `List<String> listGrades(String categoryName)`
  - `Optional<TransferTarget> resolveTransferTarget(String schoolId, String schoolName)`（`TransferTarget(String state, String toUserid)` record）
  - `boolean initiateCountyTransfer(Long customerId, String fromUserid, String externalUserid, String schoolId, String schoolName)`

- [ ] **Step 1: 写失败测试（isCountyCode + listSchools + listGrades）**

新建 `src/test/java/com/bookstore/qrcode/integration/SchoolSelectionServiceTest.java`：

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.SchoolSelectionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Import(WecomApiMockConfig.class)
@DisplayName("县区选校服务测试")
class SchoolSelectionServiceTest extends BaseIntegrationTest {

    @Autowired private SchoolSelectionService service;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrCodeGroupRepository groupRepo;

    private QrCode countyQr;

    @BeforeEach
    void setUp() {
        schoolRepo.deleteAll();
        groupRepo.deleteAll();
        qrCodeRepo.deleteAll();

        countyQr = new QrCode();
        countyQr.setSchoolName("白银区");
        countyQr.setSchoolId("county:白银区");
        countyQr.setRegionCity("白银市");
        countyQr.setRegionDistrict("白银区");
        countyQr.setStatus(QrCode.QrCodeStatus.active);
        countyQr.setScene(Scene.daily_push);
        countyQr.setCreateMode(QrCode.CreateMode.manual);
        countyQr = qrCodeRepo.save(countyQr);
    }

    @Test
    void 检测县区码() {
        assertThat(service.isCountyCode(countyQr)).isTrue();
        QrCode schoolQr = new QrCode();
        schoolQr.setSchoolName("白银一小");
        schoolQr.setSchoolId("s1");
        schoolQr.setRegionCity("白银市");
        schoolQr.setRegionDistrict("白银区");
        schoolQr = qrCodeRepo.save(schoolQr);
        assertThat(service.isCountyCode(schoolQr)).isFalse();
    }

    @Test
    void 按学段列出县区学校() {
        SchoolCategory primary = categoryRepo.save(SchoolCategory.builder()
            .name("小学").sortOrder(1).build());
        schoolRepo.save(School.builder().schoolId("s1").schoolName("白银一小")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s2").schoolName("白银一中")
            .regionCity("白银市").regionDistrict("白银区").categoryId(null).deleted(false).build());

        List<SchoolSelectionService.SchoolOption> primarySchools =
            service.listSchools(countyQr.getId(), "小学");
        assertThat(primarySchools).extracting(SchoolSelectionService.SchoolOption::schoolName)
            .containsExactly("白银一小");

        List<SchoolSelectionService.SchoolOption> all = service.listSchools(countyQr.getId(), "全部");
        assertThat(all).hasSize(2);
    }

    @Test
    void 按学段返回年级枚举() {
        assertThat(service.listGrades("小学"))
            .containsExactly("一年级", "二年级", "三年级", "四年级", "五年级", "六年级");
        assertThat(service.listGrades("幼儿园")).containsExactly("小班", "中班", "大班");
        assertThat(service.listGrades("不存在的学段")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SchoolSelectionServiceTest test`
Expected: FAIL（`SchoolSelectionService` 类不存在）。

- [ ] **Step 3: 实现 Service（前半部分）**

新建 `src/main/java/com/bookstore/qrcode/service/SchoolSelectionService.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 县区码选校服务：县区→学校列表、学段→年级枚举、学校→服务老师解析链、发起在职继承。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolSelectionService {

    private final QrCodeRepository qrCodeRepo;
    private final SchoolRepository schoolRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final QrCodeGroupRepository groupRepo;
    private final QrAgentRepository qrAgentRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 县区码 school_id 前缀，用于区分县区码与学校活码 */
    public static final String COUNTY_PREFIX = "county:";

    /** 学段 → 年级枚举（见 spec §2.4） */
    private static final Map<String, List<String>> GRADE_MAP = Map.of(
        "小学", List.of("一年级", "二年级", "三年级", "四年级", "五年级", "六年级"),
        "初中", List.of("初一", "初二", "初三"),
        "高中", List.of("高一", "高二", "高三"),
        "幼儿园", List.of("小班", "中班", "大班")
    );

    public boolean isCountyCode(QrCode qr) {
        return qr != null && qr.getSchoolId() != null
            && qr.getSchoolId().startsWith(COUNTY_PREFIX);
    }

    public List<SchoolOption> listSchools(Long qrCodeId, String categoryName) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null || qr.getRegionDistrict() == null) return List.of();
        String district = qr.getRegionDistrict();

        List<School> schools;
        if (categoryName == null || categoryName.isBlank() || "全部".equals(categoryName)) {
            schools = schoolRepo.findByRegionDistrictAndDeletedFalseOrderBySchoolName(district);
        } else if ("未分类".equals(categoryName)) {
            schools = schoolRepo
                .findByRegionDistrictAndCategoryIdIsNullAndDeletedFalseOrderBySchoolName(district);
        } else {
            Long categoryId = categoryRepo.findByName(categoryName)
                .map(SchoolCategory::getId).orElse(null);
            if (categoryId == null) {
                log.warn("学段未找到对应分类: category={}", categoryName);
                return List.of();
            }
            schools = schoolRepo
                .findByRegionDistrictAndCategoryIdAndDeletedFalseOrderBySchoolName(district, categoryId);
        }
        return schools.stream()
            .map(s -> new SchoolOption(s.getSchoolId(), s.getSchoolName()))
            .toList();
    }

    public List<String> listGrades(String categoryName) {
        if (categoryName == null) return List.of();
        return GRADE_MAP.getOrDefault(categoryName, List.of());
    }

    public record SchoolOption(String schoolId, String schoolName) {}
    public record TransferTarget(String state, String toUserid) {}
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SchoolSelectionServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/SchoolSelectionService.java \
        src/test/java/com/bookstore/qrcode/integration/SchoolSelectionServiceTest.java
git commit -m "feat: 新增县区选校服务（县区码识别/学校列表/年级枚举）"
```

---

### Task 3: SchoolSelectionService — resolveTransferTarget 解析链 + initiateCountyTransfer

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/SchoolSelectionService.java`
- Test: `src/test/java/com/bookstore/qrcode/integration/SchoolSelectionServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `QrCodeRepository.findBySchoolId(String)`、`QrAgentRepository.findByQrCodeId(Long)`、`QrCodeGroupRepository.findByGroupType(String)`、`QrCodeGroup.schoolList`（一行一个学校名）。
- Produces: `Optional<TransferTarget> resolveTransferTarget(String schoolId, String schoolName)`、`boolean initiateCountyTransfer(...)`。

- [ ] **Step 1: 写失败测试（解析链三种情况 + 转接事件）**

在 `SchoolSelectionServiceTest` 追加：

```java
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private StringRedisTemplate redisTemplate;

    private QrCode buildSchoolQr(String schoolId, String schoolName, String serviceUserid) {
        QrCode qr = new QrCode();
        qr.setSchoolName(schoolName);
        qr.setSchoolId(schoolId);
        qr.setRegionCity("白银市");
        qr.setRegionDistrict("白银区");
        qr.setStatus(QrCode.QrCodeStatus.active);
        qr.setScene(Scene.daily_push);
        qr.setCreateMode(QrCode.CreateMode.manual);
        qr = qrCodeRepo.save(qr);
        if (serviceUserid != null) {
            qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId())
                .agentUserid(serviceUserid).role(QrAgent.AgentRole.service)
                .status(QrAgent.AgentStatus.active).build());
        }
        return qr;
    }

    @Test
    void 解析链_一校一码() {
        buildSchoolQr("s1", "白银一小", "svc_a");
        Optional<SchoolSelectionService.TransferTarget> target =
            service.resolveTransferTarget("s1", "白银一小");
        assertThat(target).isPresent();
        assertThat(target.get().state()).isEqualTo("s1");
        assertThat(target.get().toUserid()).isEqualTo("svc_a");
    }

    @Test
    void 解析链_学区码兜底() {
        // 学校无独立活码，但包在学区码 schoolList 里
        QrCode allianceQr = buildSchoolQr("alliance:白银区", "白银区联盟", "svc_district");
        groupRepo.save(QrCodeGroup.builder().name("白银联盟").regionCity("白银市")
            .regionDistrict("白银区").groupType("alliance")
            .qrCodeId(allianceQr.getId())
            .schoolList("白银一小\n白银二小").build());

        Optional<SchoolSelectionService.TransferTarget> target =
            service.resolveTransferTarget("s9", "白银二小");
        assertThat(target).isPresent();
        assertThat(target.get().state()).isEqualTo("alliance:白银区");
        assertThat(target.get().toUserid()).isEqualTo("svc_district");
    }

    @Test
    void 解析链_既无独立活码也不在学区码() {
        assertThat(service.resolveTransferTarget("s99", "幽灵学校")).isEmpty();
    }

    @Test
    void 发起县区转接_写入转接流() {
        buildSchoolQr("s1", "白银一小", "svc_a");
        boolean published = service.initiateCountyTransfer(
            123L, "rec_county", "wm-external", "s1", "白银一小");
        assertThat(published).isTrue();

        var records = redisTemplate.opsForStream()
            .range(RedisConfig.TRANSFER_STREAM_KEY,
                org.springframework.data.domain.Range.unbounded());
        assertThat(records).isNotEmpty();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SchoolSelectionServiceTest test`
Expected: FAIL（`resolveTransferTarget` / `initiateCountyTransfer` 未实现）。

- [ ] **Step 3: 实现解析链与转接事件**

在 `SchoolSelectionService` 末尾追加：

```java
    /**
     * 学校 → 服务老师解析链（见 spec §3.2）：
     * ① 学校有独立活码 → 取该活码的 service 老师；② 否则包在学区码 → 取学区码活码的 service 老师。
     *
     * @return state=目标活码 schoolId（传给 TransferService.initiate 反查活码），toUserid=目标服务老师
     */
    public Optional<TransferTarget> resolveTransferTarget(String schoolId, String schoolName) {
        // ① 一校一码：学校自己有活码
        QrCode schoolQr = qrCodeRepo.findBySchoolId(schoolId).orElse(null);
        if (schoolQr != null) {
            String toUserid = findServiceAgent(schoolQr);
            if (toUserid != null) return Optional.of(new TransferTarget(schoolId, toUserid));
            log.warn("学校 {} 有活码但未配置服务老师，无法转接", schoolName);
            return Optional.empty();
        }
        // ② 学区码兜底：学校名在联盟 schoolList 里
        if (schoolName == null) return Optional.empty();
        for (QrCodeGroup g : groupRepo.findByGroupType("alliance")) {
            if (!containsSchool(g, schoolName)) continue;
            if (g.getQrCodeId() == null) continue;
            QrCode allianceQr = qrCodeRepo.findById(g.getQrCodeId()).orElse(null);
            if (allianceQr == null) continue;
            String toUserid = findServiceAgent(allianceQr);
            if (toUserid != null) return Optional.of(new TransferTarget(allianceQr.getSchoolId(), toUserid));
        }
        log.warn("学校 {} 既无独立活码也不在学区码，留在县区接待员", schoolName);
        return Optional.empty();
    }

    /**
     * 表单提交后发起县区码在职继承：解析目标老师并写入 TRANSFER_STREAM_KEY 异步转接。
     *
     * @return true=已发布转接事件，false=未解析到目标（留在县区接待员）
     */
    public boolean initiateCountyTransfer(Long customerId, String fromUserid,
                                           String externalUserid, String schoolId, String schoolName) {
        Optional<TransferTarget> target = resolveTransferTarget(schoolId, schoolName);
        if (target.isEmpty()) return false;
        TransferTarget t = target.get();
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("customer_id", String.valueOf(customerId));
            event.put("from_userid", fromUserid);
            event.put("to_userid", t.toUserid());
            event.put("external_userid", externalUserid);
            event.put("state", t.state());
            redisTemplate.opsForStream().add(
                RedisConfig.TRANSFER_STREAM_KEY,
                Map.of("event", objectMapper.writeValueAsString(event)));
            log.info("县区码转接事件已发布: customer={}, to={}, state={}",
                customerId, t.toUserid(), t.state());
            return true;
        } catch (Exception e) {
            log.error("发布县区码转接事件失败: customer={}", customerId, e);
            return false;
        }
    }

    /** 取活码第一个未移除的 service/dual 老师 */
    private String findServiceAgent(QrCode qr) {
        return qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .filter(a -> a.getRole() == QrAgent.AgentRole.service
                      || a.getRole() == QrAgent.AgentRole.dual)
            .map(QrAgent::getAgentUserid)
            .findFirst().orElse(null);
    }

    /** schoolList 一行一个学校名，精确匹配整行 */
    private boolean containsSchool(QrCodeGroup g, String schoolName) {
        if (g.getSchoolList() == null) return false;
        for (String line : g.getSchoolList().split("\\n")) {
            if (line.strip().equals(schoolName)) return true;
        }
        return false;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SchoolSelectionServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/SchoolSelectionService.java \
        src/test/java/com/bookstore/qrcode/integration/SchoolSelectionServiceTest.java
git commit -m "feat: 学校→服务老师解析链 + 县区码在职继承转接事件"
```

---

### Task 4: FormController — 新增接口 + fillForm 县区码识别

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/FormController.java`

**Interfaces:**
- Consumes: `SchoolSelectionService.isCountyCode / listSchools / listGrades`。
- Produces:
  - `GET /api/form/schools?qrCodeId=&category=` → `{"schools":[{schoolId,schoolName}]}`（JSON）
  - `GET /api/form/grades?category=` → `{"grades":["一年级",...]}`（JSON）
  - `fillForm` 对县区码增加 model 属性 `countyCode=true`、`district`。

- [ ] **Step 1: 注入 Service 并新增两个接口**

在 `FormController` 顶部 `@RequiredArgsConstructor` 字段区追加依赖：

```java
    private final SchoolSelectionService schoolSelectionService;
```

在 `fillForm` 的 return 之前（第 78 行 `return "form/fill";` 前）追加县区码识别：

```java
        // 县区码：三级级联选校（学段→学校→年级班级）
        if (schoolSelectionService.isCountyCode(qr)) {
            model.addAttribute("countyCode", true);
            model.addAttribute("district", qr.getRegionDistrict());
        }
```

在 `FormController` 类末尾（`success()` 之前）新增两个 JSON 接口：

```java
    @GetMapping("/api/form/schools")
    @ResponseBody
    public Map<String, Object> schools(@RequestParam Long qrCodeId,
                                       @RequestParam(required = false) String category) {
        List<SchoolSelectionService.SchoolOption> schools =
            schoolSelectionService.listSchools(qrCodeId, category);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schools", schools);
        return result;
    }

    @GetMapping("/api/form/grades")
    @ResponseBody
    public Map<String, Object> grades(@RequestParam String category) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grades", schoolSelectionService.listGrades(category));
        return result;
    }
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/controller/FormController.java
git commit -m "feat: 表单页县区码识别 + 学校/年级查询接口"
```

---

### Task 5: FormController — submit() 转接接线

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/FormController.java`

**Interfaces:**
- Consumes: `SchoolSelectionService.isCountyCode / initiateCountyTransfer`；body 新增顶层字段 `schoolId`。
- Produces: `submit()` 在县区码场景发布转接事件。

- [ ] **Step 1: 在 submit() 里接线**

在 `submit()` 中，`redisTemplate.opsForStream().add(RedisConfig.TAG_STREAM_KEY, ...)` 之后、`result.put("success", true)` 之前，追加：

```java
            // 县区码：表单提交后触发在职继承转接（异步，走 TRANSFER_STREAM_KEY）
            if (schoolSelectionService.isCountyCode(qr)) {
                String schoolId = body.get("schoolId") != null
                    ? body.get("schoolId").toString() : null;
                if (schoolId != null && !schoolId.isBlank()) {
                    schoolSelectionService.initiateCountyTransfer(
                        customerId,
                        customer.getCurrentAgent(),
                        customer.getExternalUserid(),
                        schoolId, schoolName);
                }
            }
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/controller/FormController.java
git commit -m "feat: 表单提交后触发县区码在职继承转接"
```

---

### Task 6: fill.html — 三级级联 + 搜索 + localStorage

**Files:**
- Modify: `src/main/resources/templates/form/fill.html`

**Interfaces:**
- Consumes: model 属性 `countyCode`、`district`；`GET /api/form/schools`、`GET /api/form/grades`；pinyin-pro CDN。
- Produces: 县区码表单页三级级联 UI；提交体含 `schoolId` + `schoolName` + `fieldData.grade/class`。

- [ ] **Step 1: 引入 pinyin-pro 并在 head 加县区码样式**

在 `<head>` 的 Bootstrap `<link>` 后追加 pinyin-pro（拼音首字母搜索）：

```html
    <script src="https://cdn.jsdelivr.net/npm/pinyin-pro@3.26.0/dist/index.js"></script>
```

在 `<style>` 里追加县区码级联样式：

```css
        .stage-btn { border:1px solid #dee2e6; border-radius:8px; padding:10px 0; text-align:center; cursor:pointer; background:#fff; }
        .stage-btn.active { border-color:#2980b9; background:#eaf4fb; color:#1a5276; font-weight:700; }
        .school-item { padding:10px 12px; border:1px solid #eef0f3; border-radius:8px; margin-bottom:8px; cursor:pointer; }
        .school-item.selected { border-color:#2980b9; background:#eaf4fb; }
        .last-hint { background:#f0f7ee; border:1px solid #d4e7cd; border-radius:8px; padding:10px 12px; font-size:0.85rem; color:#3c763d; margin-bottom:1rem; cursor:pointer; }
```

- [ ] **Step 2: 替换 form 主体，插入县区码级联容器**

在 `<form id="collectForm">` 内、现有的 `<div id="schoolPicker">`（联盟下拉）之后，追加县区码级联容器：

```html
                <!-- 县区码：三级级联选校 -->
                <div id="countyPicker" style="display:none;">
                    <div class="mb-3">
                        <label class="form-label">学段 <span class="text-danger">*</span></label>
                        <div class="row g-2" id="stageButtons">
                            <div class="col-3"><div class="stage-btn" data-stage="小学">小学</div></div>
                            <div class="col-3"><div class="stage-btn" data-stage="初中">初中</div></div>
                            <div class="col-3"><div class="stage-btn" data-stage="高中">高中</div></div>
                            <div class="col-3"><div class="stage-btn" data-stage="幼儿园">幼儿园</div></div>
                        </div>
                    </div>
                    <div class="mb-3" id="schoolSearchBox" style="display:none;">
                        <label class="form-label">学校 <span class="text-danger">*</span></label>
                        <input type="text" id="schoolSearch" class="form-control" placeholder="搜索学校名称 / 拼音首字母">
                        <div id="schoolList" class="mt-2" style="max-height:280px;overflow:auto;"></div>
                    </div>
                    <div class="mb-3" id="gradeBox" style="display:none;">
                        <label class="form-label">年级 + 班级 <span class="text-danger">*</span></label>
                        <div class="row g-2">
                            <div class="col-6">
                                <select id="gradeSelect" class="form-select"></select>
                            </div>
                            <div class="col-6">
                                <input type="text" id="classInput" class="form-control" placeholder="如：2班">
                            </div>
                        </div>
                    </div>
                </div>
```

- [ ] **Step 3: 替换提交脚本，加入级联逻辑**

用以下脚本整体替换现有 `<script th:inline="javascript">...</script>`（保留 fields 动态渲染部分不变，扩展县区码逻辑）：

```html
<script th:inline="javascript">
    var fields = JSON.parse(/*[[${fieldsJson}]]*/ '[]');
    var isCountyCode = /*[[${countyCode} ?: false}]]*/ false;

    // ===== 动态表单字段（既有逻辑） =====
    var container = document.getElementById('formFields');
    fields.forEach(function(f) {
        var div = document.createElement('div'); div.className = 'mb-3';
        var label = document.createElement('label');
        label.className = 'form-label'; label.textContent = f.label + (f.required ? ' *' : '');
        div.appendChild(label);
        if (f.type === 'select') {
            var sel = document.createElement('select');
            sel.className = 'form-select'; sel.name = f.name; sel.required = f.required || false;
            var defaultOpt = document.createElement('option');
            defaultOpt.value = ''; defaultOpt.textContent = '请选择';
            sel.appendChild(defaultOpt);
            (f.options || []).forEach(function(o) {
                var opt = document.createElement('option');
                opt.value = o; opt.textContent = o;
                sel.appendChild(opt);
            });
            div.appendChild(sel);
        } else {
            var input = document.createElement('input');
            input.type = 'text'; input.className = 'form-control';
            input.name = f.name; input.required = f.required || false;
            div.appendChild(input);
        }
        container.appendChild(div);
    });

    // ===== 县区码三级级联 =====
    var selectedStage = null, selectedSchool = null, allSchools = [];
    var lastKey = 'county_last_selection';

    function pinyinInitials(name) {
        try { return window.pinyinPro.pinyin(name, {pattern:'first', toneType:'none', type:'array'}).join(''); }
        catch (e) { return ''; }
    }
    function pinyinFull(name) {
        try { return window.pinyinPro.pinyin(name, {toneType:'none', type:'array'}).join(''); }
        catch (e) { return ''; }
    }

    if (isCountyCode) {
        document.getElementById('countyPicker').style.display = '';
        // 学段按钮
        document.querySelectorAll('#stageButtons .stage-btn').forEach(function(btn) {
            btn.addEventListener('click', function() {
                selectedStage = btn.dataset.stage;
                document.querySelectorAll('#stageButtons .stage-btn').forEach(function(b){ b.classList.remove('active'); });
                btn.classList.add('active');
                loadSchools(selectedStage);
                loadGrades(selectedStage);
                document.getElementById('schoolSearchBox').style.display = '';
                document.getElementById('gradeBox').style.display = '';
                document.getElementById('schoolSearch').value = '';
                renderSchools('');
            });
        });
        // 搜索过滤
        document.getElementById('schoolSearch').addEventListener('input', function() {
            renderSchools(this.value.trim().toLowerCase());
        });
        // 上次选择提示
        var last = localStorage.getItem(lastKey);
        if (last) {
            try {
                var lastObj = JSON.parse(last);
                var hint = document.createElement('div');
                hint.className = 'last-hint';
                hint.textContent = '上次：' + lastObj.stage + ' ' + lastObj.schoolName + ' ' + lastObj.grade + lastObj.className + '，点击回填';
                hint.addEventListener('click', function() { restoreLast(lastObj); });
                document.getElementById('countyPicker').insertBefore(hint, document.getElementById('stageButtons'));
            } catch (e) {}
        }
    }

    function loadSchools(stage) {
        var qrCodeId = document.getElementById('qrCodeId').value;
        fetch('/api/form/schools?qrCodeId=' + qrCodeId + '&category=' + encodeURIComponent(stage))
            .then(function(r){ return r.json(); })
            .then(function(d){ allSchools = d.schools || []; renderSchools(''); })
            .catch(function(){ allSchools = []; renderSchools(''); });
    }

    function renderSchools(query) {
        var list = document.getElementById('schoolList');
        list.innerHTML = '';
        var filtered = allSchools;
        if (query) {
            filtered = allSchools.filter(function(s) {
                var name = s.schoolName.toLowerCase();
                var initials = pinyinInitials(s.schoolName).toLowerCase();
                var full = pinyinFull(s.schoolName).toLowerCase();
                return name.indexOf(query) >= 0 || initials.indexOf(query) >= 0 || full.indexOf(query) >= 0;
            });
        }
        filtered.forEach(function(s) {
            var item = document.createElement('div');
            item.className = 'school-item' + (selectedSchool && selectedSchool.schoolId === s.schoolId ? ' selected' : '');
            item.textContent = s.schoolName;
            item.addEventListener('click', function() {
                selectedSchool = s;
                renderSchools(document.getElementById('schoolSearch').value.trim().toLowerCase());
            });
            list.appendChild(item);
        });
    }

    function loadGrades(stage) {
        fetch('/api/form/grades?category=' + encodeURIComponent(stage))
            .then(function(r){ return r.json(); })
            .then(function(d) {
                var sel = document.getElementById('gradeSelect');
                sel.innerHTML = '';
                (d.grades || []).forEach(function(g) {
                    var o = document.createElement('option');
                    o.value = g; o.textContent = g;
                    sel.appendChild(o);
                });
            });
    }

    function restoreLast(obj) {
        selectedStage = obj.stage;
        document.querySelectorAll('#stageButtons .stage-btn').forEach(function(b){ b.classList.toggle('active', b.dataset.stage === obj.stage); });
        loadSchools(obj.stage); loadGrades(obj.stage);
        document.getElementById('schoolSearchBox').style.display = '';
        document.getElementById('gradeBox').style.display = '';
        document.getElementById('classInput').value = obj.className || '';
        // school 回填在 loadSchools 后按名称匹配
        var check = setInterval(function() {
            var match = allSchools.find(function(s){ return s.schoolName === obj.schoolName; });
            if (match) { selectedSchool = match; renderSchools(''); clearInterval(check); }
        }, 100);
        document.getElementById('gradeSelect').value = obj.grade || '';
    }

    // ===== 提交 =====
    document.getElementById('collectForm').addEventListener('submit', function(e) {
        e.preventDefault();
        var fieldData = {};
        fields.forEach(function(f) {
            var el = document.querySelector('[name="' + f.name + '"]');
            if (el) fieldData[f.name] = el.value;
        });
        var btn = e.target.querySelector('button');
        btn.disabled = true; btn.textContent = '提交中...';

        var body = {
            qrCodeId: parseInt(document.getElementById('qrCodeId').value),
            customerId: parseInt(document.getElementById('customerId').value),
            fieldData: fieldData
        };

        var schoolSelect = document.getElementById('schoolSelect');
        if (schoolSelect) body.schoolName = schoolSelect.value;

        if (isCountyCode) {
            if (!selectedStage || !selectedSchool) {
                msg.innerHTML = '<span class="text-danger">❌ 请先选择学段和学校</span>';
                btn.disabled = false; btn.textContent = '提交';
                return;
            }
            body.schoolId = selectedSchool.schoolId;
            body.schoolName = selectedSchool.schoolName;
            fieldData.grade = document.getElementById('gradeSelect').value;
            fieldData.class = document.getElementById('classInput').value;
            body.fieldData = fieldData;
            // 记住本次选择
            localStorage.setItem(lastKey, JSON.stringify({
                stage: selectedStage, schoolId: selectedSchool.schoolId,
                schoolName: selectedSchool.schoolName,
                grade: fieldData.grade, className: fieldData.class
            }));
        }

        fetch('/api/form/submit', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        }).then(function(r) { return r.json(); }).then(function(d) {
            var msg = document.getElementById('submitMsg'); msg.style.display = '';
            if (d.success) {
                msg.innerHTML = '<span class="text-success">✅ 提交成功！</span>';
                setTimeout(function() { window.location.href = '/form/success'; }, 1000);
            } else {
                msg.innerHTML = '<span class="text-danger">❌ ' + (d.error || '提交失败') + '</span>';
                btn.disabled = false; btn.textContent = '提交';
            }
        });
    });
</script>
```

- [ ] **Step 2: 手工验证（无单测，需本地起服务）**

1. `mvn spring-boot:run` 启动。
2. 造一个县区码活码（`school_id="county:白银区"`，`region_district="白银区"`，`form_template_id` 指向含 `grade`/`class` 字段的模板），拿到活码 ID。
3. 访问 `/form/{qrCodeId}?c={customerId}`。
4. 验证：点「小学」→ 学校列表只显示小学；搜 `by`（拼音首字母）能过滤「白银一小」；选年级班级提交；二次访问出现「上次选择」置顶，点击回填。
5. 验证提交后 `field_data` 含 `grade`/`class`，且 `TRANSFER_STREAM_KEY` 出现转接事件（`redis-cli XLEN wecom:transfer:stream`）。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/templates/form/fill.html
git commit -m "feat: 县区码表单三级级联选校 + 拼音搜索 + 记忆上次选择"
```

---

### Task 7: 表单模板 tag_mapping 配置（数据，非代码）

**Files:**
- 无代码改动（数据配置）。

**Interfaces:**
- Consumes: 无。
- Produces: 县区码所用表单模板的 `tag_mapping` 配置正确。

- [ ] **Step 1: 确认县区码表单模板 tag_mapping**

县区码活码的 `form_template_id` 指向的表单模板，其 `tag_mapping` 必须配置为：

```json
{"grade":"tag","class":"tag"}
```

（若模板还含 `child_name` 字段，可加 `"child_name":"remark"`，见 `docs/superpowers/plans/2026-06-21-welcome-form-inheritance-plan.md` 默认示例。）

此配置使 `TagService.applyFormTags` 自动把 `grade`/`class` 打成企微标签（`TagType.form` + `source="form"`），无需改代码。可通过管理后台「表单模板」编辑页设置，或直接 SQL 更新 `form_template.tag_mapping`。

- [ ] **Step 2: 验证打标**

提交县区码表单后，确认客户在企微侧被打上「学校标签 + 年级标签 + 班级标签」（`TagService.applyFormTags` 通过 `form_submit` 事件异步执行）。

- [ ] **Step 3: 提交（如涉及 seed/迁移 SQL）**

```bash
git add <seed/migration 文件>
git commit -m "chore: 县区码表单模板 grade/class tag_mapping 配置"
```

---

## Self-Review 结论

- **Spec 覆盖**：§4.2 两个接口 → Task 4；§3.2/3.3 解析链 + 转接 → Task 3/5；§2.2/2.3/2.4 三级级联 + 搜索 + 记忆 → Task 6；§4.1 学校列表来源 → Task 1；§5 年级班级标签 → Task 7；§7 边界（未分类/学区码兜底/无活码不转接）→ Task 2/3 已覆盖。
- **类型一致性**：`SchoolOption` / `TransferTarget` / `initiateCountyTransfer` 在 Task 2/3/4/5 中签名一致；`schoolId` 顶层字段在 Task 5/6 一致。
- **无占位符**：所有步骤含完整代码与命令。

## Execution Handoff

计划已保存。两个执行选项（见下一条消息）。
