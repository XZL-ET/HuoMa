# 告警降噪与自动归档 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `/alerts` 的 2,140 条未处理告警收敛成十余条真实待办，并让每条待办都能一键跳到处理页面。

**Architecture:** 在 `AlertService.createAlert` 内按类型 upsert（条件类合并、事件类保留）；各生产者在自己确认条件消失时按 DB 差集关闭告警；新增每小时维护任务做兜底合并与过期归档；告警页顶部按类型聚合出“待处理事项”卡片并给出深链；48002 从逐员工告警改成每天一条系统级说明。

**Tech Stack:** Java 17 / Spring Boot 3 / Spring Data JPA / Thymeleaf / MySQL 8（生产）、H2（测试）/ Redis（跨机锁与计数）。

**Spec:** `docs/superpowers/specs/2026-10-29-alert-noise-reduction-design.md`

## Global Constraints

- 生产 `spring.jpa.hibernate.ddl-auto: none`；本计划只改 `schema.sql` 与 H2 的 `schema-test.sql`，生产 `ALTER TABLE` 由用户执行，禁止代跑。
- 生产是 ALB 后两台 active-active ECS（10.0.0.231/232），所有新增定时任务必须用 `ScheduledJobGuard` 加 `lock:job:*`，不得依赖进程内状态。
- 实体不使用 JPA 关系注解，跨表只存 ID（`CLAUDE.md` 既有决策）。
- `agent_alert.detail` 是 MySQL JSON 列，值经 Jackson 序列化（带引号）；字符串匹配按旧文案片段，不按裸数字。
- 条件类 / 事件类的判定只写在 `AlertTypeMeta`，调用方不得自行散落 if-else。
- 不修改 48002 的发送方案，只改“怎么记、怎么显示”。
- 新增文案一律中文；页面上不出现英文类型名（元数据缺失时回退原始类型名）。
- 测试命令统一 `mvn -q -Dtest=<Class> test`；测试环境 Redis 端口 16379 不可达，任何依赖 Redis 的路径必须有降级分支。

## Review Focus

1. **滚动部署期间的旧行**：旧 jar 写入的告警 `last_seen_at` 为 NULL。所有“是否过期”判断必须用 `COALESCE(lastSeenAt, createdAt)`，否则新代码上线首小时会把它们判成永久最新或永久最旧。（Task 4 测试）
2. **双机并发 upsert**：两台同时查不到 open 行会各插一条。合并必须以“求和/取最大”收口，不能覆盖丢次数。（Task 4 测试）
3. **扫描失败不得当成恢复**：巡检抛异常、批次里单个活码抛异常、Stream 查不到消费组时，都不能关闭告警。（Task 6/7 测试）
4. **Redis 故障**：锁与计数都 fail-open；48002 计数取不到时仍要生成告警，文案不能显示“今日 0 次”。（Task 9 测试）
5. **detail 是 JSON 字符串**：历史 48002 清理按“无客户联系权限”匹配；`WechatSyncHealingService` 的真实告警也带 `errcode=48002`，必须不被误清。（Task 4 测试）

---

### Task 1: 数据模型与仓库查询

**Files:**
- Modify: `src/main/resources/schema.sql:231-245`
- Modify: `src/test/resources/schema-test.sql:209-231`
- Modify: `src/main/java/com/bookstore/qrcode/entity/AgentAlert.java:104-118`
- Modify: `src/main/java/com/bookstore/qrcode/repository/AgentAlertRepository.java`
- Create: `src/test/java/com/bookstore/qrcode/repository/AgentAlertRepositoryTest.java`

**Interfaces:**
- Produces:
  - `AgentAlert#getLastSeenAt()/setLastSeenAt(LocalDateTime)`、`#getOccurrenceCount()/setOccurrenceCount(int)`
  - `AgentAlertRepository#findByStatus(AlertStatus) : List<AgentAlert>`
  - `AgentAlertRepository#findByAlertTypeInAndStatus(Collection<String>, AlertStatus) : List<AgentAlert>`
  - `AgentAlertRepository#findOpenByKey(String alertType, String agentUserid, Long qrCodeId, AlertStatus status) : List<AgentAlert>`（按 id 升序；null 与 null 匹配）
  - `AgentAlertRepository#closeByIds(Collection<Long> ids, AlertStatus status, LocalDateTime resolvedAt, String resolvedBy) : int`

- [ ] **Step 1: 写仓库测试**

```java
@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("AgentAlertRepository 告警合并查询")
class AgentAlertRepositoryTest {
    @Autowired AgentAlertRepository repo;
    @Autowired TestEntityManager em;

    private AgentAlert alert(String type, String agent, Long qr) {
        return em.persistFlushFind(AgentAlert.builder()
            .alertType(type).agentUserid(agent).qrCodeId(qr)
            .severity(AgentAlert.AlertSeverity.medium)
            .detail("\"x\"").status(AgentAlert.AlertStatus.open).build());
    }

    @Test
    @DisplayName("findOpenByKey — null 与 null 匹配，不匹配非 null")
    void shouldMatchNullAsNull() {
        AgentAlert global = alert("inheritance_no_service", null, null);
        alert("inheritance_no_service", "u1", null);

        var found = repo.findOpenByKey("inheritance_no_service", null, null, AgentAlert.AlertStatus.open);

        assertThat(found).extracting(AgentAlert::getId).containsExactly(global.getId());
    }

    @Test
    @DisplayName("closeByIds — 只关闭给定 id，并写 resolvedBy/At")
    void shouldCloseOnlyGivenIds() {
        AgentAlert a = alert("traffic_spike", null, 7L);
        AgentAlert b = alert("traffic_spike", null, 8L);
        var now = LocalDateTime.now();

        int closed = repo.closeByIds(List.of(a.getId()), AgentAlert.AlertStatus.auto_resolved, now, "auto:test");
        em.flush(); em.clear();

        assertThat(closed).isEqualTo(1);
        assertThat(repo.findById(a.getId()).orElseThrow().getStatus())
            .isEqualTo(AgentAlert.AlertStatus.auto_resolved);
        assertThat(repo.findById(b.getId()).orElseThrow().getStatus())
            .isEqualTo(AgentAlert.AlertStatus.open);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -Dtest=AgentAlertRepositoryTest test`
Expected: 编译失败 — `findOpenByKey` / `closeByIds` 不存在。

- [ ] **Step 3: 加列、加索引、加查询**

`schema.sql` 的 `agent_alert` 建表语句中，在 `resolved_at` 后加：

```sql
last_seen_at DATETIME NULL COMMENT '最近一次命中时间',
occurrence_count INT NOT NULL DEFAULT 1 COMMENT '累计命中次数',
```

并在索引区加 `INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id)`。
`schema-test.sql` 做同样两列（用 `TIMESTAMP`）与索引。

`AgentAlert` 加：

```java
@Column(name = "last_seen_at")
private LocalDateTime lastSeenAt;

@Column(name = "occurrence_count", nullable = false)
@Builder.Default
private int occurrenceCount = 1;
```

`@PrePersist` 里补 `if (lastSeenAt == null) lastSeenAt = createdAt;`（`createdAt` 在上面已赋值）。

`AgentAlertRepository` 加上述四个方法；`findOpenByKey` 用 JPQL 显式处理 NULL：

```java
@Query("SELECT a FROM AgentAlert a WHERE a.alertType = :alertType AND a.status = :status "
     + "AND ((:agentUserid IS NULL AND a.agentUserid IS NULL) OR a.agentUserid = :agentUserid) "
     + "AND ((:qrCodeId IS NULL AND a.qrCodeId IS NULL) OR a.qrCodeId = :qrCodeId) "
     + "ORDER BY a.id ASC")
List<AgentAlert> findOpenByKey(...);
```

