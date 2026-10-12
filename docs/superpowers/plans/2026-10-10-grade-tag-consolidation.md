# 年级标签归并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把历史散落在「学校-{市}」标签组里的年级标签统一归并到企微「年级」标签组，并让本地 `tag` / `customer_tag` 账目与企微一致。

**Architecture:** 新增一个运维型 `GradeTagMigrationService`，分四段执行：Phase A 逐行分类（R1/R2/R3）+ 逐客户对账 + 按 `wecom_tag_id` 重接本地账目；Phase B 删除企微「学校-*」组里的 12 个年级同名重复标签（精确名白名单）；Phase C 清理本地孤儿 tag 行；Phase D 校验。进度/报告写入新表 `grade_tag_migration_progress`（只用于观测与断点续跑，不承载正确性 —— 所有写操作本身幂等）。由 `TagMigrationController` + `admin/tag-migration.html` 提供 dry-run、启动、看板。

**Tech Stack:** Spring Boot 3 / Java 17、Spring Data JPA、Thymeleaf + Bootstrap、MySQL (Flyway)、Redis（`ScheduledJobGuard` 分布式锁）、企微 API（`WecomApiClient`）。

**Spec:** `docs/superpowers/specs/2026-10-10-grade-tag-consolidation-design.md`

## Global Constraints

- **禁止按名字重接线，必须按 `wecom_tag_id` 重接线。** 每学期运营手工把企微年级标签名改一级（升学），本地行名与企微当前名可能差一级，属正常（见 memory `grade-tag-semester-promotion`），按名会指错。
- **范围只含 12 个中小学年级名**：`一年级,二年级,三年级,四年级,五年级,六年级,七年级,八年级,九年级,高一,高二,高三`。幼儿园（小班/中班/大班/幼儿园小班…）与「高中毕业」本次不动。
- **Phase B 必须精确名字匹配**，绝不能用 `LIKE '%班%'` —— `学校-*` 组里有 `中班`/`小班`，还有学校名 `舟曲县憨班镇中心小学`（landmine）。
- 企微限流按现有模式：每 100 次 API 调用后 `Thread.sleep(10_000)`。
- 双节点 active-active（生产 ALB 后 10.0.0.231/232），迁移 Job 必须用 `ScheduledJobGuard` 加锁。
- tag 行 / customer_tag 行的写必须走 `TagInsertService` / `CustomerTagInsertService` 的 `REQUIRES_NEW` 独立事务，不得在调用方事务里 `save`（外键自锁，见 memory `tag-bind-fk-self-lock`）。
- **不改 `TagService.getOrCreateTag()` 的漂移修复逻辑** —— 根因已由 `docs/data-repair-20261008-grade-tag-group.sql`（模板映射改 `tag:年级`）堵住；本计划只需把历史重复标签删干净。
- 完成标识：`Phase A 完成后才能跑 Phase B`（否则删掉旧标签后 R2/R3 客户在企微就没年级标签了）。
- 生产部署归用户：本计划只产出代码与验证命令，不代跑 `deploy.sh` / 重启。
- 每个 Task 结束各提交一次；提交信息用 `feat:` / `test:` / `fix:` 前缀（与仓库现有风格一致）。

## Review Focus

Spec 隐含但没有任何任务测试覆盖的输入/故障模式，最可能伤到人的排前面。每条都已在下述任务里加上对应测试。

1. **升学改名（本地行名与企微「年级」组当前名差一级）**：按名补打会把客户打错一级；按 wid 重接线才正确。→ Task 3（分类只认 wid）、Task 6（R2 先读企微按 id 取目标）。
2. **R2 客户企微侧其实已带「年级」组标签，只是标签名与本地行名不同**：此时必须按企微实际 tag id 重接线，**不得**再补打一个新标签。→ Task 6。
3. **同名重复行导致 `uk_customer_tag (customer_id, tag_id)` 唯一键冲突**：重接线时要删掉多余那条重复行，而不是抛异常中断。→ Task 4。
4. **客户在企微已被删 / 无 `current_agent`**：`markTag` 会因空 userid 或客户不存在抛错（84061 等）；必须跳过并记录，不能整批失败。→ Task 6。
5. **Phase B 误删**：`学校-*` 组里的 `中班`/`小班`/学校名 `舟曲县憨班镇中心小学` 与 12 个年级名相邻，白名单必须精确匹配。→ Task 7。

---

## 文件结构

**新建：**

| 路径 | 职责 |
|---|---|
| `src/main/resources/db/migration/V22__grade_tag_migration_progress.sql` | 进度/报告表 |
| `src/main/java/com/bookstore/qrcode/entity/GradeTagMigrationProgress.java` | 进度行实体 |
| `src/main/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepository.java` | 进度读写 |
| `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java` | Phase A–D 全部逻辑 + 报告 |
| `src/main/java/com/bookstore/qrcode/controller/TagMigrationController.java` | 看板 / dry-run / 启动 / 删旧标签 |
| `src/main/resources/templates/admin/tag-migration.html` | 看板页面 |
| `src/test/java/com/bookstore/qrcode/wecom/WecomApiClientDelCorpTagTest.java` | `delCorpTag` 单测 |
| `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java` | 服务单测（mock `WecomApiClient`） |

**修改：**

| 路径 | 改动 |
|---|---|
| `.../wecom/WecomApiClient.java` | 新增 `delCorpTag(List<String>)` |
| `.../repository/TagRepository.java` | 新增 `findFirstByWecomTagIdAndGroupKeyword`、`findGradeNamedRowsOutsideGradeGroup` |
| `.../repository/CustomerTagRepository.java` | 新增 `rewireTag`（@Modifying）、`countByTagIdIn`、`findByTagIdAndIdGreaterThanOrderByIdAsc`、`countByTagId` |
| `.../service/CustomerTagInsertService.java` | 新增 `rewire(Long customerId, Long fromTagId, Long toTagId)`（REQUIRES_NEW） |
| `.../config/SecurityConfig.java` | `/admin/tag-migration/**` 限 ADMIN |

---

### Task 1: 进度表 + 实体 + 仓库

**Files:**
- Create: `src/main/resources/db/migration/V22__grade_tag_migration_progress.sql`
- Modify: `src/test/resources/schema-test.sql`（H2 测试库表结构，`ddl-auto: none` + `spring.sql.init.schema-locations`，新表必须手工补上）
- Create: `src/main/java/com/bookstore/qrcode/entity/GradeTagMigrationProgress.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepository.java`
- Test: `src/test/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepositoryTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - 表 `grade_tag_migration_progress(customer_id, local_tag_id, row_class, action, ...)`，唯一键 `uk_gm_progress_item (customer_id, local_tag_id)`。
  - `GradeTagMigrationProgressRepository extends JpaRepository<GradeTagMigrationProgress, Long>`；
    `List<Object[]> findProcessedKeys()`（返回 `[customerId, localTagId]` 对）、
    `long countByRowClass(String rowClass)`、
    `long countByRequiresReview(boolean requiresReview)`、
    `List<GradeTagMigrationProgress> findTop200ByRequiresReviewOrderByIdAsc(boolean requiresReview)`。

- [ ] **Step 1: 写迁移 SQL**

创建 `src/main/resources/db/migration/V22__grade_tag_migration_progress.sql`：

```sql
-- V22: 年级标签归并进度/报告表
-- 记录 Phase A 逐客户对账的结果，供后台看板展示与人工复核；同时按
-- (customer_id, local_tag_id) 作为已处理标记，使迁移 Job 可断点续跑。
-- 注意：此表【不承载正确性】——所有写操作（markTag add、按 wid 重接线）本身幂等，
-- 重跑 = 续跑，最坏情况只是重复处理一遍。
-- 使用 CREATE TABLE IF NOT EXISTS 保证幂等。