`closeByIds` 用 `@Modifying(clearAutomatically = true)` 的批量 UPDATE；`ids` 为空时调用方要先短路（空 IN 是非法 SQL）。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=AgentAlertRepositoryTest test`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/schema.sql src/test/resources/schema-test.sql \
  src/main/java/com/bookstore/qrcode/entity/AgentAlert.java \
  src/main/java/com/bookstore/qrcode/repository/AgentAlertRepository.java \
  src/test/java/com/bookstore/qrcode/repository/AgentAlertRepositoryTest.java
git commit -m "feat(alert): 增加最近发生/次数两列与合并查询"
```

---

### Task 2: AlertTypeMeta 类型元数据与深链

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/AlertTypeMeta.java`
- Create: `src/test/java/com/bookstore/qrcode/service/AlertTypeMetaTest.java`

**Interfaces:**
- Produces:
  - `record AlertTypeMeta(String type, String label, String suggestion, AgentAlert.AlertSeverity severity, boolean informational)`
  - `AlertTypeMeta.of(String type) : AlertTypeMeta`（未知类型回退 `type` 自身、medium、非 informational）
  - `AlertTypeMeta.labelOf(String type) : String`
  - `AlertTypeMeta.isConditionType(String type) : boolean`
  - `AlertTypeMeta.keyOf(String type, String agentUserid, Long qrCodeId) : String`
  - `AlertTypeMeta.linkFor(String type, Long qrCodeId) : String`（空串 = 无链接）
  - `AlertTypeMeta.linkLabelFor(String type) : String`
  - `AlertTypeMeta.ALL : List<AlertTypeMeta>`

- [ ] **Step 1: 写测试**

```java
@DisplayName("AlertTypeMeta 类型元数据")
class AlertTypeMetaTest {

    @Test
    @DisplayName("traffic_spike 的合并键丢弃 agent，只保留活码")
    void shouldDropAgentForKeyOfTrafficSpike() {
        assertThat(AlertTypeMeta.keyOf("traffic_spike", "u1", 7L))
            .isEqualTo(AlertTypeMeta.keyOf("traffic_spike", "u2", 7L));
    }

    @Test
    @DisplayName("全局类型合并键固定为 type||")
    void shouldNormalizeGlobalTypes() {
        assertThat(AlertTypeMeta.keyOf("inheritance_no_service", "u1", 7L)).isEqualTo("inheritance_no_service||");
        assertThat(AlertTypeMeta.keyOf("wechat_48002", null, 7L)).isEqualTo("wechat_48002||");
    }

    @Test
    @DisplayName("leak_ 前缀按条件类处理，事件类不被合并")
    void shouldClassifyTypes() {
        assertThat(AlertTypeMeta.isConditionType("leak_tag_skip_missing_qrcode")).isTrue();
        assertThat(AlertTypeMeta.isConditionType("melt")).isFalse();
        assertThat(AlertTypeMeta.isConditionType("some_future_type")).isFalse();
    }

    @Test
    @DisplayName("深链：有活码去详情，无活码回列表；48002 无链接")
    void shouldBuildLinks() {
        assertThat(AlertTypeMeta.linkFor("traffic_spike", 7L)).isEqualTo("/qrcodes/7");
        assertThat(AlertTypeMeta.linkFor("traffic_spike", null)).isEqualTo("/qrcodes");
        assertThat(AlertTypeMeta.linkFor("qr_agent_anomaly", null)).isEqualTo("/qrcodes/verify-all-agents");
        assertThat(AlertTypeMeta.linkFor("wechat_48002", null)).isEmpty();
        assertThat(AlertTypeMeta.linkFor("unknown_type", null)).isEmpty();
    }