CREATE TABLE IF NOT EXISTS grade_tag_migration_progress (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id          BIGINT       NOT NULL COMMENT '客户主键',
    external_userid      VARCHAR(100) COMMENT '客户企微 external_userid，便于人工核对',
    row_class            VARCHAR(4)   NOT NULL COMMENT '行级分类 R1/R2/R3',
    local_tag_id         BIGINT       NOT NULL COMMENT '迁移前客户绑定的非年级组 tag 行 id',
    local_tag_name       VARCHAR(100) COMMENT '迁移前本地行名（升学改名时可能与企微当前名不一致）',
    target_wecom_tag_id  VARCHAR(50)  COMMENT '归并后应指向的「年级」组企微标签 id',
    target_tag_id        BIGINT       COMMENT '归并后本地 canonical tag 行 id',
    action               VARCHAR(40)  NOT NULL COMMENT 'LOCAL_ONLY/WECOM_RELINK/WECOM_MARKED/WECOM_MARK_FAILED/SKIPPED_NO_AGENT',
    requires_review      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=按本地名补打（升学可能差一级），需人工复核',
    note                 VARCHAR(255) COMMENT '备注（错误信息等）',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gm_progress_item (customer_id, local_tag_id),
    INDEX idx_gm_progress_class (row_class, requires_review)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级标签归并进度/报告表';
```

- [ ] **Step 2: 写实体**

创建 `src/main/java/com/bookstore/qrcode/entity/GradeTagMigrationProgress.java`：

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 年级标签归并进度/报告行（仅观测与断点续跑，不承载正确性）。 */
@Entity
@Table(name = "grade_tag_migration_progress")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GradeTagMigrationProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "external_userid", length = 100)
    private String externalUserid;

    /** 行级分类：R1/R2/R3 */
    @Column(name = "row_class", nullable = false, length = 4)
    private String rowClass;

    @Column(name = "local_tag_id", nullable = false)
    private Long localTagId;

    @Column(name = "local_tag_name", length = 100)
    private String localTagName;

    @Column(name = "target_wecom_tag_id", length = 50)
    private String targetWecomTagId;

    @Column(name = "target_tag_id")
    private Long targetTagId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "requires_review", nullable = false)
    @Builder.Default
    private Boolean requiresReview = false;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: 写仓库**

创建 `src/main/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepository.java`：

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface GradeTagMigrationProgressRepository
        extends JpaRepository<GradeTagMigrationProgress, Long> {

    /** 已处理工作项的自然键集合，用于断点续跑：每行返回 [customerId, localTagId]。 */
    @Query("select p.customerId, p.localTagId from GradeTagMigrationProgress p")
    List<Object[]> findProcessedKeys();

    long countByRowClass(String rowClass);

    long countByRequiresReview(boolean requiresReview);

    List<GradeTagMigrationProgress> findTop200ByRequiresReviewOrderByIdAsc(boolean requiresReview);
}
```

- [ ] **Step 4: 补 H2 测试库建表脚本**

在 `src/test/resources/schema-test.sql` 的 `customer_tag` 段之后追加（H2 语法，风格对齐同文件其它表）：

```sql
-- grade_tag_migration_progress：年级标签归并进度/报告表
CREATE TABLE IF NOT EXISTS grade_tag_migration_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    external_userid VARCHAR(100),
    row_class VARCHAR(4) NOT NULL,
    local_tag_id BIGINT NOT NULL,
    local_tag_name VARCHAR(100),
    target_wecom_tag_id VARCHAR(50),
    target_tag_id BIGINT,
    action VARCHAR(40) NOT NULL,
    requires_review BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gm_progress_item UNIQUE (customer_id, local_tag_id)
);
CREATE INDEX IF NOT EXISTS idx_gm_progress_class ON grade_tag_migration_progress (row_class, requires_review);
```

- [ ] **Step 5: 写测试**

创建 `src/test/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepositoryTest.java`（用 `@DataJpaTest @ActiveProfiles("test")`：schema 由 `application-test.yml` 的 `spring.sql.init.schema-locations` 自动加载，无需 `@Sql`）：

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GradeTagMigrationProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class GradeTagMigrationProgressRepositoryTest {

    @Autowired
    private GradeTagMigrationProgressRepository repo;

    @Test
    @DisplayName("进度行可写入并按 row_class / requires_review 统计")
    void savesAndCounts() {
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(1L).rowClass("R1").localTagId(100L).action("LOCAL_ONLY").build());
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(2L).rowClass("R3").localTagId(101L).action("WECOM_MARKED")
            .requiresReview(true).build());

        assertEquals(1, repo.countByRowClass("R1"));
        assertEquals(1, repo.countByRowClass("R3"));
        assertEquals(1, repo.countByRequiresReview(true));
        assertEquals(1, repo.findTop200ByRequiresReviewOrderByIdAsc(true).size());
    }

    @Test
    @DisplayName("findProcessedKeys 返回 (customerId, localTagId) 对")
    void findProcessedKeysReturnsPairs() {
        repo.save(GradeTagMigrationProgress.builder()
            .customerId(7L).rowClass("R2").localTagId(200L).action("WECOM_RELINK").build());

        List<Object[]> keys = repo.findProcessedKeys();
        assertEquals(1, keys.size());
        assertEquals(7L, ((Number) keys.get(0)[0]).longValue());
        assertEquals(200L, ((Number) keys.get(0)[1]).longValue());
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `./mvnw -q -Dtest=GradeTagMigrationProgressRepositoryTest test`
Expected: PASS（2 个用例）。

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/db/migration/V22__grade_tag_migration_progress.sql \
        src/test/resources/schema-test.sql \
        src/main/java/com/bookstore/qrcode/entity/GradeTagMigrationProgress.java \
        src/main/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepository.java \
        src/test/java/com/bookstore/qrcode/repository/GradeTagMigrationProgressRepositoryTest.java
git commit -m "feat: 年级标签归并进度/报告表 V22 + 实体仓库"
```

---

### Task 2: `WecomApiClient.delCorpTag`

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`（在 `markTag` 之后、`// 在职继承` 注释之前新增）
- Test: `src/test/java/com/bookstore/qrcode/wecom/WecomApiClientDelCorpTagTest.java`

**Interfaces:**
- Consumes: `private String postForJson(String url, String jsonBody)`、`private JsonNode parseAndCheck(String resp, String action)`、`getAccessToken()`。
- Produces: `public JsonNode delCorpTag(List<String> tagIds)` —— 调用 `POST /cgi-bin/externalcontact/del_corp_tag`，body `{"tag_id":[...]}`；非零 errcode 抛 `WecomApiException`。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/bookstore/qrcode/wecom/WecomApiClientDelCorpTagTest.java`。构造手法照抄 `WecomApiClientTransferResultTest`：反射调 5 参构造器 `(WecomConfig, int, int, RestTemplateBuilder, ObjectMapper)`，再反射取私有 `restTemplate` 字段绑 `MockRestServiceServer`：

```java
package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WecomApiClientDelCorpTagTest {

    private WecomApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        client = createClient();
        Field rtField = WecomApiClient.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        server = MockRestServiceServer.createServer((RestTemplate) rtField.get(client));
    }

    /** 与 WecomApiClientTransferResultTest#createClient 同款：预置未过期 token，避免额外的 gettoken 调用。 */
    private WecomApiClient createClient() throws Exception {
        WecomConfig config = new WecomConfig();
        config.setCorpId("test_corp");
        config.setCorpSecret("test_secret");
        config.setAccessToken("test_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 300);

        java.lang.reflect.Constructor<WecomApiClient> ctor =
            WecomApiClient.class.getDeclaredConstructor(
                WecomConfig.class, int.class, int.class, RestTemplateBuilder.class, ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(config, 3, 10, new RestTemplateBuilder(), new ObjectMapper());
    }

    @Test
    @DisplayName("delCorpTag 调用 del_corp_tag 且请求体为 tag_id 数组")
    void shouldCallDelCorpTag() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/del_corp_tag?access_token=test_token"))
            .andExpect(content().string(containsString("\"tag_id\":[\"a\",\"b\"]")))
            .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        JsonNode resp = client.delCorpTag(List.of("a", "b"));

        assertEquals(0, resp.get("errcode").asInt());
        server.verify();
    }

    @Test
    @DisplayName("delCorpTag 遇非零 errcode 抛异常")
    void shouldThrowOnError() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/del_corp_tag?access_token=test_token"))
            .andRespond(withSuccess("{\"errcode\":40058,\"errmsg\":\"invalid tagid\"}",
                MediaType.APPLICATION_JSON));

        assertThrows(WecomApiException.class, () -> client.delCorpTag(List.of("x")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -Dtest=WecomApiClientDelCorpTagTest test`
Expected: FAIL，编译错误 `cannot find symbol: method delCorpTag(List<String>)`。

- [ ] **Step 3: 实现**

在 `WecomApiClient.java` 的 `markTag` 方法之后插入：

```java
    /**
     * 删除企业标签（corp 级）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/del_corp_tag}
     * <pre>
     * 请求: {"tag_id": ["etxxx", "etyyy"]}
     * 响应: {"errcode": 0, "errmsg": "ok"}
     * </pre>
     * 删除后企微会自动把该标签从所有客户身上摘掉。
     *
     * @param tagIds 要删除的企微标签 ID 列表
     * @throws WecomApiException 删除失败时抛出
     */
    public JsonNode delCorpTag(List<String> tagIds) {
        String url = BASE_URL + "/externalcontact/del_corp_tag?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("tag_id", tagIds);
            String body = objectMapper.writeValueAsString(bodyMap);
            String respStr = postForJson(url, body);
            return parseAndCheck(respStr, "删除企业标签");
        } catch (WecomApiException e) {
            throw e;
        } catch (Exception e) {
            throw new WecomTransientException(-1,
                "删除企业标签失败: " + e.getMessage(), null);
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -Dtest=WecomApiClientDelCorpTagTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java \
        src/test/java/com/bookstore/qrcode/wecom/WecomApiClientDelCorpTagTest.java
git commit -m "feat: WecomApiClient 新增 delCorpTag（del_corp_tag）"
```

---

### Task 3: 企微标签索引 + A0 行级分类（R1/R2/R3）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/TagRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`

**Interfaces:**
- Consumes: `WecomApiClient.getCorpTagList()` → `JsonNode`（结构 `tag_group[].{group_name, group_id, tag[].{id, name}}`）；`TagRepository`。
- Produces（`GradeTagMigrationService`）：
  - `record WecomTagIndex(Map<String,String> wid2name, Map<String,String> wid2group, Map<String,String> groupIdByName, Set<String> gradeWids)`，另有便捷方法 `boolean hasGradeTag(List<String> wids)`、`String gradeWidByName(String name)`。
  - `WecomTagIndex loadWecomTagIndex()`
  - `enum RowClass { R1, R2, R3 }`
  - `record GradeTagRow(long tagId, String name, String groupKeyword, String wecomTagId, RowClass rowClass)`
  - `List<GradeTagRow> classifyLocalGradeRows(WecomTagIndex index)`
  - `static final List<String> GRADE_NAMES`（12 个名字，顺序固定）
  - `static final String GRADE_GROUP = "年级"`

- [ ] **Step 1: 给 TagRepository 加两个查询**

在 `TagRepository.java` 末尾（`findFirstByNameAndGroupKeyword` 之后）加：

```java
    /**
     * 按企微标签 ID + 组关键词定位本地行（canonical 行解析用）。
     *
     * <p>归并必须按 {@code wecom_tag_id} 接线而非按名字：每学期运营会把企微年级标签名
     * 改一级（升学），本地行名与企微当前名可能差一级。</p>
     */
    Optional<Tag> findFirstByWecomTagIdAndGroupKeyword(String wecomTagId, String groupKeyword);

    /**
     * 取所有「名字是年级名、但不属于年级组」的本地 tag 行 —— 即待归并的重复行。
     *
     * <p>用 {@code group_keyword <> '年级'} 而不是 {@code LIKE '学校%'}：历史遗留行
     * 的 group_keyword 可能是空串，但它们同样是重复行。</p>
     */
    @Query("select t from Tag t where t.name in :names and t.groupKeyword <> '年级'")
    List<Tag> findGradeNamedRowsOutsideGradeGroup(@Param("names") Collection<String> names);
```

在文件顶部 import 区补：

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
```

- [ ] **Step 2: 写失败测试**

创建 `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`（照抄 `TagServiceTest` 的 Mockito 风格）：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradeTagMigrationServiceTest {

    @Mock WecomApiClient wecomApi;
    @Mock TagRepository tagRepo;
    @Mock CustomerRepository customerRepo;
    @Mock CustomerTagRepository customerTagRepo;
    @Mock TagInsertService tagInsertService;
    @Mock CustomerTagInsertService customerTagInsertService;
    @Mock GradeTagMigrationProgressRepository progressRepo;
    @Mock ScheduledJobGuard jobGuard;

    @InjectMocks GradeTagMigrationService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 企微 get_corp_tag_list 响应：年级组含「一年级」+「七年级」；学校-{市}组含与年级同名的重复标签 */
    private static final String CORP_TAG_LIST = """
        {"errcode":0,"errmsg":"ok","tag_group":[
          {"group_name":"年级","group_id":"GRADE","tag":[
            {"id":"g1","name":"一年级"},{"id":"g7","name":"七年级"}]},
          {"group_name":"学校-天水市","group_id":"SCH-TS","tag":[
            {"id":"s1","name":"一年级"},{"id":"s7","name":"七年级"},
            {"id":"s2","name":"中班"}]},
          {"group_name":"班级","group_id":"CLS","tag":[{"id":"c1","name":"一班"}]}
        ]}""";

    private Tag row(long id, String name, String gk, String wid) {
        return Tag.builder().id(id).name(name).groupKeyword(gk).wecomTagId(wid).build();
    }

    @Test
    @DisplayName("loadWecomTagIndex 解析组名/id/标签名，并圈出年级组 id 集合")
    void loadsIndex() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));

        var idx = service.loadWecomTagIndex();

        assertEquals("年级", idx.wid2group().get("g1"));
        assertEquals("一年级", idx.wid2name().get("g1"));
        assertEquals("学校-天水市", idx.wid2group().get("s1"));
        assertEquals("GRADE", idx.groupIdByName().get("年级"));
        assertEquals(2, idx.gradeWids().size());
        assertEquals("g1", idx.gradeWidByName("一年级"));
    }

    @Test
    @DisplayName("A0 分类：wid 在年级组→R1；wid 在学校组同名→R2；wid 企微不存在/为空→R3")
    void classifiesRows() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection())).thenReturn(List.of(
            row(1184, "一年级", "学校-天水市", "g1"), // R1：本地行挂在校园组，但 wid 已漂到年级组标签
            row(2127, "一年级", "学校-天水市", "s1"), // R2：wid 是校园组同名标签
            row(125,  "七年级", "", "DEAD"),          // R3（wid 企微已不存在）
            row(126,  "八年级", "", null)             // R3（wid 为空）
        ));

        var rows = service.classifyLocalGradeRows(service.loadWecomTagIndex());

        assertEquals(4, rows.size());
        assertEquals(GradeTagMigrationService.RowClass.R1, rows.get(0).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R2, rows.get(1).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R3, rows.get(2).rowClass());
        assertEquals(GradeTagMigrationService.RowClass.R3, rows.get(3).rowClass());
    }

    @Test
    @DisplayName("范围只含 12 个中小学年级名，不含幼儿园与高中毕业")
    void gradeNamesAreExactlyTwelve() {
        assertEquals(List.of("一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
            "七年级", "八年级", "九年级", "高一", "高二", "高三"),
            GradeTagMigrationService.GRADE_NAMES);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: FAIL，编译错误 `cannot find symbol: class GradeTagMigrationService`。

- [ ] **Step 4: 实现**

创建 `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 年级标签归并 —— 把散落在「学校-{市}」组的年级标签收敛到「年级」组。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-10-grade-tag-consolidation-design.md}。
 * 核心规则：<b>按 {@code wecom_tag_id} 接线，绝不按名字</b>（每学期升学改名会让本地名差一级）。</p>
 *
 * <p>四段：A0 行级分类（R1/R2/R3）→ A1 逐客户对账 → A2 按 wid 重接本地账目 →
 * B 删企微重复标签 → C 清本地孤儿行 → D 校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeTagMigrationService {

    /** 本次归并范围内的小学/初中/高中年级名（幼儿园与「高中毕业」不在内）。 */
    public static final List<String> GRADE_NAMES = List.of(
        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
        "七年级", "八年级", "九年级", "高一", "高二", "高三");

    public static final String GRADE_GROUP = "年级";

    /** 判断一个组是不是「非年级的重复组」时排除的核心组名。 */
    public static final Set<String> CORE_GROUPS =
        Set.of("年级", "班级", "市州", "县区", "渠道来源");

    private final WecomApiClient wecomApi;
    private final TagRepository tagRepo;
    private final CustomerRepository customerRepo;
    private final CustomerTagRepository customerTagRepo;
    private final TagInsertService tagInsertService;
    private final CustomerTagInsertService customerTagInsertService;
    private final GradeTagMigrationProgressRepository progressRepo;
    private final ScheduledJobGuard jobGuard;

    // ---------------------------------------------------------------- A0

    /** 企微标签全局索引：wid→名、wid→组名、组名→组 id、年级组 wid 集合。 */
    public record WecomTagIndex(Map<String, String> wid2name,
                                Map<String, String> wid2group,
                                Map<String, String> groupIdByName,
                                Set<String> gradeWids) {

        /** 这组标签 id 里是否已经带了「年级」组的年级标签。 */
        public boolean hasGradeTag(Collection<String> wids) {
            if (wids == null) return false;
            for (String w : wids) if (gradeWids.contains(w)) return true;
            return false;
        }

        /** 「年级」组里名叫 name 的标签 id；没有则返回 null（可能被升学改名改掉了）。 */
        public String gradeWidByName(String name) {
            for (String wid : gradeWids) {
                if (name.equals(wid2name.get(wid))) return wid;
            }
            return null;
        }
    }

    public WecomTagIndex loadWecomTagIndex() {
        JsonNode resp = wecomApi.getCorpTagList();
        // LinkedHashMap：保持企微返回顺序，使 Phase B 的「先删 1 个」探针每次挑同一条
        Map<String, String> wid2name = new LinkedHashMap<>();
        Map<String, String> wid2group = new LinkedHashMap<>();
        Map<String, String> groupIdByName = new LinkedHashMap<>();
        Set<String> gradeWids = new LinkedHashSet<>();
        for (JsonNode g : resp.path("tag_group")) {
            String groupName = g.path("group_name").asText(null);
            if (groupName != null) groupIdByName.put(groupName, g.path("group_id").asText(null));
            for (JsonNode t : g.path("tag")) {
                String id = t.path("id").asText(null);
                if (id == null) continue;
                wid2name.put(id, t.path("name").asText(null));
                wid2group.put(id, groupName);
                if (GRADE_GROUP.equals(groupName)) gradeWids.add(id);
            }
        }
        return new WecomTagIndex(wid2name, wid2group, groupIdByName, gradeWids);
    }

    public enum RowClass { R1, R2, R3 }

    /** 本地一条「名为年级名、却不在年级组」的 tag 行的分类结果。 */
    public record GradeTagRow(long tagId, String name, String groupKeyword,
                              String wecomTagId, RowClass rowClass) {}

    /**
     * A0：把本地所有非年级组的年级名 tag 行分成三类。
     *
     * <ul>
     *   <li><b>R1</b>：wid 落在企微「年级」组 → 客户企微侧已对，只需本地重接线。</li>
     *   <li><b>R2</b>：wid 是企微某非年级组（「学校-{市}」）的同名标签 → 需逐客户判定。</li>
     *   <li><b>R3</b>：wid 为空或企微已不存在（legacy 空组行）→ 客户企微侧没有年级标签，需按本地名补打。</li>
     * </ul>
     */
    public List<GradeTagRow> classifyLocalGradeRows(WecomTagIndex index) {
        List<GradeTagRow> out = new ArrayList<>();
        for (var t : tagRepo.findGradeNamedRowsOutsideGradeGroup(GRADE_NAMES)) {
            String wid = t.getWecomTagId();
            RowClass cls;
            if (wid == null || wid.isBlank()) {
                cls = RowClass.R3;
            } else if (index.gradeWids().contains(wid)) {
                cls = RowClass.R1;
            } else if (index.wid2group().containsKey(wid)) {
                cls = RowClass.R2;
            } else {
                cls = RowClass.R3;
            }
            out.add(new GradeTagRow(t.getId(), t.getName(), t.getGroupKeyword(), wid, cls));
        }
        log.info("A0 行级分类完成: 共 {} 行, R1={}, R2={}, R3={}",
            out.size(),
            out.stream().filter(r -> r.rowClass() == RowClass.R1).count(),
            out.stream().filter(r -> r.rowClass() == RowClass.R2).count(),
            out.stream().filter(r -> r.rowClass() == RowClass.R3).count());
        return out;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/repository/TagRepository.java \
        src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java \
        src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java
git commit -m "feat: 年级标签 A0 行级分类（R1/R2/R3，按 wid 判定）"
```

---

### Task 4: 本地重接线原语（collision-safe）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/CustomerTagRepository.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerTagInsertService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/CustomerTagInsertServiceRewireTest.java`

**Interfaces:**
- Consumes: `CustomerTagRepository`。
- Produces:
  - `CustomerTagRepository.rewireTag(Long customerId, Long fromTagId, Long toTagId)` → `int`（`@Modifying` JPQL UPDATE，就地改 `tag_id`，保留 `source`/`tagged_at`）
  - `CustomerTagRepository.countByTagIdIn(Collection<Long>)` → `long`
  - `CustomerTagRepository.findByTagIdAndIdGreaterThanOrderByIdAsc(Long tagId, Long afterId, Pageable)` → `List<CustomerTag>`
  - `CustomerTagInsertService.rewire(Long customerId, Long fromTagId, Long toTagId)` → `boolean`（REQUIRES_NEW；目标行已存在则删源行返回 true；源行不存在返回 false）

- [ ] **Step 1: 给仓库加方法**

在 `CustomerTagRepository.java` 中加（顶部需 import `@Modifying`、`@Query`、`@Param`、`Pageable`、`Collection`）：

```java
    /** 就地改 tag_id，保留 source 与 tagged_at（保 TransferService.checkFormFilled 语义）。 */
    @Modifying
    @Query("update CustomerTag ct set ct.tagId = :toTagId "
        + "where ct.customerId = :customerId and ct.tagId = :fromTagId")
    int rewireTag(@Param("customerId") Long customerId,
                  @Param("fromTagId") Long fromTagId,
                  @Param("toTagId") Long toTagId);

    long countByTagIdIn(Collection<Long> tagIds);

    List<CustomerTag> findByTagIdAndIdGreaterThanOrderByIdAsc(Long tagId, Long afterId, Pageable pageable);
```

- [ ] **Step 2: 写失败测试**

创建 `src/test/java/com/bookstore/qrcode/service/CustomerTagInsertServiceRewireTest.java`（纯 Mockito，不碰 DB）：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.repository.CustomerTagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerTagInsertServiceRewireTest {

    @Mock CustomerTagRepository customerTagRepo;
    @InjectMocks CustomerTagInsertService service;

    @Test
    @DisplayName("目标行不存在时：就地 UPDATE tag_id，返回 true")
    void rewiresInPlace() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(false);
        when(customerTagRepo.rewireTag(1L, 100L, 200L)).thenReturn(1);

        assertTrue(service.rewire(1L, 100L, 200L));
        verify(customerTagRepo).rewireTag(1L, 100L, 200L);
        verify(customerTagRepo, never()).deleteByCustomerIdAndTagId(any(), any());
    }

    @Test
    @DisplayName("目标行已存在（重复行）时：删源行，返回 true，避免撞 uk_customer_tag")
    void deletesDuplicateWhenTargetExists() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(true);

        assertTrue(service.rewire(1L, 100L, 200L));
        verify(customerTagRepo).deleteByCustomerIdAndTagId(1L, 100L);
        verify(customerTagRepo, never()).rewireTag(any(), any(), any());
    }

    @Test
    @DisplayName("源行已被删（客户被删）时：不抛异常，返回 false")
    void returnsFalseWhenSourceGone() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(false);
        when(customerTagRepo.rewireTag(1L, 100L, 200L)).thenReturn(0);

        assertFalse(service.rewire(1L, 100L, 200L));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -Dtest=CustomerTagInsertServiceRewireTest test`
Expected: FAIL，编译错误 `cannot find symbol: method rewire`。

- [ ] **Step 4: 实现**

在 `CustomerTagInsertService.java` 的 `insert` 方法之后加：

```java
    /**
     * 把客户的某条年级标签绑定从 fromTagId 重接到 toTagId。
     *
     * <p>归并专用。就地 UPDATE 以保留 {@code source} 与 {@code tagged_at}：
     * {@link com.bookstore.qrcode.service.TransferService#checkFormFilled} 依赖 {@code source=form}，
     * 删表重建会把它抹成默认值。</p>
     *
     * <p>若客户已经绑定了 toTagId（同名重复行导致），直接删掉 fromTagId 那条多余行，
     * 否则 UPDATE 会撞唯一键 {@code uk_customer_tag (customer_id, tag_id)}。</p>
     *
     * @return 是否真的发生了变更；源行已被删除（客户被删）时返回 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rewire(Long customerId, Long fromTagId, Long toTagId) {
        if (customerTagRepo.existsByCustomerIdAndTagId(customerId, toTagId)) {
            customerTagRepo.deleteByCustomerIdAndTagId(customerId, fromTagId);
            return true;
        }
        return customerTagRepo.rewireTag(customerId, fromTagId, toTagId) > 0;
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -Dtest=CustomerTagInsertServiceRewireTest test`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/repository/CustomerTagRepository.java \
        src/main/java/com/bookstore/qrcode/service/CustomerTagInsertService.java \
        src/test/java/com/bookstore/qrcode/service/CustomerTagInsertServiceRewireTest.java
git commit -m "feat: customer_tag 重接线原语（就地改 tag_id，保留 source，撞唯一键时删重复行）"
```

---

### Task 5: canonical 行解析

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `TagRepository.findFirstByWecomTagIdAndGroupKeyword`、`TagInsertService.insertNew` / `updateWecomTagId`。
- Produces:
  - `record TargetTag(String wecomTagId, String name)` —— 归并目标（wid + 企微当前名）
  - `Long resolveCanonicalTagId(TargetTag target)` —— 返回本地「年级」组 canonical tag 行 id（不存在则按 `target.name()` 新建并回填 wid）；`target` 为 null 或 wid 空白时返回 null。

- [ ] **Step 1: 写失败测试**

在 `GradeTagMigrationServiceTest.java` 追加：

```java
    @Test
    @DisplayName("canonical 行已存在（wid 命中年级组行）→ 直接返回该行 id")
    void resolveCanonicalReturnsExisting() {
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        assertEquals(1190L, service.resolveCanonicalTagId(
            new GradeTagMigrationService.TargetTag("g7", "七年级")));
        org.mockito.Mockito.verify(tagInsertService, org.mockito.Mockito.never())
            .insertNew(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("canonical 行不存在 → 按 target.name() 新建 (名, 年级) 并回填 wid")
    void resolveCanonicalCreatesWhenMissing() {
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g9", "年级"))
            .thenReturn(java.util.Optional.empty());
        when(tagInsertService.insertNew("八年级", Tag.TagType.manual, null, "年级"))
            .thenReturn(Tag.builder().id(999L).name("八年级").groupKeyword("年级").build());

        assertEquals(999L, service.resolveCanonicalTagId(
            new GradeTagMigrationService.TargetTag("g9", "八年级")));
        org.mockito.Mockito.verify(tagInsertService).updateWecomTagId(999L, "g9");
    }

    @Test
    @DisplayName("target 为 null / wid 空白 → 返回 null，不建行")
    void resolveCanonicalNullForBlank() {
        assertNull(service.resolveCanonicalTagId(null));
        assertNull(service.resolveCanonicalTagId(new GradeTagMigrationService.TargetTag("  ", "一年级")));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: FAIL，编译错误 `cannot find symbol: method resolveCanonicalTagId`。

- [ ] **Step 3: 实现**

在 `GradeTagMigrationService` 中加：

```java
    // ---------------------------------------------------------------- A2

    /**
     * 归并目标：要指向的企微「年级」组标签 id，加上该标签当前的企微名。
     *
     * <p>带 name 是为了在 canonical 本地行缺失时能按<b>企微当前名</b>建行 —— 本地旧行名
     * 可能因升学改名差一级，落库会重新引入名/id 不一致。</p>
     */
    public record TargetTag(String wecomTagId, String name) {}

    /**
     * 求目标对应的本地「年级」组 canonical tag 行 id；不存在则按 {@code target.name()} 新建。
     *
     * <p>定位按 {@code wecom_tag_id}（不按名）：升学改名会让本地名差一级。</p>
     */
    public Long resolveCanonicalTagId(TargetTag target) {
        if (target == null || target.wecomTagId() == null || target.wecomTagId().isBlank()) return null;
        var existing = tagRepo.findFirstByWecomTagIdAndGroupKeyword(target.wecomTagId(), GRADE_GROUP);
        if (existing.isPresent()) return existing.get().getId();

        String name = target.name() != null ? target.name() : "";
        Tag created = tagInsertService.insertNew(name, Tag.TagType.manual, null, GRADE_GROUP);
        tagInsertService.updateWecomTagId(created.getId(), target.wecomTagId());
        log.info("新建 canonical 年级 tag 行: id={}, name={}, wid={}",
            created.getId(), name, target.wecomTagId());
        return created.getId();
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java \
        src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java
git commit -m "feat: canonical 年级 tag 行解析（按 wid 定位，缺则按企微名新建）"
```

---

### Task 6: A1/A2 逐客户对账 + 续跑 + 限流 + 分布式锁

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `WecomApiClient.getExternalContact(eid)` → `JsonNode`（`follow_user[].tags[].tag_id`）、`markTag(eid, userid, tagIds)`、`addCorpTag(name, groupId)`、`addCorpTagWithGroup(name, groupName)`；`CustomerRepository.findAllById`；`CustomerTagRepository.findByTagIdAndIdGreaterThanOrderByIdAsc`、`countByTagIdIn`；`CustomerTagInsertService.rewire`；`GradeTagMigrationProgressRepository`；`ScheduledJobGuard.tryAcquire/release`；`GradeTagMigrationService.TargetTag`（Task 5）。
- Produces:
  - `record MigrationReport(long rows, long customers, long localOnly, long wecomRelink, long wecomMarked, long wecomMarkFailed, long skippedNoAgent, long requiresReview, boolean dryRun)`
  - `MigrationReport runPhaseA(boolean dryRun)`
  - `static List<String> extractWecomTagIds(JsonNode externalContactResp)`
  - 常量 `JOB_LOCK_KEY = "lock:job:grade-tag-migration"`、`JOB_LOCK_TTL = Duration.ofHours(2)`

- [ ] **Step 1: 写失败测试**

在 `GradeTagMigrationServiceTest.java` 追加。**这些用例一律走 `runPhaseA(false)`** —— `dryRun=true` 会在 A0 分类后直接返回、根本不对账，用它测对账会永远为 0：

```java
    private static final String EXT_CONTACT_NO_GRADE = """
        {"errcode":0,"errmsg":"ok","external_contact":{"external_userid":"wm1"},
         "follow_user":[{"userid":"ag1","tags":[{"tag_id":"s7","type":1}]}]}""";

    private static final String EXT_CONTACT_HAS_GRADE = """
        {"errcode":0,"errmsg":"ok","external_contact":{"external_userid":"wm2"},
         "follow_user":[{"userid":"ag2","tags":[{"tag_id":"g7","type":1},{"tag_id":"s7","type":1}]}]}""";

    private com.bookstore.qrcode.entity.CustomerTag ct(long id, long customerId, long tagId) {
        return com.bookstore.qrcode.entity.CustomerTag.builder()
            .id(id).customerId(customerId).tagId(tagId).build();
    }

    private com.bookstore.qrcode.entity.Customer cust(long id, String eid, String agent) {
        return com.bookstore.qrcode.entity.Customer.builder()
            .id(id).externalUserid(eid).currentAgent(agent)
            .status(com.bookstore.qrcode.entity.Customer.CustomerStatus.active).build();
    }

    /** 一个客户、一条本地年级标签行的公共桩。 */
    private void stubSingleRow(String corpTagListJson, long tagRowId, String tagRowName,
                               String tagRowGk, String tagRowWid,
                               long customerId, String eid, String agent) throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(corpTagListJson));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(tagRowId, tagRowName, tagRowGk, tagRowWid)));
        when(customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq(tagRowId), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(ct(1L, customerId, tagRowId)))
            .thenReturn(List.of());
        when(customerRepo.findAllById(List.of(customerId)))
            .thenReturn(List.of(cust(customerId, eid, agent)));
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("tok");
    }

    @Test
    @DisplayName("extractWecomTagIds 汇总所有 follow_user 的 tag_id")
    void extractsTagIds() throws Exception {
        assertEquals(List.of("g7", "s7"),
            GradeTagMigrationService.extractWecomTagIds(MAPPER.readTree(EXT_CONTACT_HAS_GRADE)));
        assertTrue(GradeTagMigrationService.extractWecomTagIds(null).isEmpty());
    }

    @Test
    @DisplayName("R1 客户：零企微调用，只做本地重接线（canonical 已是本行时不写）")
    void r1IsLocalOnly() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 1184L, "一年级", "学校-天水市", "g1", 11L, "wmA", "agA");
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g1", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1184L).name("一年级").groupKeyword("年级").wecomTagId("g1").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.rows());
        assertEquals(1, report.localOnly());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .getExternalContact(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(jobGuard).release(
            org.mockito.ArgumentMatchers.eq(GradeTagMigrationService.JOB_LOCK_KEY),
            org.mockito.ArgumentMatchers.eq("tok"));
    }

    @Test
    @DisplayName("R2 客户已带年级组标签（异名，升学后）→ 按企微实际 id 重接线，不补打")
    void r2WithGradeTagRelinksByWecomId() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 2127L, "一年级", "学校-天水市", "s1", 12L, "wm2", "ag2");
        when(wecomApi.getExternalContact("wm2")).thenReturn(MAPPER.readTree(EXT_CONTACT_HAS_GRADE));
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomRelink());
        org.mockito.Mockito.verify(customerTagInsertService).rewire(12L, 2127L, 1190L);
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("R3 客户：按本地名补打「年级」组标签，并标记 requires_review=1")
    void r3MarksByLocalNameAndFlagsReview() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 13L, "wm3", "ag3");
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarked());
        assertEquals(1, report.requiresReview());
        org.mockito.Mockito.verify(wecomApi).markTag("wm3", "ag3", List.of("g7"));
        org.mockito.Mockito.verify(customerTagInsertService).rewire(13L, 125L, 1190L);
    }

    @Test
    @DisplayName("R2 客户企微侧完全没有年级标签 → 按本地名补打进年级组，标 review")
    void r2WithoutGradeTagMarksByName() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 2127L, "七年级", "学校-天水市", "s7", 15L, "wm1", "ag1");
        when(wecomApi.getExternalContact("wm1")).thenReturn(MAPPER.readTree(EXT_CONTACT_NO_GRADE));
        when(tagRepo.findFirstByWecomTagIdAndGroupKeyword("g7", "年级"))
            .thenReturn(java.util.Optional.of(
                Tag.builder().id(1190L).name("七年级").groupKeyword("年级").wecomTagId("g7").build()));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarked());
        assertEquals(1, report.requiresReview());
        org.mockito.Mockito.verify(wecomApi).markTag("wm1", "ag1", List.of("g7"));
    }

    @Test
    @DisplayName("无 current_agent 的客户：跳过并计数，不调 markTag")
    void skipsCustomerWithoutAgent() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 14L, "wm4", " ");

        var report = service.runPhaseA(false);

        assertEquals(1, report.skippedNoAgent());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("markTag 抛错（客户已删/84061）不炸整批：计入 wecomMarkFailed，不重接线（留旧绑定给重跑）")
    void markFailureIsIsolated() throws Exception {
        stubSingleRow(CORP_TAG_LIST, 125L, "七年级", "", "DEAD", 13L, "wm3", "ag3");
        org.mockito.Mockito.doThrow(new RuntimeException("84061 not external contact"))
            .when(wecomApi).markTag("wm3", "ag3", List.of("g7"));

        var report = service.runPhaseA(false);

        assertEquals(1, report.wecomMarkFailed());
        org.mockito.Mockito.verify(customerTagInsertService, org.mockito.Mockito.never())
            .rewire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(jobGuard).release(
            org.mockito.ArgumentMatchers.eq(GradeTagMigrationService.JOB_LOCK_KEY),
            org.mockito.ArgumentMatchers.eq("tok"));
    }

    @Test
    @DisplayName("拿不到分布式锁时立即抛错，不重复跑")
    void refusesWhenLockHeld() throws Exception {
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.runPhaseA(false));
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never()).getCorpTagList();
    }

    @Test
    @DisplayName("dry-run：只分类统计，零企微写、零写库")
    void dryRunDoesNothing() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(125L, "七年级", "", "DEAD")));
        when(customerTagRepo.countByTagIdIn(List.of(125L))).thenReturn(7L);

        var report = service.runPhaseA(true);

        assertTrue(report.dryRun());
        assertEquals(1, report.rows());
        assertEquals(7, report.customers());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(customerTagInsertService, org.mockito.Mockito.never())
            .rewire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(progressRepo, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("续跑：进度表里已有该 (customer, tagRow) 时跳过，不再调企微")
    void resumesFromProgress() throws Exception {
        when(jobGuard.tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("tok");
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(125L, "七年级", "", "DEAD")));
        when(progressRepo.findProcessedKeys())
            .thenReturn(List.<Object[]>of(new Object[]{13L, 125L}));
        when(customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq(125L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(ct(3L, 13L, 125L)))
            .thenReturn(List.of());
        when(customerRepo.findAllById(List.of(13L))).thenReturn(List.of(cust(13L, "wm3", "ag3")));

        var report = service.runPhaseA(false);

        assertEquals(0, report.wecomMarked());
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .markTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
```

> 注意 `resumesFromProgress` 里 `findByTagIdAndIdGreaterThanOrderByIdAsc` 仍会被调用（客户端是分页读），跳过判断在客户粒度上。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: FAIL，编译错误 `cannot find symbol: method runPhaseA` / `extractWecomTagIds`。

- [ ] **Step 3: 实现**

在 `GradeTagMigrationService` 中加。类顶部补 import：`com.bookstore.qrcode.entity.Customer`、`com.bookstore.qrcode.entity.CustomerTag`、`com.bookstore.qrcode.entity.Tag`、`com.bookstore.qrcode.entity.GradeTagMigrationProgress`、`java.time.Duration`、`org.springframework.data.domain.PageRequest`、`org.springframework.scheduling.annotation.Async`。

```java
    // ---------------------------------------------------------------- A1

    public static final String JOB_LOCK_KEY = "lock:job:grade-tag-migration";
    public static final Duration JOB_LOCK_TTL = Duration.ofHours(2);

    /** 分页批大小：一次从 customer_tag 取多少条（本地读，不是 API 调用）。 */
    private static final int PAGE_SIZE = 500;
    /** 每 N 次企微 API 调用休眠一次（沿用既有 600/min 限流模式）。 */
    private static final int API_SLEEP_EVERY = 100;
    private static final long API_SLEEP_MS = 10_000;

    public record MigrationReport(long rows, long customers, long localOnly, long wecomRelink,
                                  long wecomMarked, long wecomMarkFailed, long skippedNoAgent,
                                  long requiresReview, boolean dryRun) {}

    /**
     * 逐客户对账计数器。做成<b>局部变量</b>贯穿调用，不做成字段 ——
     * 本服务是单例，字段会被第二次运行或并发触发污染。
     */
    private static final class Counters {
        long rows, customers, localOnly, wecomRelink, wecomMarked, wecomMarkFailed,
             skippedNoAgent, requiresReview;
        int apiCalls;

        MigrationReport toReport(boolean dryRun) {
            return new MigrationReport(rows, customers, localOnly, wecomRelink, wecomMarked,
                wecomMarkFailed, skippedNoAgent, requiresReview, dryRun);
        }

        @Override public String toString() {
            return String.format("rows=%d customers=%d localOnly=%d relink=%d marked=%d "
                    + "markFailed=%d skipped=%d review=%d apiCalls=%d",
                rows, customers, localOnly, wecomRelink, wecomMarked, wecomMarkFailed,
                skippedNoAgent, requiresReview, apiCalls);
        }
    }

    /** 从 {@code externalcontact/get} 响应里汇总所有 follow_user 的 tag_id。 */
    public static List<String> extractWecomTagIds(JsonNode resp) {
        List<String> out = new ArrayList<>();
        if (resp == null) return out;
        for (JsonNode fu : resp.path("follow_user")) {
            for (JsonNode tg : fu.path("tags")) {
                String id = tg.path("tag_id").asText(null);
                if (id != null) out.add(id);
            }
        }
        return out;
    }

    /**
     * Phase A：逐行分类 → 逐客户对账 → 按 wid 重接本地账目。
     *
     * <p>幂等：R2/R3 的 {@code markTag} 是 add（重复 add 已存在标签无副作用），A2 重接线
     * 若已是 canonical 行则是空操作。因此重跑 = 续跑；{@code grade_tag_migration_progress}
     * 只用来省时（跳过已处理项）与出报告，不承载正确性。</p>
     *
     * <p>{@code dryRun=true} 时：只做 A0 分类并统计，<b>零企微调用、零写库</b>。</p>
     */
    public MigrationReport runPhaseA(boolean dryRun) {
        if (dryRun) {
            WecomTagIndex index = loadWecomTagIndex();
            List<GradeTagRow> rows = classifyLocalGradeRows(index);
            long customers = rows.stream()
                .mapToLong(r -> customerTagRepo.countByTagIdIn(List.of(r.tagId())))
                .sum();
            return new MigrationReport(rows.size(), customers, 0, 0, 0, 0, 0, 0, true);
        }

        // 先拿锁再读企微：避免双节点同时各扫一遍分类
        String token = jobGuard.tryAcquire(JOB_LOCK_KEY, JOB_LOCK_TTL);
        if (token == null) {
            throw new IllegalStateException("归并 Job 已在另一节点运行，本次跳过");
        }
        try {
            WecomTagIndex index = loadWecomTagIndex();
            List<GradeTagRow> rows = classifyLocalGradeRows(index);
            Counters counters = new Counters();
            Set<String> processed = loadProcessedKeys();
            for (GradeTagRow r : rows) {
                processRow(r, index, processed, counters);
            }
            log.info("Phase A 完成: {}", counters);
            return counters.toReport(false);
        } finally {
            jobGuard.release(JOB_LOCK_KEY, token);
        }
    }

    /** 后台线程跑 Phase A（HTTP 线程不能等 25–40 分钟）。{@code @EnableAsync} 已在应用主类开启。 */
    @Async("taskExecutor")
    public void runPhaseAAsync() {
        try {
            log.info("Phase A 后台启动");
            runPhaseA(false);
        } catch (Exception e) {
            log.error("Phase A 后台执行失败: {}", e.getMessage(), e);
        }
    }

    private Set<String> loadProcessedKeys() {
        Set<String> keys = new HashSet<>();
        for (Object[] k : progressRepo.findProcessedKeys()) {
            keys.add(((Number) k[0]).longValue() + ":" + ((Number) k[1]).longValue());
        }
        log.info("续跑：已有 {} 个已处理工作项", keys.size());
        return keys;
    }

    /** 处理一条非年级组年级 tag 行下的所有客户（按 ct.id 分页，避免一次拉进内存）。 */
    private void processRow(GradeTagRow row, WecomTagIndex index, Set<String> processed,
                            Counters counters) {
        counters.rows++;
        Long afterId = -1L;
        while (true) {
            List<CustomerTag> batch = customerTagRepo.findByTagIdAndIdGreaterThanOrderByIdAsc(
                row.tagId(), afterId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) break;
            afterId = batch.get(batch.size() - 1).getId();

            List<Long> customerIds = batch.stream().map(CustomerTag::getCustomerId).distinct().toList();
            Map<Long, Customer> byId = new HashMap<>();
            customerRepo.findAllById(customerIds).forEach(c -> byId.put(c.getId(), c));

            for (CustomerTag ct : batch) {
                Customer c = byId.get(ct.getCustomerId());
                if (c == null) continue;
                if (processed.contains(c.getId() + ":" + row.tagId())) continue;
                try {
                    processCustomer(row, index, c, counters);
                } catch (Exception e) {
                    // 单客户失败不炸整批（客户已删会抛 84061 等）
                    log.error("归并失败: customerId={}, tagRow={}, error={}",
                        c.getId(), row.tagId(), e.getMessage());
                    counters.wecomMarkFailed++;
                    recordProgress(c, row, null, null, "WECOM_MARK_FAILED", true, e.getMessage());
                }
            }
        }
    }

    private void processCustomer(GradeTagRow row, WecomTagIndex index, Customer c,
                                 Counters counters) {
        counters.customers++;
        if (c.getCurrentAgent() == null || c.getCurrentAgent().isBlank()) {
            counters.skippedNoAgent++;
            recordProgress(c, row, null, null, "SKIPPED_NO_AGENT", false, null);
            return;
        }

        TargetTag target;
        String action;
        boolean review = false;

        if (row.rowClass() == RowClass.R1) {
            // 客户企微侧已对，零 API 调用
            target = new TargetTag(row.wecomTagId(), index.wid2name().get(row.wecomTagId()));
            action = "LOCAL_ONLY";
        } else {
            List<String> wecomIds = List.of();
            if (row.rowClass() == RowClass.R2) {
                sleepIfNeeded(counters);
                wecomIds = extractWecomTagIds(wecomApi.getExternalContact(c.getExternalUserid()));
                counters.apiCalls++;
            }

            if (index.hasGradeTag(wecomIds)) {
                // 已带年级组标签（可能因升学而异名）→ 按企微实际 id 重接线，绝不补打
                String wid = wecomIds.stream().filter(index.gradeWids()::contains)
                    .findFirst().orElse(null);
                target = new TargetTag(wid, index.wid2name().get(wid));
                action = "WECOM_RELINK";
            } else {
                // 企微确实没有年级标签 → 只能按本地行名补打；升学可能差一级，标 review
                target = ensureGradeTagByName(row.name(), index, counters);
                sleepIfNeeded(counters);
                wecomApi.markTag(c.getExternalUserid(), c.getCurrentAgent(),
                    List.of(target.wecomTagId()));
                counters.apiCalls++;
                action = "WECOM_MARKED";
                review = true;
            }
        }

        Long canonicalId = resolveCanonicalTagId(target);
        if (canonicalId != null && !canonicalId.equals(row.tagId())) {
            customerTagInsertService.rewire(c.getId(), row.tagId(), canonicalId);
        }
        switch (action) {
            case "LOCAL_ONLY" -> counters.localOnly++;
            case "WECOM_RELINK" -> counters.wecomRelink++;
            case "WECOM_MARKED" -> counters.wecomMarked++;
            default -> { }
        }
        if (review) counters.requiresReview++;
        recordProgress(c, row, target.wecomTagId(), canonicalId, action, review, null);
    }

    /**
     * 取「年级」组里名叫 {@code name} 的标签；不存在则新建（组已存在则 add 进该组）。
     *
     * <p>这是全流程<b>唯一按名字走</b>的路径（R1/R2 已带年级组标签的都按 id）——
     * 升学改名会让本地名与企微名差一级，因此调用方一律置 {@code requires_review=1}。</p>
     */
    private TargetTag ensureGradeTagByName(String name, WecomTagIndex index, Counters counters) {
        String wid = index.gradeWidByName(name);
        if (wid != null) return new TargetTag(wid, index.wid2name().get(wid));

        sleepIfNeeded(counters);
        String groupId = index.groupIdByName().get(GRADE_GROUP);
        JsonNode created = (groupId != null && !groupId.isBlank())
            ? wecomApi.addCorpTag(name, groupId)
            : wecomApi.addCorpTagWithGroup(name, GRADE_GROUP);
        counters.apiCalls++;

        String newId = created.path("tag_group").path("tag").path(0).path("id").asText(null);
        if (newId == null || newId.isBlank()) {
            throw new IllegalStateException("add_corp_tag 未返回新标签 id: " + name);
        }
        log.warn("按本地名补建「年级」组标签: name={}, newId={}（本地名可能因升学差一级，请复核）",
            name, newId);
        return new TargetTag(newId, name);
    }

    private void sleepIfNeeded(Counters counters) {
        if (counters.apiCalls > 0 && counters.apiCalls % API_SLEEP_EVERY == 0) {
            log.info("归并限频休眠: apiCalls={}", counters.apiCalls);
            try {
                Thread.sleep(API_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("归并 Job 被中断", e);
            }
        }
    }

    private void recordProgress(Customer c, GradeTagRow row, String targetWid, Long canonicalId,
                                String action, boolean requiresReview, String note) {
        try {
            progressRepo.save(GradeTagMigrationProgress.builder()
                .customerId(c.getId())
                .externalUserid(c.getExternalUserid())
                .rowClass(row.rowClass().name())
                .localTagId(row.tagId())
                .localTagName(row.name())
                .targetWecomTagId(targetWid)
                .targetTagId(canonicalId)
                .action(action)
                .requiresReview(requiresReview)
                .note(note == null ? null : note.substring(0, Math.min(note.length(), 255)))
                .build());
        } catch (Exception e) {
            // 进度行只是观测，写失败不影响正确性（重跑会重新处理这一项）
            log.warn("进度行写入失败: customerId={}, tagRow={}, {}", c.getId(), row.tagId(), e.getMessage());
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: PASS（16 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java \
        src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java
git commit -m "feat: 年级标签 A1/A2 逐客户对账（R2 读企微按 id 接线、R3 按名补打并标复核）"
```

---

### Task 7: Phase B 删除企微「学校-*」组年级重复标签

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `WecomApiClient.getCorpTagList()`、`delCorpTag(List<String>)`；`CustomerTagRepository.countByTagIdIn`；`TagRepository.findGradeNamedRowsOutsideGradeGroup`。
- Produces:
  - `record DeletePlanEntry(String wecomTagId, String groupName, String tagName)`
  - `List<DeletePlanEntry> planSchoolGroupGradeDeletions(WecomTagIndex index)`
  - `DeletePlanEntry probeSingleDeletion(WecomTagIndex index)` —— 取清单第一条，用于"先删 1 个实测"
  - `int deleteSchoolGroupGradeTags(List<String> wecomTagIds)`

- [ ] **Step 1: 写失败测试**

在 `GradeTagMigrationServiceTest.java` 追加：

```java
    @Test
    @DisplayName("白名单：只取非核心组里精确等于 12 年级名的标签，排除中班/班级")
    void plansDeletionExactNamesOnly() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));

        var plan = service.planSchoolGroupGradeDeletions(service.loadWecomTagIndex());

        assertEquals(2, plan.size());
        assertTrue(plan.stream().anyMatch(p -> p.wecomTagId().equals("s1")
            && p.groupName().equals("学校-天水市") && p.tagName().equals("一年级")));
        assertTrue(plan.stream().anyMatch(p -> p.wecomTagId().equals("s7")));
        // s2=中班 在「学校-天水市」组里，但不在 12 名白名单内 —— 绝不能进清单
        assertTrue(plan.stream().noneMatch(p -> p.wecomTagId().equals("s2")));
        // c1=一班 在「班级」组 —— 组名排除
        assertTrue(plan.stream().noneMatch(p -> p.wecomTagId().equals("c1")));
    }

    @Test
    @DisplayName("删除前护栏：本地仍有 customer_tag 指向待删标签时拒绝执行")
    void refusesDeletionWhileLocalBindingsRemain() throws Exception {
        // 本地仍有一条 R2 学校组行指向 s1
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(2127, "一年级", "学校-天水市", "s1")));
        when(customerTagRepo.countByTagIdIn(List.of(2127L))).thenReturn(5L);

        assertThrows(IllegalStateException.class,
            () -> service.deleteSchoolGroupGradeTags(List.of("s1")));
        org.mockito.Mockito.verify(wecomApi, org.mockito.Mockito.never())
            .delCorpTag(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("本地无残留绑定时：逐个 delCorpTag")
    void deletesWhenClean() throws Exception {
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection()))
            .thenReturn(List.of(row(2127, "一年级", "学校-天水市", "s1")));
        when(customerTagRepo.countByTagIdIn(List.of(2127L))).thenReturn(0L);

        assertEquals(1, service.deleteSchoolGroupGradeTags(List.of("s1")));
        org.mockito.Mockito.verify(wecomApi).delCorpTag(List.of("s1"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: FAIL，编译错误 `cannot find symbol: method planSchoolGroupGradeDeletions`。

- [ ] **Step 3: 实现**

在 `GradeTagMigrationService` 中加：

```java
    // ---------------------------------------------------------------- B

    public record DeletePlanEntry(String wecomTagId, String groupName, String tagName) {}

    /**
     * 算出企微侧待删清单：组名不是核心组（年级/班级/市州/县区/渠道来源），
     * 且标签名<b>精确等于</b> 12 个年级名之一。
     *
     * <p>必须精确匹配：{@code 学校-*} 组里有 {@code 中班}/{@code 小班}，还有学校名
     * {@code 舟曲县憨班镇中心小学} —— 用 {@code LIKE '%班%'} 会误删。</p>
     */
    public List<DeletePlanEntry> planSchoolGroupGradeDeletions(WecomTagIndex index) {
        List<DeletePlanEntry> plan = new ArrayList<>();
        // 直接遍历索引：wid2group/wid2name 已覆盖所有组的所有标签，无需再打一次 get_corp_tag_list
        for (Map.Entry<String, String> e : index.wid2group().entrySet()) {
            String wid = e.getKey();
            String groupName = e.getValue();
            if (CORE_GROUPS.contains(groupName)) continue;
            String tagName = index.wid2name().get(wid);
            if (GRADE_NAMES.contains(tagName)) {
                plan.add(new DeletePlanEntry(wid, groupName, tagName));
            }
        }
        log.info("Phase B 待删清单: {} 个", plan.size());
        plan.forEach(p -> log.info("  待删: [{}]{} wid={}", p.groupName(), p.tagName(), p.wecomTagId()));
        return plan;
    }

    /** 取清单第一条，用于「先删 1 个实测」。清单为空时返回 null。 */
    public DeletePlanEntry probeSingleDeletion(WecomTagIndex index) {
        var plan = planSchoolGroupGradeDeletions(index);
        return plan.isEmpty() ? null : plan.get(0);
    }

    /**
     * 执行删除。调用前必须先跑完 Phase A，否则那些客户在企微就没年级标签了。
     *
     * <p>护栏：若本地仍有 {@code customer_tag} 指向这些 wid 对应的 tag 行，直接拒绝 ——
     * 说明 Phase A 没跑干净。</p>
     */
    public int deleteSchoolGroupGradeTags(List<String> wecomTagIds) {
        if (wecomTagIds == null || wecomTagIds.isEmpty()) return 0;

        List<Long> localRowIds = new ArrayList<>();
        for (String wid : wecomTagIds) {
            tagRepo.findFirstByWecomTagIdAndGroupKeyword(wid, GRADE_GROUP).ifPresent(t -> localRowIds.add(t.getId()));
        }
        // 也可能指向校园组行（group_keyword 不是年级组）
        for (var t : tagRepo.findGradeNamedRowsOutsideGradeGroup(GRADE_NAMES)) {
            if (t.getWecomTagId() != null && wecomTagIds.contains(t.getWecomTagId())) {
                localRowIds.add(t.getId());
            }
        }
        if (!localRowIds.isEmpty()) {
            long bound = customerTagRepo.countByTagIdIn(localRowIds);
            if (bound > 0) {
                throw new IllegalStateException(
                    "本地仍有 " + bound + " 条 customer_tag 指向待删的学校组年级标签行，"
                        + "请先跑完 Phase A 再删企微标签");
            }
        }

        int deleted = 0;
        Counters counters = new Counters();
        for (String wid : wecomTagIds) {
            sleepIfNeeded(counters);
            wecomApi.delCorpTag(List.of(wid));
            counters.apiCalls++;
            deleted++;
            log.info("已删企微学校组年级标签: wid={}（{}/{}）", wid, deleted, wecomTagIds.size());
        }
        return deleted;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: PASS（19 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java \
        src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java
git commit -m "feat: 年级标签 Phase B —— 精确名白名单 + 删企微学校组重复标签（带本地残留护栏）"
```

---

### Task 8: Phase C 清理本地孤儿行 + Phase D 校验

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `TagRepository.findGradeNamedRowsOutsideGradeGroup`、`CustomerTagRepository.countByTagId`（新加）、`TagRepository.deleteById`；`WecomApiClient.getCorpTagList()`。
- Produces:
  - `long purgeOrphanGradeTagRows(boolean dryRun)` —— 删除无 `customer_tag` 引用的非年级组年级 tag 行
  - `record VerifyResult(long localNonCanonicalRows, long localBindingsToThem, long wecomSchoolGroupGradeTags)`，方法 `VerifyResult verify()`

- [ ] **Step 1: 给 CustomerTagRepository 加 countByTagId**

```java
    long countByTagId(Long tagId);
```

- [ ] **Step 2: 写失败测试**

在 `GradeTagMigrationServiceTest.java` 追加：

```java
    @Test
    @DisplayName("Phase C：有引用的行不删，无引用的行删除")
    void purgesOnlyOrphanRows() {
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection())).thenReturn(java.util.List.of(
            row(200, "一年级", "学校-天水市", "s1"),
            row(201, "二年级", "", "DEAD")));
        when(customerTagRepo.countByTagId(200L)).thenReturn(3L);
        when(customerTagRepo.countByTagId(201L)).thenReturn(0L);

        assertEquals(1, service.purgeOrphanGradeTagRows(false));
        org.mockito.Mockito.verify(tagRepo, org.mockito.Mockito.never()).deleteById(200L);
        org.mockito.Mockito.verify(tagRepo).deleteById(201L);
    }

    @Test
    @DisplayName("Phase C dry-run：只报告不删除")
    void purgeDryRunDoesNotDelete() {
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection())).thenReturn(java.util.List.of(
            row(201, "二年级", "", "DEAD")));
        when(customerTagRepo.countByTagId(201L)).thenReturn(0L);

        assertEquals(1, service.purgeOrphanGradeTagRows(true));
        org.mockito.Mockito.verify(tagRepo, org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Phase D verify：三项指标都为 0 表示已收敛")
    void verifyCounts() throws Exception {
        when(wecomApi.getCorpTagList()).thenReturn(MAPPER.readTree(CORP_TAG_LIST));
        when(tagRepo.findGradeNamedRowsOutsideGradeGroup(anyCollection())).thenReturn(java.util.List.of());

        var v = service.verify();

        assertEquals(0, v.localNonCanonicalRows());
        assertEquals(2, v.wecomSchoolGroupGradeTags()); // CORP_TAG_LIST 里 s1/s7 还在
    }
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: FAIL，编译错误 `cannot find symbol: method purgeOrphanGradeTagRows`。

- [ ] **Step 4: 实现**

```java
    // ---------------------------------------------------------------- C

    /**
     * 删除重接线后不再被任何 customer_tag 引用的非年级组年级 tag 行。
     *
     * <p><b>顺序要求：</b>必须确认无引用再删。否则 {@link TagService#getOrCreateTag} 的
     * 僵尸 id 修复若再被调用（企微标签已删），会重同步、甚至按名重建学校组标签，抵消 Phase B。</p>
     *
     * @return 删除（dry-run 时为「可删」）的行数
     */
    public long purgeOrphanGradeTagRows(boolean dryRun) {
        List<Tag> rows = tagRepo.findGradeNamedRowsOutsideGradeGroup(GRADE_NAMES);
        long orphans = 0;
        for (Tag t : rows) {
            if (customerTagRepo.countByTagId(t.getId()) > 0) continue;
            orphans++;
            if (dryRun) {
                log.info("[dry-run] 可删孤儿 tag 行: id={}, name={}, group={}", t.getId(), t.getName(), t.getGroupKeyword());
            } else {
                tagRepo.deleteById(t.getId());
                log.info("已删孤儿 tag 行: id={}, name={}, group={}", t.getId(), t.getName(), t.getGroupKeyword());
            }
        }
        log.info("Phase C 完成: {} 行非年级组年级 tag 行中，孤儿 {} 行（dryRun={}）", rows.size(), orphans, dryRun);
        return orphans;
    }

    // ---------------------------------------------------------------- D

    public record VerifyResult(long localNonCanonicalRows, long localBindingsToThem,
                               long wecomSchoolGroupGradeTags) {
        public boolean converged() {
            return localNonCanonicalRows == 0 && wecomSchoolGroupGradeTags == 0;
        }
    }

    /** Phase D：三项归并完成度指标。 */
    public VerifyResult verify() {
        List<Tag> rows = tagRepo.findGradeNamedRowsOutsideGradeGroup(GRADE_NAMES);
        long bindings = rows.isEmpty() ? 0
            : rows.stream().mapToLong(t -> customerTagRepo.countByTagId(t.getId())).sum();
        long wecomDupes = planSchoolGroupGradeDeletions(loadWecomTagIndex()).size();
        VerifyResult r = new VerifyResult(rows.size(), bindings, wecomDupes);
        log.info("Phase D 校验: {}", r);
        return r;
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -Dtest=GradeTagMigrationServiceTest test`
Expected: PASS（22 个用例）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/repository/CustomerTagRepository.java \
        src/main/java/com/bookstore/qrcode/service/GradeTagMigrationService.java \
        src/test/java/com/bookstore/qrcode/service/GradeTagMigrationServiceTest.java
git commit -m "feat: 年级标签 Phase C 孤儿行清理 + Phase D 校验指标"
```

---

### Task 9: 后台看板（Controller + 模板 + 安全规则）

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/TagMigrationController.java`
- Create: `src/main/resources/templates/admin/tag-migration.html`
- Modify: `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`
- Test: `src/test/java/com/bookstore/qrcode/controller/TagMigrationControllerTest.java`

**Interfaces:**
- Consumes: `GradeTagMigrationService.runPhaseA/planSchoolGroupGradeDeletions/probeSingleDeletion/deleteSchoolGroupGradeTags/purgeOrphanGradeTagRows/verify/loadWecomTagIndex`；`GradeTagMigrationProgressRepository.countByRowClass/countByRequiresReview/findTop200ByRequiresReviewOrderByIdAsc`。
- Produces: 路由 `GET /admin/tag-migration`、`POST /admin/tag-migration/run-a`、`POST /admin/tag-migration/delete-school-tags`、`POST /admin/tag-migration/purge-orphans`、`GET /admin/tag-migration/status`。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/bookstore/qrcode/controller/TagMigrationControllerTest.java`。注解组合照抄 `AdminSystemConfigControllerWebTest`：`@WebMvcTest(Controller.class)` + `@Import(SecurityConfig.class)`（否则测不到 `hasRole("ADMIN")`），`SecurityConfig` 依赖的 `rateLimitRedisTemplate` / `LoginSuccessHandler` 必须 `@MockBean`，POST 必须带 `csrf()`：

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.LoginSuccessHandler;
import com.bookstore.qrcode.config.SecurityConfig;
import com.bookstore.qrcode.repository.GradeTagMigrationProgressRepository;
import com.bookstore.qrcode.service.GradeTagMigrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(TagMigrationController.class)
@Import(SecurityConfig.class)
class TagMigrationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private GradeTagMigrationService service;
    @MockBean private GradeTagMigrationProgressRepository progressRepo;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    private GradeTagMigrationService.WecomTagIndex emptyIndex() {
        return new GradeTagMigrationService.WecomTagIndex(
            Map.of(), Map.of(), Map.of(), Set.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("看板页渲染 Phase D 校验、待删清单与进度计数")
    void dashboardRenders() throws Exception {
        var index = emptyIndex();
        when(service.loadWecomTagIndex()).thenReturn(index);
        when(service.planSchoolGroupGradeDeletions(index)).thenReturn(List.of());
        when(service.verify()).thenReturn(new GradeTagMigrationService.VerifyResult(0, 0, 0));
        when(progressRepo.countByRowClass("R1")).thenReturn(1L);
        when(progressRepo.countByRowClass("R2")).thenReturn(2L);
        when(progressRepo.countByRowClass("R3")).thenReturn(3L);
        when(progressRepo.countByRequiresReview(true)).thenReturn(0L);
        when(progressRepo.findTop200ByRequiresReviewOrderByIdAsc(true)).thenReturn(List.of());

        mockMvc.perform(get("/admin/tag-migration"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/tag-migration"))
            .andExpect(model().attributeExists("deletePlan", "verify", "counts", "reviewList"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST run-a dryRun=true 同步跑 dry-run，并回填 flash 结果")
    void runADryRun() throws Exception {
        when(service.runPhaseA(true)).thenReturn(new GradeTagMigrationService.MigrationReport(
            1, 1, 1, 0, 0, 0, 0, 0, true));

        mockMvc.perform(post("/admin/tag-migration/run-a").with(csrf())
                .param("dryRun", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/tag-migration"))
            .andExpect(flash().attributeExists("message"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST run-a dryRun=false 只投递后台任务（HTTP 线程不等 30 分钟）")
    void runAAsync() throws Exception {
        mockMvc.perform(post("/admin/tag-migration/run-a").with(csrf())
                .param("dryRun", "false"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/tag-migration"))
            .andExpect(flash().attributeExists("message"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("已登录但非 ADMIN 访问看板被拒（403）")
    void requiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/tag-migration")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -Dtest=TagMigrationControllerTest test`
Expected: FAIL，编译错误 `cannot find symbol: class TagMigrationController`。

- [ ] **Step 3: 实现 Controller**

创建 `src/main/java/com/bookstore/qrcode/controller/TagMigrationController.java`：

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.repository.GradeTagMigrationProgressRepository;
import com.bookstore.qrcode.service.GradeTagMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台：年级标签归并看板。
 *
 * <p>Phase A 约 25–40 分钟，不能挂在 HTTP 线程上同步跑，故 {@code /run-a} 立即返回、
 * 由 {@link Async} 后台执行；页面刷新看进度（进度表也只是观测，重跑=续跑）。</p>
 */
@Slf4j
@Controller
@RequestMapping("/admin/tag-migration")
@RequiredArgsConstructor
public class TagMigrationController {

    private final GradeTagMigrationService service;
    private final GradeTagMigrationProgressRepository progressRepo;

    @GetMapping
    public String dashboard(Model model) {
        var index = service.loadWecomTagIndex();
        model.addAttribute("deletePlan", service.planSchoolGroupGradeDeletions(index));
        model.addAttribute("verify", service.verify());

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("R1", progressRepo.countByRowClass("R1"));
        counts.put("R2", progressRepo.countByRowClass("R2"));
        counts.put("R3", progressRepo.countByRowClass("R3"));
        counts.put("review", progressRepo.countByRequiresReview(true));
        model.addAttribute("counts", counts);
        model.addAttribute("reviewList", progressRepo.findTop200ByRequiresReviewOrderByIdAsc(true));
        return "admin/tag-migration";
    }

    @PostMapping("/run-a")
    public String runA(@RequestParam(defaultValue = "false") boolean dryRun,
                       RedirectAttributes redirect) {
        if (dryRun) {
            var report = service.runPhaseA(true);
            redirect.addFlashAttribute("message", "Phase A dry-run: " + report);
        } else {
            service.runPhaseAAsync();
            redirect.addFlashAttribute("message", "Phase A 已后台启动，刷新本页看进度（约 25–40 分钟）");
        }
        return "redirect:/admin/tag-migration";
    }

    @PostMapping("/delete-school-tags")
    public String deleteSchoolTags(@RequestParam(defaultValue = "false") boolean confirmed,
                                   @RequestParam(defaultValue = "false") boolean probeOnly,
                                   RedirectAttributes redirect) {
        var index = service.loadWecomTagIndex();
        if (probeOnly) {
            var probe = service.probeSingleDeletion(index);
            if (probe == null) {
                redirect.addFlashAttribute("message", "待删清单为空，无需探针");
            } else {
                service.deleteSchoolGroupGradeTags(java.util.List.of(probe.wecomTagId()));
                redirect.addFlashAttribute("message", "已实测删除 1 个: [" + probe.groupName() + "]"
                    + probe.tagName() + " wid=" + probe.wecomTagId());
            }
            return "redirect:/admin/tag-migration";
        }
        if (!confirmed) {
            redirect.addFlashAttribute("error", "需勾选确认后再执行批量删除");
            return "redirect:/admin/tag-migration";
        }
        var plan = service.planSchoolGroupGradeDeletions(index);
        int n = service.deleteSchoolGroupGradeTags(plan.stream()
            .map(GradeTagMigrationService.DeletePlanEntry::wecomTagId).toList());
        redirect.addFlashAttribute("message", "已删除企微学校组年级标签 " + n + " 个");
        return "redirect:/admin/tag-migration";
    }

    @PostMapping("/purge-orphans")
    public String purgeOrphans(@RequestParam(defaultValue = "false") boolean dryRun,
                               @RequestParam(defaultValue = "false") boolean confirmed,
                               RedirectAttributes redirect) {
        if (!dryRun && !confirmed) {
            redirect.addFlashAttribute("error", "需勾选确认后再执行删除");
            return "redirect:/admin/tag-migration";
        }
        long n = service.purgeOrphanGradeTagRows(dryRun);
        redirect.addFlashAttribute("message", (dryRun ? "[dry-run] 可删" : "已删") + "孤儿 tag 行 " + n + " 行");
        return "redirect:/admin/tag-migration";
    }

    /** 供页面 fetch 轮询的轻量 JSON。 */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("R1", progressRepo.countByRowClass("R1"));
        m.put("R2", progressRepo.countByRowClass("R2"));
        m.put("R3", progressRepo.countByRowClass("R3"));
        m.put("review", progressRepo.countByRequiresReview(true));
        return m;
    }
}
```

`runPhaseAAsync()` 已由 Task 6 加在 `GradeTagMigrationService` 上，本步不再重复添加。

- [ ] **Step 4: 实现模板**

创建 `src/main/resources/templates/admin/tag-migration.html`（沿用 `admin/schools.html` 的 `layout` 片段）：

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='年级标签归并', content=~{::#content})}">
<body>
<div id="content">
    <h4 class="mb-3">🏷️ 年级标签归并</h4>

    <div th:if="${message}" class="alert alert-success alert-dismissible fade show" role="alert">
        ✅ <span th:text="${message}"></span>
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    </div>
    <div th:if="${error}" class="alert alert-danger alert-dismissible fade show" role="alert">
        ❌ <span th:text="${error}"></span>
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    </div>

    <div class="card border-0 shadow-sm mb-3">
        <div class="card-body">
            <h6>Phase D 校验</h6>
            <ul class="mb-0">
                <li>本地非年级组年级 tag 行：<b th:text="${verify.localNonCanonicalRows()}"></b></li>
                <li>指向这些行的 customer_tag 绑定：<b th:text="${verify.localBindingsToThem()}"></b></li>
                <li>企微「学校-*」组里的年级同名标签：<b th:text="${verify.wecomSchoolGroupGradeTags()}"></b></li>
            </ul>
        </div>
    </div>

    <div class="card border-0 shadow-sm mb-3">
        <div class="card-body">
            <h6>Phase A 进度（按行分类）</h6>
            <p class="mb-1">R1 <b th:text="${counts['R1']}"></b>
                · R2 <b th:text="${counts['R2']}"></b>
                · R3 <b th:text="${counts['R3']}"></b>
                · 待人工复核 <b th:text="${counts['review']}"></b></p>
            <form method="post" th:action="@{/admin/tag-migration/run-a}" class="d-inline">
                <input type="hidden" name="dryRun" value="true">
                <button class="btn btn-outline-secondary btn-sm">dry-run（零 API 零写库）</button>
            </form>
            <form method="post" th:action="@{/admin/tag-migration/run-a}" class="d-inline">
                <input type="hidden" name="dryRun" value="false">
                <button class="btn btn-primary btn-sm"
                        onclick="return confirm('确认后台启动 Phase A？约 25–40 分钟')">启动 Phase A</button>
            </form>
        </div>
    </div>

    <div class="card border-0 shadow-sm mb-3">
        <div class="card-body">
            <h6>Phase B 待删企微标签（<span th:text="${deletePlan.size()}"></span> 个）</h6>
            <table class="table table-sm">
                <thead><tr><th>组</th><th>标签名</th><th>wid</th></tr></thead>
                <tbody>
                <tr th:each="p : ${deletePlan}">
                    <td th:text="${p.groupName()}"></td>
                    <td th:text="${p.tagName()}"></td>
                    <td><code th:text="${p.wecomTagId()}"></code></td>
                </tr>
                </tbody>
            </table>
            <form method="post" th:action="@{/admin/tag-migration/delete-school-tags}" class="d-inline">
                <input type="hidden" name="probeOnly" value="true">
                <button class="btn btn-outline-warning btn-sm"
                        onclick="return confirm('先删清单里第 1 个标签做实测？')">实测删 1 个</button>
            </form>
            <form method="post" th:action="@{/admin/tag-migration/delete-school-tags}" class="d-inline">
                <input type="hidden" name="confirmed" value="true">
                <button class="btn btn-danger btn-sm"
                        onclick="return confirm('确认删除全部待删标签？Phase A 必须已完成')">批量删除</button>
            </form>
        </div>
    </div>

    <div class="card border-0 shadow-sm mb-3">
        <div class="card-body">
            <h6>Phase C 本地孤儿 tag 行清理</h6>
            <form method="post" th:action="@{/admin/tag-migration/purge-orphans}" class="d-inline">
                <input type="hidden" name="dryRun" value="true">
                <button class="btn btn-outline-secondary btn-sm">dry-run</button>
            </form>
            <form method="post" th:action="@{/admin/tag-migration/purge-orphans}" class="d-inline">
                <input type="hidden" name="confirmed" value="true">
                <button class="btn btn-danger btn-sm"
                        onclick="return confirm('确认删除无引用的孤儿 tag 行？')">执行清理</button>
            </form>
        </div>
    </div>

    <div class="card border-0 shadow-sm">
        <div class="card-body">
            <h6>待人工复核（按本地名补打，升学可能差一级，最多 200 条）</h6>
            <table class="table table-sm">
                <thead><tr><th>客户</th><th>本地行名</th><th>分类</th><th>动作</th></tr></thead>
                <tbody>
                <tr th:each="p : ${reviewList}">
                    <td th:text="${p.externalUserid}"></td>
                    <td th:text="${p.localTagName}"></td>
                    <td th:text="${p.rowClass}"></td>
                    <td th:text="${p.action}"></td>
                </tr>
                </tbody>
            </table>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 5: 加安全规则**

在 `SecurityConfig.java` 的 CSRF 段无需改动（页面用 `th:action` 表单，Thymeleaf 自动带 CSRF token）。在 authorize 段、`/admin/system-config/**` 那条之后加：

```java
                        // 年级标签归并：仅 admin 可访问（含删除企微标签等破坏性操作）
                        .requestMatchers("/admin/tag-migration/**").hasRole("ADMIN")
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./mvnw -q -Dtest=TagMigrationControllerTest test`
Expected: PASS。

再跑全量编译与相关测试，确认没破坏别的：

Run: `./mvnw -q -DskipTests compile && ./mvnw -q -Dtest='GradeTagMigrationServiceTest,CustomerTagInsertServiceRewireTest,WecomApiClientDelCorpTagTest,TagServiceTest' test`
Expected: 全 PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/controller/TagMigrationController.java \
        src/main/resources/templates/admin/tag-migration.html \
        src/main/java/com/bookstore/qrcode/config/SecurityConfig.java \
        src/test/java/com/bookstore/qrcode/controller/TagMigrationControllerTest.java
git commit -m "feat: 年级标签归并后台看板（dry-run / 启动 / 删旧标签 / 孤儿清理）"
```

---

## 上线执行顺序（人工，非代码任务）

> 这是**运行手册**，不是 code task。生产部署归用户自己执行。

1. **部署 + 等 Flyway 建 V22 表**（`app.jar` mtime 即上线版本，见 memory `prod-runtime-inspection`）。
2. **dry-run**：后台 `/admin/tag-migration` 点 `dry-run`，核对 R1/R2/R3 行数与待删清单。预期：待删清单只含 12 个精确年级名，**不含** `中班`/`小班`/`舟曲县憨班镇中心小学`。
3. **`mark_tag` 幂等 smoke test（Spec 第七节的未验证项）**：挑 1 个**已知已带**「年级」组标签的客户，对同一 tag id 手动重复 `markTag` 一次，确认返回 `errcode=0`、企微侧标签数不变（重复 add 不报错）。若企微对重复 add 报错，Phase A 需要先过滤掉「已带该标签」的客户。
4. **Phase A**：点「启动 Phase A」，等 25–40 分钟，刷新看 R1+R2+R3 计数增长到约 6.15 万客户。
5. **复核 R3 清单**：导出「待人工复核」的客户（`requires_review=1`），人工抽查是否因升学差一级。
6. **Phase B 探针**：点「实测删 1 个」，到企微后台确认该标签已消失、且**没有**客户因此掉标签；若异常，走 `add_corp_tag` 补回。
7. **Phase B 批量**：点「批量删除」。
8. **Phase C**：先 `dry-run` 看可删行数，再「执行清理」。
9. **Phase D 验收**：看板三项指标：本地非年级组年级 tag 行 = 0，指向它们的绑定 = 0，企微学校组年级同名标签 = 0。
10. **正向回归**：提交一份真实表单，确认年级打到「年级」组。

## 验收标准（对 Spec 第八节）

| # | 标准 | 由谁保证 |
|---|---|---|
| 1 | 企微「学校-*」组中 12 个年级名标签数为 0 | Task 7 `deleteSchoolGroupGradeTags` + Task 8 `verify().wecomSchoolGroupGradeTags()` |
| 2 | 抽样 300 客户企微年级标签全在「年级」组 | Task 6 对账逻辑（R2 读企微按 id 接线、R3 补打进「年级」组）+ 人工抽样 |
| 3 | 本地无 `customer_tag` 指向非 canonical 年级 tag 行 | Task 4 `rewire`（撞唯一键删重复）+ Task 8 `verify().localBindingsToThem()` |
| 4 | 新表单提交年级打「年级」组 | 已由 `docs/data-repair-20261008-grade-tag-group.sql` 保证（本计划不改）|
| 5 | Phase A 「按本地名补打」清单已人工复核 | `requires_review=1` 行 + 看板「待人工复核」表 |