    @Test
    @DisplayName("未知类型回退为原始类型名")
    void shouldFallbackForUnknownType() {
        assertThat(AlertTypeMeta.of("unknown_type").label()).isEqualTo("unknown_type");
        assertThat(AlertTypeMeta.of("unknown_type").informational()).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -Dtest=AlertTypeMetaTest test`
Expected: 编译失败 — 类不存在。

- [ ] **Step 3: 实现静态表**

按设计 4.6 建 `ALL`（每种类型一条：中文名、建议动作、参考级别、是否 informational）。`informational=true` 的只有：`wechat_48002`、`daily_anomaly_heal`、`traffic_spike`。

合并键规则（`keyOf` 返回 `type + "|" + agent + "|" + qr`，null 输出空串）：

- `traffic_spike`：agent 置空，qr 保留。
- 全局类型：`inheritance_no_service`、`inheritance_ambiguous_service`、`callback_stream_backlog`、`tag_stream_backlog`、`datafill_stream_backlog`、`outbound_stream_backlog`、`transfer_stream_backlog`、`transfer_dlq_backlog`、`dlq_replay_exhausted`、`daily_reset_failure`、`deletion_report_fail`、`daily_anomaly_heal`、`wechat_48002`：agent 与 qr 都置空。
- 其余原样。

`isConditionType`：`ALL` 中 informational/普通条件类名单 + `type.startsWith("leak_")`；事件类只有 `add_fail`、`melt`、`transfer_fail`、`transfer_retry_exhausted_svc`；未知类型返回 `false`（按事件类保留逐条，宁可多留不可误合并）。

`linkFor` 按设计 4.6 的映射表实现；`leak_` 前缀兜底 `/qrcodes`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=AlertTypeMetaTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/AlertTypeMeta.java \
  src/test/java/com/bookstore/qrcode/service/AlertTypeMetaTest.java
git commit -m "feat(alert): 类型元数据与告警深链"
```

---

### Task 3: createAlert 条件类 upsert 与关闭助手

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/AlertService.java:272-311`
- Modify: `src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java`

**Interfaces:**
- Consumes: `AlertTypeMeta.isConditionType/keyOf`（Task 2）、`findOpenByKey/closeByIds`（Task 1）
- Produces:
  - `AlertService#createAlert(...)` 条件类走 upsert，事件类行为不变
  - `AlertService#resolveOpenByType(String alertType) : int`
  - `AlertService#resolveOpenByKey(String alertType, String agentUserid, Long qrCodeId) : int`
  - `AlertService#resolveOpenByTypeExceptQr(String alertType, Collection<Long> keepQrIds) : int`
  - `AlertService#resolveOpenByTypeExceptAgents(String alertType, Collection<String> keepAgents) : int`
  - `AlertService#resolveOpenByTypesExceptKeys(Collection<String> alertTypes, Collection<String> keepKeys) : int`
  - 统一写 `status=auto_resolved`、`resolvedBy="auto:condition-cleared"`

- [ ] **Step 1: 写失败测试**

在 `AlertServiceTest` 增加：

```java
@Test
@DisplayName("createAlert — 条件类命中 open 行时更新次数/最近时间，不新增")
void shouldUpsertConditionAlert() {
    AgentAlert existing = AgentAlert.builder().id(9L).alertType("traffic_spike")
        .severity(AgentAlert.AlertSeverity.low).detail("\"old\"")
        .status(AgentAlert.AlertStatus.open).qrCodeId(7L).occurrenceCount(1)
        .createdAt(LocalDateTime.now().minusDays(3)).build();
    when(alertRepo.findOpenByKey("traffic_spike", null, 7L, AgentAlert.AlertStatus.open))
        .thenReturn(List.of(existing));

    alertService.createAlert("agentX", "traffic_spike", AgentAlert.AlertSeverity.high,
        "新详情", AgentAlert.AutoAction.none, 7L);

    assertThat(existing.getOccurrenceCount()).isEqualTo(2);
    assertThat(existing.getSeverity()).isEqualTo(AgentAlert.AlertSeverity.high);
    assertThat(existing.getDetail()).contains("新详情");
    assertThat(existing.getLastSeenAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    verify(alertRepo).save(existing);
}

@Test
@DisplayName("createAlert — 条件类无 open 行时插入，次数=1、最近时间=now")
void shouldInsertConditionAlertWhenNoneOpen() {
    when(alertRepo.findOpenByKey("traffic_spike", null, 7L, AgentAlert.AlertStatus.open))
        .thenReturn(List.of());
    when(alertRepo.save(any(AgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

    alertService.createAlert("a", "traffic_spike", AgentAlert.AlertSeverity.medium, "x",
        AgentAlert.AutoAction.none, 7L);

    ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
    verify(alertRepo).save(captor.capture());
    assertThat(captor.getValue().getOccurrenceCount()).isEqualTo(1);
    assertThat(captor.getValue().getAgentUserid()).isNull(); // traffic_spike 归一键
    assertThat(captor.getValue().getLastSeenAt()).isNotNull();
}

@Test
@DisplayName("createAlert — 事件类不做 upsert")
void shouldAlwaysInsertEventAlert() {
    when(alertRepo.save(any(AgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

    alertService.createAlert("u1", "melt", AgentAlert.AlertSeverity.high, "x",
        AgentAlert.AutoAction.melted, 7L);

    verify(alertRepo, never()).findOpenByKey(any(), any(), any(), any());
}

@Test
@DisplayName("resolveOpenByType — 批量关闭并为空时不发 SQL")
void shouldResolveOpenByType() {
    AgentAlert a = AgentAlert.builder().id(1L).status(AgentAlert.AlertStatus.open).build();
    AgentAlert b = AgentAlert.builder().id(2L).status(AgentAlert.AlertStatus.open).build();
    when(alertRepo.findByAlertTypeInAndStatus(List.of("qr_agent_anomaly"), AgentAlert.AlertStatus.open))
        .thenReturn(List.of(a, b));

    int n = alertService.resolveOpenByType("qr_agent_anomaly");

    assertThat(n).isEqualTo(2);
    verify(alertRepo).closeByIds(List.of(1L, 2L), AgentAlert.AlertStatus.auto_resolved,
        any(LocalDateTime.class), eq("auto:condition-cleared"));
}

@Test
@DisplayName("resolveOpenByType — 没有 open 行时不调用 closeByIds")
void shouldSkipCloseWhenNothingOpen() {
    when(alertRepo.findByAlertTypeInAndStatus(any(), any())).thenReturn(List.of());
    assertThat(alertService.resolveOpenByType("qr_agent_anomaly")).isZero();
    verify(alertRepo, never()).closeByIds(any(), any(), any(), any());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -Dtest=AlertServiceTest test`
Expected: FAIL — `findOpenByKey` 未被调用 / 方法不存在（先因编译失败停下）。

- [ ] **Step 3: 实现 upsert 与关闭助手**

`createAlert` 在事务内：

1. 归一化键：`String keyAgent = AlertTypeMeta.keyOf(alertType, agentUserid, qrCodeId)` 解析回 agent/qr 不优雅——改为在 `AlertTypeMeta` 增加 `normalizeAgent(type, agent)` 与 `normalizeQr(type, qr)` 两个方法（`keyOf` 内部复用），`createAlert` 用它们决定写入的 `agentUserid`/`qrCodeId`。
2. 若 `isConditionType(alertType)`：`findOpenByKey(type, keyAgent, keyQr, open)`；命中则取第一条（最早），更新 `detail`、`lastSeenAt=now`、`occurrenceCount+1`、`severity=更高者`、`autoAction`、`qrCodeId/agentUserid`（归一化值），`save`，日志 `debug`；未命中则按原逻辑插入，并显式写 `lastSeenAt=now`、`occurrenceCount=1`，日志 `warn`。
3. 事件类完全保持现状（无条件插入、`warn` 日志）。

关闭助手用一个私有 `resolveWhere(Collection<String> types, Predicate<AgentAlert> keepOut)`：查 `findByAlertTypeInAndStatus(types, open)`，过滤出要关的 id，为空直接返回 0，否则 `closeByIds(ids, auto_resolved, now, "auto:condition-cleared")` 并 `log.info`。

`resolveOpenByTypesExceptKeys` 用 `AlertTypeMeta.keyOf(...)` 生成现有行的 key，不在 `keepKeys` 里的关闭。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=AlertServiceTest test`
Expected: PASS（原有 10 个 + 新增 5 个）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/AlertService.java \
  src/main/java/com/bookstore/qrcode/service/AlertTypeMeta.java \
  src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java
git commit -m "feat(alert): 条件类告警 upsert 与自动关闭"
```

---

### Task 4: 每小时去重与过期归档

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/AlertService.java`
- Modify: `src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java`

**Interfaces:**
- Produces:
  - `AlertService#collapseDuplicates() : int` — 返回被合并关闭的条数
  - `AlertService#archiveStale() : int` — 返回被归档的条数

- [ ] **Step 1: 写失败测试**

```java
@Test
@DisplayName("collapseDuplicates — 保留最早一条，次数求和、最近取最大，其余关闭")
void shouldCollapseKeepingOldestAndSumming() {
    LocalDateTime old = LocalDateTime.now().minusDays(5);
    AgentAlert a = AgentAlert.builder().id(1L).alertType("qr_agent_anomaly").status(open)
        .occurrenceCount(2).lastSeenAt(old).createdAt(old).build();
    AgentAlert b = AgentAlert.builder().id(2L).alertType("qr_agent_anomaly").status(open)
        .occurrenceCount(3).lastSeenAt(old.plusDays(1)).createdAt(old.plusDays(1)).build();
    AgentAlert c = AgentAlert.builder().id(3L).alertType("qr_agent_anomaly").status(open)
        .occurrenceCount(1).lastSeenAt(old.plusDays(2)).createdAt(old.plusDays(2)).build();
    when(alertRepo.findByStatus(open)).thenReturn(List.of(a, b, c));

    int n = alertService.collapseDuplicates();

    assertThat(n).isEqualTo(2);
    assertThat(a.getOccurrenceCount()).isEqualTo(6);
    assertThat(a.getLastSeenAt()).isEqualTo(old.plusDays(2));
    assertThat(a.getId()).isEqualTo(1L);
    verify(alertRepo).closeByIds(List.of(2L, 3L), AgentAlert.AlertStatus.auto_resolved,
        any(), eq("auto:dedup"));
}

@Test
@DisplayName("archiveStale — lastSeenAt 为空时按 createdAt 判定（滚动部署兼容）")
void shouldUseCreatedAtWhenLastSeenMissing() {
    AgentAlert legacy = AgentAlert.builder().id(1L).alertType("traffic_spike").status(open)
        .occurrenceCount(1).createdAt(LocalDateTime.now().minusDays(2)).build();
    when(alertRepo.findByStatus(open)).thenReturn(List.of(legacy));

    assertThat(alertService.archiveStale()).isEqualTo(1);
    verify(alertRepo).closeByIds(List.of(1L), auto_resolved, any(), eq("auto:stale"));
}

@Test
@DisplayName("archiveStale — 只清旧文案的无权限 48002，不误清自愈告警")
void shouldArchiveOnlyLegacy48002Text() {
    AgentAlert legacy = AgentAlert.builder().id(1L).alertType("wechat_unavailable").status(open)
        .detail("\"员工 u1 无客户联系权限(errcode=48002)，欢迎语发送被企微拒绝\"")
        .occurrenceCount(1).createdAt(LocalDateTime.now()).build();
    AgentAlert healing = AgentAlert.builder().id(2L).alertType("wechat_unavailable").status(open)
        .detail("\"企微不可用员工已被自愈移除: userid=u2 活码=9 errcode=48002\"")
        .occurrenceCount(1).createdAt(LocalDateTime.now()).build();
    when(alertRepo.findByStatus(open)).thenReturn(List.of(legacy, healing));

    assertThat(alertService.archiveStale()).isEqualTo(1);
    verify(alertRepo).closeByIds(List.of(1L), auto_resolved, any(), eq("auto:stale"));
}

@Test
@DisplayName("archiveStale — 真实待办永不过期")
void shouldNotArchiveActionableTypes() {
    AgentAlert inherit = AgentAlert.builder().id(1L).alertType("inheritance_no_service").status(open)
        .occurrenceCount(1).createdAt(LocalDateTime.now().minusDays(365)).build();
    AgentAlert teacher = AgentAlert.builder().id(2L).alertType("service_teacher_anomaly").status(open)
        .occurrenceCount(1).createdAt(LocalDateTime.now().minusDays(365)).build();
    when(alertRepo.findByStatus(open)).thenReturn(List.of(inherit, teacher));

    assertThat(alertService.archiveStale()).isZero();
    verify(alertRepo, never()).closeByIds(any(), any(), any(), any());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -Dtest=AlertServiceTest test`
Expected: FAIL — 方法不存在。

- [ ] **Step 3: 实现**

`collapseDuplicates`：取 `findByStatus(open)`，只保留 `isConditionType` 的行，按 `keyOf` 分组，组内按 id 升序；存活第一条，`occurrenceCount = 组内求和`、`lastSeenAt = 组内非空最大（全空则用各组 createdAt 最大）`、`severity = 更高者`、`detail = 组内最后一条的 detail`；其余 id 走 `closeByIds(..., "auto:dedup")`。`saveAll` 只在存活行有变化时调用。

`archiveStale`：取 `findByStatus(open)`，按类型判定（`lastSeen = lastSeenAt != null ? lastSeenAt : createdAt`）：

| 类型 | 条件 |
|---|---|
| `daily_anomaly_heal` | `createdAt < now-24h` |
| `traffic_spike` | `lastSeen < now-24h` |
| `wechat_48002` | `lastSeen < now-24h` |
| `qr_agent_anomaly` | `lastSeen < now-7d` |
| `dlq_replay_exhausted` / `deletion_report_fail` | `lastSeen < now-7d` |
| `employee_departed_service` | `lastSeen < now-30d` |
| `wechat_unavailable` 且 `detail` 含 `无客户联系权限` | 无条件 |
| `transfer_retry_exhausted_svc` | `lastSeen < now-14d` |
| `add_fail` / `melt` / `transfer_fail` | `createdAt < now-30d` |
| 其他 | 不归档 |

注意：`detail` 存的是带引号的 JSON 字符串，匹配用 `detail != null && detail.contains("无客户联系权限")`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=AlertServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/AlertService.java \
  src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java
git commit -m "feat(alert): 每小时合并重复与过期归档"
```

---

### Task 5: AlertMaintenanceJob

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/worker/AlertMaintenanceJob.java`
- Create: `src/test/java/com/bookstore/qrcode/worker/AlertMaintenanceJobTest.java`

**Interfaces:**
- Consumes: `AlertService#collapseDuplicates/archiveStale`、`ScheduledJobGuard#runExclusive`
- Produces: `AlertMaintenanceJob#runOnce()`（定时与启动共用）

- [ ] **Step 1: 写测试**

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("告警维护任务")
class AlertMaintenanceJobTest {
    @Mock AlertService alertService;
    @Mock ScheduledJobGuard jobGuard;
    @InjectMocks AlertMaintenanceJob job;

    @Test
    @DisplayName("runOnce — 持锁时先合并再归档")
    void shouldCollapseThenArchive() {
        when(jobGuard.runExclusive(eq("lock:job:alert-maintenance"), any(), any()))
            .thenAnswer(inv -> { ((Runnable) inv.getArgument(2)).run(); return true; });
        when(alertService.collapseDuplicates()).thenReturn(3);
        when(alertService.archiveStale()).thenReturn(5);

        job.runOnce();

        InOrder order = inOrder(alertService);
        order.verify(alertService).collapseDuplicates();
        order.verify(alertService).archiveStale();
    }

    @Test
    @DisplayName("runOnce — 未持锁时不执行")
    void shouldSkipWhenLockedByOtherNode() {
        when(jobGuard.runExclusive(any(), any(), any())).thenReturn(false);
        job.runOnce();
        verifyNoInteractions(alertService);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=AlertMaintenanceJobTest test`
Expected: 编译失败 — 类不存在。

- [ ] **Step 3: 实现**

```java
@Slf4j @Component @RequiredArgsConstructor
public class AlertMaintenanceJob {
    static final String LOCK_KEY = "lock:job:alert-maintenance";
    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private final AlertService alertService;
    private final ScheduledJobGuard jobGuard;

    @Scheduled(cron = "0 20 * * * *")
    public void runOnce() { ... }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { runOnce(); }
}
```

`runOnce` 内 `jobGuard.runExclusive(LOCK_KEY, LOCK_TTL, () -> { int m = collapseDuplicates(); int n = archiveStale(); log.info("告警维护: 合并 {} 条、归档 {} 条", m, n); })`。两个方法各自 try-catch 会吞掉异常，不需要额外处理。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=AlertMaintenanceJobTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/worker/AlertMaintenanceJob.java \
  src/test/java/com/bookstore/qrcode/worker/AlertMaintenanceJobTest.java
git commit -m "feat(alert): 每小时维护任务（合并+归档）"
```

---

### Task 6: PatrolWorker 四处自动关闭

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java:385-412, 424-465, 540-590, 195-215, 627-665`
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java:2040-2200`（返回本轮命中的告警 key）
- Modify: `src/test/java/com/bookstore/qrcode/worker/PatrolWorkerTest.java`

**Interfaces:**
- Consumes: Task 3 的 `resolveOpenByType` / `resolveOpenByTypeExceptQr` / `resolveOpenByTypesExceptKeys`
- Produces: `QrCodeService#replaceAnomalyAgents` 的返回 Map 新增 `"alertKeys" : List<String>`

- [ ] **Step 1: 写测试**

在 `PatrolWorkerTest` 增加（用反射调用私有方法，沿用该文件已有的 `buildWorker` 方式）：

```java
@Test
@DisplayName("巡检异常归零 → 关闭 qr_agent_anomaly")
void shouldResolveAnomalyWhenNone() throws Exception {
    when(alertService.resolveOpenByType("qr_agent_anomaly")).thenReturn(1);
    // 所有 QrAgent 均无异常（agent/employee 查询返回空集合 → 无异常标签）
    invokePatrolQrAgentAnomalies();
    verify(alertService).resolveOpenByType("qr_agent_anomaly");
}

@Test
@DisplayName("本机仍有高负载活码 → 不关闭该活码的 traffic_spike，关闭其余")
void shouldResolveTrafficSpikeByQrDiff() throws Exception {
    // 准备一个 active 活码 + 一个 active QrAgent，其 dailyCurrent/dailyMax = 1.0
    invokeCheckOverloadedQrCodes();
    verify(alertService).resolveOpenByTypeExceptQr(eq("traffic_spike"), eq(Set.of(1L)));
}

@Test
@DisplayName("自愈批次中某活码抛异常 → 整批不关闭")
void shouldSkipHealClosureOnError() throws Exception {
    when(qrCodeService.replaceAnomalyAgents(anyLong())).thenThrow(new RuntimeException("boom"));
    invokeDailyAnomalyAutoHeal();
    verify(alertService, never()).resolveOpenByTypesExceptKeys(any(), any());
}

@Test
@DisplayName("DLQ 重放无 exhausted → 关闭 dlq_replay_exhausted")
void shouldResolveDlqExhausted() throws Exception {
    // messageGuardService.replayAllDlq 返回 new ReplaySummary(0, 0)
    invokeAutoReplayDlq();
    verify(alertService).resolveOpenByType("dlq_replay_exhausted");
}
```

（`invokeXxx` 为测试文件内已有的反射调用辅助；实现时按现有写法补。）

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=PatrolWorkerTest test`
Expected: FAIL — 未调用关闭方法。

- [ ] **Step 3: 实现**

- `patrolQrAgentAnomalies`：`allUserids.isEmpty()` 分支在 return 前调用 `resolveOpenByType("qr_agent_anomaly")`；扫描结束若 `anomalyCount == 0` 同样调用。
- `checkOverloadedQrCodes`：`activeQrs.isEmpty()` 分支先 `resolveOpenByTypeExceptQr("traffic_spike", Set.of())`；循环中记录 `Set<Long> overloadedQrIds`；循环后（含无高负载的情况）调用 `resolveOpenByTypeExceptQr("traffic_spike", overloadedQrIds)`。循环体的任何异常向上抛，不执行关闭。
- `checkGlobalPoolLow`：`standbyCount >= 10` 早退前 `resolveOpenByType("empty_backup")`；自动补充后再取一次 standby，`>= 5` 时同样关闭（当前只在 `<5` 时告警）。
- `autoReplayDlq`：重放实际执行（未被节流）且 `summary.exhausted() == 0` 时 `resolveOpenByType("dlq_replay_exhausted")`。
- `dailyAnomalyAutoHeal`：循环里累计 `Set<String> hitKeys` 与 `boolean hadError`；`replaceAnomalyAgents` 的返回 Map 里读 `alertKeys`（List<String>）并入 `hitKeys`，捕获异常时置 `hadError = true`；循环后若 `!hadError`，调用 `resolveOpenByTypesExceptKeys(Set.of("service_teacher_anomaly","anomaly_replace_shortfall","anomaly_replace_blocked"), hitKeys)`。
- `QrCodeService.replaceAnomalyAgents`：在三个 `createAlert` 处同时把 `AlertTypeMeta.keyOf(...)` 加进结果 Map 的 `alertKeys`（blocked / shortfall / 每个服务老师）。注意 blocked 分支提前 return，也要带上 `alertKeys`。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=PatrolWorkerTest,QrCodeServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java \
  src/main/java/com/bookstore/qrcode/service/QrCodeService.java \
  src/test/java/com/bookstore/qrcode/worker/PatrolWorkerTest.java
git commit -m "feat(alert): 巡检类告警条件恢复时自动关闭"
```

---

### Task 7: 继承 / Stream / DLQ 积压的自动关闭

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/job/InheritanceJob.java:230-390`
- Modify: `src/main/java/com/bookstore/qrcode/worker/StreamBacklogMonitor.java:114-161`
- Modify: `src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java:189-201`
- Modify: `src/test/java/com/bookstore/qrcode/worker/StreamBacklogMonitorTest.java`
- Create or modify: `src/test/java/com/bookstore/qrcode/job/InheritanceJobTest.java`（若不存在，新建纯 Mockito 测试；不复用 Spring 上下文）

**Interfaces:**
- Consumes: Task 3 的 `resolveOpenByType`

- [ ] **Step 1: 写测试**

```java
// StreamBacklogMonitorTest
@Test
@DisplayName("流健康（无停摆、PEL 低于阈值）→ 关闭该类积压告警")
void shouldResolveWhenHealthy() {
    // info/group 均为正常值，pendingCount=0
    monitor.checkAll();
    verify(alertService).resolveOpenByType("callback_stream_backlog");
}

@Test
@DisplayName("Stream 不存在/查不到消费组 → 不关闭")
void shouldNotResolveWhenStreamMissing() {
    when(redisTemplate.opsForStream().info(anyString())).thenThrow(new RuntimeException("no stream"));
    monitor.checkAll();
    verify(alertService, never()).resolveOpenByType(anyString());
}

// InheritanceJobTest
@Test
@DisplayName("夜间全量扫描无缺服务老师 → 关闭告警")
void shouldResolveNoServiceOnFullScan() {
    job.processTransferWindow(start, end, "test", false); // 无活跃活码
    verify(alertService).resolveOpenByType("inheritance_no_service");
}

@Test
@DisplayName("白天增量扫描列表为空 → 不关闭")
void shouldNotResolveOnIncrementalScan() {
    job.processTransferWindow(start, end, "test", true);
    verify(alertService, never()).resolveOpenByType("inheritance_no_service");
}

@Test
@DisplayName("扫描中单个活码抛异常 → 不关闭")
void shouldNotResolveWhenScanHadError() {
    // 让 qrAgentRepo.findByQrCodeId 抛异常，activeQrs 有 1 个
    job.processTransferWindow(start, end, "test", false);
    verify(alertService, never()).resolveOpenByType(anyString());
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=StreamBacklogMonitorTest,InheritanceJobTest test`
Expected: FAIL — 未调用关闭。

- [ ] **Step 3: 实现**

- `InheritanceJob.processTransferWindow`：循环里新增 `boolean scanHadError`，`catch` 中置 true；循环后 `if (!incrementalScan)` 且 `!scanHadError`：`noServiceSchools.isEmpty()` → `resolveOpenByType("inheritance_no_service")`；`ambiguousServiceSchools.isEmpty()` → 同理。注意顺序放在现有告警创建之后，避免同轮先关后建。
- `StreamBacklogMonitor`：`check(watch)` 改为返回 `boolean healthy`；`info == null || group == null || lastGenerated == null` 返回 `false`；停摆或 PEL 超阈值（已 `raise`）返回 `false`；走到底返回 `true`。`checkAll` 中 `if (check(watch)) alertService.resolveOpenByType(watch.alertType())`，异常仍按原逻辑只记 debug、不关闭。
- `TransferMonitorWorker.checkDlq`：`dlqLen <= 0` 时先 `alertService.resolveOpenByType("transfer_dlq_backlog")` 再 return。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=StreamBacklogMonitorTest,InheritanceJobTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/job/InheritanceJob.java \
  src/main/java/com/bookstore/qrcode/worker/StreamBacklogMonitor.java \
  src/main/java/com/bookstore/qrcode/worker/TransferMonitorWorker.java \
  src/test/java/com/bookstore/qrcode/worker/StreamBacklogMonitorTest.java \
  src/test/java/com/bookstore/qrcode/job/InheritanceJobTest.java
git commit -m "feat(alert): 继承/积压类告警恢复时自动关闭"
```

---

### Task 8: 每日重置 / 删除报告 / 离职员工的自动关闭

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java:106-172`
- Modify: `src/main/java/com/bookstore/qrcode/service/DeletionReportService.java:219-245`
- Modify: `src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java:108-200`
- Modify: `src/test/java/com/bookstore/qrcode/worker/DailyResetWorkerReleaseTest.java` 或新建 `DailyResetWorkerTest`
- Modify: `src/test/java/com/bookstore/qrcode/service/DeletionReportServiceTest.java`
- Modify: `src/test/java/com/bookstore/qrcode/service/EmployeeSyncServiceTest.java`

**Interfaces:**
- Consumes: `resolveOpenByType`、`resolveOpenByTypeExceptAgents`（Task 3）

- [ ] **Step 1: 写测试**

```java
// DailyResetWorkerTest
@Test
@DisplayName("重置全部成功 → 关闭 daily_reset_failure")
void shouldResolveWhenNoFailures() throws Exception {
    // 5 个步骤全部正常
    invokeDoResetAndReport();
    verify(alertService).resolveOpenByType("daily_reset_failure");
}

@Test
@DisplayName("任一失败 → 不关闭")
void shouldNotResolveWhenAnyFailure() throws Exception {
    doThrow(new RuntimeException("redis down")).when(redisTemplate).scan(any());
    invokeDoResetAndReport();
    verify(alertService, never()).resolveOpenByType("daily_reset_failure");
}

// DeletionReportServiceTest
@Test
@DisplayName("删除报告全部发送成功 → 关闭 deletion_report_fail")
void shouldResolveWhenAllSent() {
    // wecomApi.sendReportMessage 不抛异常
    service.reportRange(...);
    verify(alertService).resolveOpenByType("deletion_report_fail");
}

// EmployeeSyncServiceTest
@Test
@DisplayName("员工本轮在通讯录中 → 关闭其离职告警")
void shouldResolveDepartedAlertForPresentAgents() {
    // getUserList 返回 userids: u1, u2
    service.syncFromWecom();
    verify(alertService).resolveOpenByTypeExceptAgents("employee_departed_service", List.of("u1", "u2"));
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=DailyResetWorkerTest,DeletionReportServiceTest,EmployeeSyncServiceTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `DailyResetWorker.doResetAndReport`：在“任一步骤失败均告警”之后，`if (failures == 0) alertService.resolveOpenByType("daily_reset_failure");`（放在 try-catch 内，避免关闭失败影响主流程）。
- `DeletionReportService.sendToAdmins`：`failed.isEmpty()` 时 `alertService.resolveOpenByType("deletion_report_fail")`；有失败时保持 open。
- `EmployeeSyncService.syncFromWecom`：在员工列表处理完后（`activeUserIds` 已完整），调用 `alertService.resolveOpenByTypeExceptAgents("employee_departed_service", activeUserIds)`。注意放在 `reconcileZombieAgents()` 之后、方法返回前；`userlist` 为空时方法已提前 return，不会误关。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=DailyResetWorkerTest,DeletionReportServiceTest,EmployeeSyncServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java \
  src/main/java/com/bookstore/qrcode/service/DeletionReportService.java \
  src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java \
  src/test/java/com/bookstore/qrcode/worker/DailyResetWorkerTest.java \
  src/test/java/com/bookstore/qrcode/service/DeletionReportServiceTest.java \
  src/test/java/com/bookstore/qrcode/service/EmployeeSyncServiceTest.java
git commit -m "feat(alert): 重置/报告/离职告警恢复时自动关闭"
```

---

### Task 9: 48002 改为系统级每日一条

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/AlertService.java`（新增 `record48002Hit` / `alertWecom48002`；createAlert 命中时降日志级别）
- Modify: `src/main/java/com/bookstore/qrcode/worker/OutboundMsgWorker.java:380-396`
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java:1099-1109`
- Modify: `src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java`
- Modify: `src/test/java/com/bookstore/qrcode/service/TransferServiceTest.java`（若存在；否则用同类命名新建）

**Interfaces:**
- Produces:
  - `AlertService#record48002Hit() : long`（Redis INCR `alert:48002:count:{yyyyMMdd}`，首次设置 TTL 2 天；Redis 异常返回 `-1`）
  - `AlertService#alertWecom48002() : void`（计数 + upsert 一条 `wechat_48002`）

- [ ] **Step 1: 写测试**

```java
@Test
@DisplayName("alertWecom48002 — 系统级低级别、不含员工、文案含次数与交接欢迎语")
void shouldCreateSystemLevel48002() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(anyString())).thenReturn(7L);
    when(alertRepo.findOpenByKey("wechat_48002", null, null, open)).thenReturn(List.of());
    when(alertRepo.save(any(AgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

    alertService.alertWecom48002();

    ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
    verify(alertRepo).save(captor.capture());
    AgentAlert a = captor.getValue();
    assertThat(a.getAlertType()).isEqualTo("wechat_48002");
    assertThat(a.getAgentUserid()).isNull();
    assertThat(a.getSeverity()).isEqualTo(AgentAlert.AlertSeverity.low);
    assertThat(a.getDetail()).contains("7 次").contains("交接欢迎语").doesNotContain("员工权限");
}

@Test
@DisplayName("record48002Hit — Redis 异常返回 -1，不抛出")
void shouldReturnMinusOneOnRedisFailure() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(anyString())).thenThrow(new RuntimeException("down"));
    assertThat(alertService.record48002Hit()).isEqualTo(-1L);
}

@Test
@DisplayName("alertWecom48002 — 计数不可用时文案不写 0 次")
void shouldNotClaimZeroWhenCounterUnavailable() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(anyString())).thenThrow(new RuntimeException("down"));
    when(alertRepo.findOpenByKey(any(), any(), any(), any())).thenReturn(List.of());
    when(alertRepo.save(any(AgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

    alertService.alertWecom48002();

    ArgumentCaptor<AgentAlert> captor = ArgumentCaptor.forClass(AgentAlert.class);
    verify(alertRepo).save(captor.capture());
    assertThat(captor.getValue().getDetail()).contains("今日").doesNotContain("0 次");
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=AlertServiceTest test`
Expected: FAIL — 方法不存在。

- [ ] **Step 3: 实现**

- `record48002Hit()`：key = `"alert:48002:count:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)`；`increment` 后若为 1 则 `expire(key, Duration.ofDays(2))`；异常 `log.warn` 并返回 `-1`。
- `alertWecom48002()`：`long n = record48002Hit();` 文案：
  - `n > 0`：`企微接口 48002（本企业未开通家校沟通能力）：欢迎语/降级消息/交接欢迎语无法送达，今日 N 次发送失败。这不是员工权限问题，无需在企微后台逐个恢复；自 2026-07-21 起持续存在，需更换发送方案。`
  - `n <= 0`：把 `今日 N 次发送失败` 换成 `今日已有发送失败`。
  调 `createAlert(null, "wechat_48002", low, text, none, null)`。
- `createAlert`：条件类命中已有行时日志用 `log.debug`（避免 800+ 行/天的 warn），新建仍 `log.warn`。
- `OutboundMsgWorker.alertNoPermission(String userid)` 整个方法体替换为 `alertService.alertWecom48002();`（参数保留以兼容调用点，方法名可保留）。删除该方法的每员工 Redis 节流键。
- `TransferService` 的 `catch (WecomPermanentException e)`：先判 `if (e.getErrcode() == 48002) alertService.alertWecom48002();`，其余永久错误保持原逻辑（标记已发 + warn 日志）。`TransferService` 新增 `private final AlertService alertService;`（Lombok 构造注入）。
- 说明：交接欢迎语的 48002 只计数，不改 `greetingSent=true` 的既有语义（那是问题 #1 的范围）。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=AlertServiceTest,TransferServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/AlertService.java \
  src/main/java/com/bookstore/qrcode/worker/OutboundMsgWorker.java \
  src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/test/java/com/bookstore/qrcode/service/AlertServiceTest.java \
  src/test/java/com/bookstore/qrcode/service/TransferServiceTest.java
git commit -m "feat(alert): 48002 改系统级每日一条并纳入交接欢迎语计数"
```

---

### Task 10: AlertTriageService 聚合卡片

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/AlertTriageService.java`
- Create: `src/test/java/com/bookstore/qrcode/service/AlertTriageServiceTest.java`

**Interfaces:**
- Consumes: `AlertTypeMeta`
- Produces:
  - `record TriageCard(String alertType, String label, String suggestion, AgentAlert.AlertSeverity severity, long count, long qrCount, long agentCount, LocalDateTime lastSeenAt, String linkUrl, String linkLabel)`
  - `record TriageSummary(List<TriageCard> actionable, List<TriageCard> informational, long actionableCount, long informationalCount)`
  - `AlertTriageService#buildSummary(List<AgentAlert> openAlerts) : TriageSummary`

- [ ] **Step 1: 写测试**

```java
@Test
@DisplayName("按类型聚合，informational 与 actionable 分开")
void shouldSplitByInformational() {
    List<AgentAlert> open = List.of(
        alert("qr_agent_anomaly", null, null, HIGH, now.minusHours(1)),
        alert("traffic_spike", null, 1L, MEDIUM, now.minusHours(2)),
        alert("traffic_spike", null, 2L, MEDIUM, now.minusHours(3)));

    TriageSummary s = service.buildSummary(open);

    assertThat(s.actionable()).extracting(TriageCard::alertType).containsExactly("qr_agent_anomaly");
    assertThat(s.informational()).extracting(TriageCard::alertType).containsExactly("traffic_spike");
    assertThat(s.actionableCount()).isEqualTo(1);
    assertThat(s.informationalCount()).isEqualTo(2);
}

@Test
@DisplayName("同类卡片：条数、涉及活码数、最近时间正确")
void shouldAggregateCounts() {
    TriageCard card = service.buildSummary(List.of(
        alert("traffic_spike", null, 1L, MEDIUM, now.minusHours(5)),
        alert("traffic_spike", null, 1L, MEDIUM, now.minusHours(1)),
        alert("traffic_spike", null, 2L, HIGH, now.minusHours(2))))
        .informational().get(0);

    assertThat(card.count()).isEqualTo(3);
    assertThat(card.qrCount()).isEqualTo(2);
    assertThat(card.severity()).isEqualTo(HIGH);
    assertThat(card.lastSeenAt()).isEqualTo(now.minusHours(1));
    assertThat(card.linkUrl()).isEqualTo("/qrcodes");
}

@Test
@DisplayName("排序：级别高 → 条数多 → 最近")
void shouldSortCards() {
    // high 1 条、medium 3 条、medium 1 条 → high, medium(3), medium(1)
}

@Test
@DisplayName("未知类型仍出现在卡片里，使用原始类型名")
void shouldIncludeUnknownTypes() {
    TriageCard card = service.buildSummary(List.of(alert("future_type", null, null, MEDIUM, now)))
        .actionable().get(0);
    assertThat(card.label()).isEqualTo("future_type");
    assertThat(card.linkUrl()).isEmpty();
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=AlertTriageServiceTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

按 `alertType` 分组 → 每组建 `TriageCard`（label/suggestion/informational 取自 `AlertTypeMeta.of`；linkUrl 取该组第一条非空 `qrCodeId` 调 `linkFor`；qrCount/agentCount 用 distinct 非空值；lastSeenAt 取 `COALESCE(lastSeenAt, createdAt)` 最大值）→ 分 actionable/informational 两列表 → 按 `severity` 降序、`count` 降序、`lastSeenAt` 降序排序。`actionableCount`/`informationalCount` 是条数之和（即告警行数，不是卡片数）。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=AlertTriageServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/AlertTriageService.java \
  src/test/java/com/bookstore/qrcode/service/AlertTriageServiceTest.java
git commit -m "feat(alert): 待处理事项聚合与深链"
```

---

### Task 11: AlertController 默认 open + 卡片模型 + 归档端点

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/AlertController.java:61-208`
- Create: `src/test/java/com/bookstore/qrcode/controller/AlertControllerTest.java`

**Interfaces:**
- Consumes: `AlertService#archiveStale`、`AlertTriageService#buildSummary`、`AlertTypeMeta.ALL`
- Produces: 模型属性 `triage`（`TriageSummary`）、`typeOptions`（`List<AlertTypeMeta>`）、`typeLabelMap`（`Map<String,String>`）；路由 `POST /alerts/archive-stale`

- [ ] **Step 1: 写测试**

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertController 告警中心")
class AlertControllerTest {
    @Mock AlertService alertService;
    @Mock AgentRepository agentRepo;
    @Mock QrCodeRepository qrCodeRepo;
    @Mock AlertTriageService triageService;
    @InjectMocks AlertController controller;

    @Test
    @DisplayName("未传 status → 默认只查 open")
    void shouldDefaultToOpen() {
        when(alertService.findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(null, null, null, null, null, null, 0, 20, model);

        assertThat(model.get("statusFilter")).isEqualTo("open");
        verify(alertService).findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("显式传空 status → 查全部")
    void shouldAllowAllStatus() {
        when(alertService.findAlerts(isNull(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        controller.list("", null, null, null, null, null, 0, 20, new ExtendedModelMap());

        verify(alertService).findAlerts(isNull(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("归档端点调用 archiveStale 并提示数量")
    void shouldArchiveStale() {
        when(alertService.archiveStale()).thenReturn(12);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.archiveStale(null, 0, null, null, null, null, null, null, ra);

        verify(alertService).archiveStale();
        verify(ra).addFlashAttribute(eq("message"), contains("12"));
        assertThat(view).startsWith("redirect:/alerts");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=AlertControllerTest test`
Expected: 编译失败（`archiveStale` 不存在）。

- [ ] **Step 3: 实现**

- `list`：`status == null` → `statusFilter = open`；`status != null && status.isBlank()` → `null`（全部）。查询后：
  ```java
  var openAlerts = alertService.findAlerts(open, null, null, null, null, null, null, PageRequest.of(0, 2000)).getContent();
  model.addAttribute("triage", triageService.buildSummary(openAlerts));
  model.addAttribute("typeOptions", AlertTypeMeta.ALL);
  model.addAttribute("typeLabelMap", AlertTypeMeta.ALL.stream().collect(toMap(AlertTypeMeta::type, AlertTypeMeta::label)));
  ```
  注意：`open` 全量取数用 `size=2000` 与合并后的量级匹配；若超过 2000 条，卡片统计会截断——在页面提示“仅统计前 2000 条”。（2,140 历史数据在维护任务首跑后即降到 <50。）
- 删除 `resolveAll` 方法；新增：
  ```java
  @PostMapping("/archive-stale")
  public String archiveStale(Principal principal, @RequestParam(defaultValue="0") int page, ...同 resolve 的筛选参数..., RedirectAttributes redirect)
  ```
  调 `alertService.archiveStale()`，flash `已归档 N 条可自动恢复/过期告警`。
- 保留 `resolve`、`buildRedirect` 不变。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=AlertControllerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/controller/AlertController.java \
  src/test/java/com/bookstore/qrcode/controller/AlertControllerTest.java
git commit -m "feat(alert): 告警页默认未处理并提供归档端点"
```

---

### Task 12: 告警页模板改版

**Files:**
- Modify: `src/main/resources/templates/alert/list.html`
- Create: `src/test/java/com/bookstore/qrcode/controller/AlertPageRenderTest.java`

**Interfaces:**
- Consumes: 模型 `triage`、`typeOptions`、`typeLabelMap`、`stats`
- Produces: 可渲染的卡片区、明细新增列、动态筛选、归档按钮

- [ ] **Step 1: 写渲染测试**

```java
@WebMvcTest(AlertController.class)
@Import(SecurityConfig.class)
@DisplayName("告警页渲染")
class AlertPageRenderTest {
    @Autowired MockMvc mockMvc;
    @MockBean AlertService alertService;
    @MockBean AlertTriageService triageService;
    @MockBean AgentRepository agentRepo;
    @MockBean QrCodeRepository qrCodeRepo;
    @MockBean(name = "rateLimitRedisTemplate") StringRedisTemplate rateLimitRedisTemplate;
    @MockBean LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser
    @DisplayName("有卡片时渲染标题/建议动作/去处理，且不再出现一键解决全部")
    void shouldRenderTriageCards() throws Exception {
        var card = new TriageCard("qr_agent_anomaly", "异常员工（巡检）", "查看异常员工并处理",
            HIGH, 3, 2, 3, LocalDateTime.now(), "/qrcodes/verify-all-agents", "查看异常员工");
        var summary = new TriageSummary(List.of(card), List.of(), 3, 0);
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of("openCount", 3L));
        when(triageService.buildSummary(any())).thenReturn(summary);

        mockMvc.perform(get("/alerts"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("异常员工（巡检）")))
            .andExpect(content().string(containsString("/qrcodes/verify-all-agents")))
            .andExpect(content().string(not(containsString("一键解决全部"))));
    }
}
```

（`@WebMvcTest` 需要 `SecurityConfig` 的 mock 依赖，照抄 `AdminSchoolControllerRenderTest` 的 `@MockBean` 清单。）

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=AlertPageRenderTest test`
Expected: FAIL — 页面没有卡片区。

- [ ] **Step 3: 改模板**

- 标题栏右侧：把「一键解决全部」表单换成 `POST /alerts/archive-stale`，文案「归档可自动恢复/过期项」，确认框写清“只归档 informational 与过期项，不会关闭真实待办”。
- 统计卡片：保留原有三项；「未处理」拆成两个数（`triage.actionableCount` / `triage.informationalCount`），原 `stats.openCount` 保留为总数。
- 新增卡片区：

```html
<div class="row g-2 mb-3" th:if="${!triage.actionable().isEmpty()}">
  <div class="col-12"><h6 class="text-muted">待处理事项</h6></div>
  <div class="col-md-6 col-xl-4" th:each="c : ${triage.actionable()}">
    <div class="card border-start border-4 p-3"
         th:classappend="${c.severity().name() == 'high' ? 'border-danger' : (c.severity().name() == 'medium' ? 'border-warning' : 'border-secondary')}">
      <div class="fw-semibold" th:text="${c.label()}">类型</div>
      <div class="small text-muted mt-1">
        <span th:text="${c.count()} + ' 条'"></span>
        <span th:if="${c.qrCount() > 0}" th:text="' · ' + ${c.qrCount()} + ' 个活码'"></span>
        <span th:if="${c.agentCount() > 0}" th:text="' · ' + ${c.agentCount()} + ' 名员工'"></span>
        <span th:if="${c.lastSeenAt() != null}" th:text="' · 最近 ' + ${#temporals.format(c.lastSeenAt(), 'MM-dd HH:mm')}"></span>
      </div>
      <div class="small mt-1" th:text="${c.suggestion()}">建议动作</div>
      <a th:if="${!#strings.isEmpty(c.linkUrl())}" th:href="@{${c.linkUrl()}}"
         class="btn btn-sm btn-outline-primary mt-2 align-self-start"
         th:text="${c.linkLabel()} + ' →'">去处理</a>
    </div>
  </div>
</div>
```

- 明细表新增两列（`最近发生`、`次数`）；类型列改用 `typeLabelMap.getOrDefault(...)`；操作列加「去处理 →」链接（`th:href="@{${T(com.bookstore.qrcode.service.AlertTypeMeta).linkFor(alert.alertType, alert.qrCodeId)}}"`，空链接不渲染）+ 「标记已处理」按钮（原 resolve 表单，条件类 `title` 提示“条件仍在时下一轮会重新出现”）。
- 类型筛选下拉用 `th:each="t : ${typeOptions}"` 生成。
- 底部图例改为说明“标记已处理 vs 去处理”的区别。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -q -Dtest=AlertPageRenderTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/templates/alert/list.html \
  src/test/java/com/bookstore/qrcode/controller/AlertPageRenderTest.java
git commit -m "feat(alert): 告警页待处理卡片与深链"
```

---

### Task 13: 全量回归与上线核对清单

**Files:**
- Create: `docs/ops-scripts/2026-10-29-alert-dedup-verify.sql`
- Modify: `docs/superpowers/specs/2026-10-29-alert-noise-reduction-design.md`（仅在实现与本计划有偏差时同步）

- [ ] **Step 1: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS。若既有测试因默认 `status=open` 或模板改动失败，按新语义修正断言，不放宽为“任意状态”。

- [ ] **Step 2: 写核对 SQL**

```sql
-- 上线前：基线
SELECT alert_type, COUNT(*) FROM agent_alert WHERE status='open' GROUP BY alert_type ORDER BY 2 DESC;

-- 执行 ALTER 后（上线前，用户操作）
-- ALTER TABLE agent_alert
--   ADD COLUMN last_seen_at DATETIME NULL AFTER resolved_at,
--   ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER last_seen_at,
--   ADD INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id);
-- UPDATE agent_alert SET last_seen_at = created_at WHERE last_seen_at IS NULL;

-- 首轮维护后：应 ≤ 50，且以下类型各 ≤ 1
SELECT alert_type, COUNT(*) FROM agent_alert WHERE status='open' GROUP BY alert_type ORDER BY 2 DESC;

-- 不应被自动关闭的真实待办仍在
SELECT id, alert_type, agent_userid, qr_code_id, created_at FROM agent_alert
WHERE status='open' AND alert_type IN ('service_teacher_anomaly','anomaly_replace_shortfall','anomaly_replace_blocked')
ORDER BY created_at;

-- 48002：每天一条
SELECT id, detail, last_seen_at FROM agent_alert
WHERE alert_type='wechat_48002' ORDER BY id DESC LIMIT 3;
```

- [ ] **Step 3: 写上线步骤（交用户执行）**

1. 备份 `agent_alert`：`mysqldump ... agent_alert > agent_alert_20261029.sql`。
2. 执行 Step 2 的 `ALTER`（新列可空/有默认值，旧版本代码不受影响）。
3. 按常规流程部署两台（`deploy/deploy.sh prod`）。
4. 看启动日志 `告警维护: 合并 M 条、归档 N 条`；核对 open 数。
5. 逐类型点验卡片跳转；制造一次真实异常验证自动关闭（修好后 ≤5 分钟变 `auto_resolved`）。

- [ ] **Step 4: 提交**

```bash
git add docs/ops-scripts/2026-10-29-alert-dedup-verify.sql
git commit -m "docs(alert): 上线核对 SQL 与清单"
```

---

## 自检记录

- **Spec 覆盖**：4.1 → Task 3；4.2 → Task 6/7/8；4.3 → Task 4/5；4.4 → Task 9；4.5/4.6 → Task 10/11/12；4.7 → Task 1；§5 文件清单逐项有主；§6 验收 → Task 13 清单。
- **类型一致性**：`keyOf` / `isConditionType` 只在 `AlertTypeMeta` 定义；`resolveOpenBy*` 五个方法名在 Task 3 定义、Task 6/7/8 使用；`TriageCard` 字段在 Task 10 定义、Task 11/12 使用。
- **Review Focus 对应测试**：滚动部署 NULL → Task 4；并发重复合并 → Task 4；扫描失败不关闭 → Task 6/7/8；Redis 故障 → Task 9；JSON 引号匹配 → Task 4。
- **未覆盖但已知**：手动“标记已处理”后条件类会重现，只由页面文案说明，不做自动测试。
