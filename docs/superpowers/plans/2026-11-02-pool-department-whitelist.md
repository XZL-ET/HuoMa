# 全局员工池部门准入白名单 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给全局员工池增加「部门准入白名单」，只允许指定部门（含子孙部门）的员工入池承接客户，并提供带预览确认的存量清理与活码撤人。

**Architecture:** 新增 `pool_allowed_department` 表作为唯一事实源；新增 `DepartmentWhitelistService` 统一做「部门树展开 + 员工任意部门命中」判定，所有入池/取人/手动添加入口调用它；新增 `DepartmentWhitelistApplyService` 负责预览、加锁、逐活码补位撤人（每个活码独立事务）与异步企微同步。

**Tech Stack:** Java 17、Spring Boot 3（jakarta）、Spring Data JPA、Thymeleaf、MySQL（生产）/ H2（测试）、Caffeine、Redis（锁）。

**Spec:** `docs/superpowers/specs/2026-11-02-pool-department-whitelist-design.md`

## Global Constraints

- Java 17，Spring Boot 3，JPA 注解使用 `jakarta.persistence.*`。
- DDL 统一由 `src/main/resources/schema.sql`（MySQL）管理，测试库镜像在 `src/test/resources/schema-test.sql`（H2）；新表必须两处都加且幂等。
- 遵守 `CLAUDE.md`：实体只用外键 ID 字段，禁止 `@ManyToOne` / `@OneToMany`。
- 白名单以数据库为唯一事实源，禁止跨节点本地强缓存；部门树可用 Caffeine（30 分钟）。
- 空白名单 = 未启用 = 不限制；启用态禁止存成空集合。
- 企微「联系我」成员列表不能为空：任何撤人前必须保证活码至少 1 名 active 成员。
- `DailyResetWorker` 每日 00:00 会把 `full → active`，无关接待员不能借此复活。
- 仅撤 `QrAgent.role = receptionist`；`service` / `dual` / `blocked` 一律不动。
- 测试命令：`./mvnw -q -Dtest=<ClassName> test`；集成测试继承 `BaseIntegrationTest`，需要企微调用时 `@Import(WecomApiMockConfig.class)`。
- 提交信息沿用仓库风格：`feat:` / `test:` / `fix:`。

## Review Focus

以下五类输入/失败模式最容易被实现者忽略，各自在对应任务的测试里固定：

1. **部门树 API 失败** → `preview/apply` 必须整体中止且不写库；日常闸口降级为精确 ID。（Task 3、Task 8）
2. **活码只剩 1 名无关接待员** → 必须先补位再撤，补不到保留原人；不能产生零成员活码。（Task 7）
3. **员工兼属多部门 / 部门为空 / 无 Employee 记录** → 任一命中即允许；无部门与无记录不允许。（Task 3、Task 4）
4. **每日 00:00 重置** → 无关接待员的 `full` 不能复活，服务老师不受影响。（Task 9）
5. **双节点 + 30 分钟本地缓存** → 白名单变更后另一台必须立即生效（每次操作读 DB）。（Task 3）

---

### Task 1: 数据表、实体与仓储

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/PoolAllowedDepartment.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/PoolAllowedDepartmentRepository.java`
- Modify: `src/main/resources/schema.sql`（追加建表）
- Modify: `src/test/resources/schema-test.sql`（追加 H2 建表）
- Test: `src/test/java/com/bookstore/qrcode/integration/PoolAllowedDepartmentRepositoryTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `PoolAllowedDepartment`（`Long deptId`、`String deptName`、`String createdBy`、`LocalDateTime createdAt`，支持 Lombok `@Builder`）
  - `PoolAllowedDepartmentRepository extends JpaRepository<PoolAllowedDepartment, Long>`，方法 `List<PoolAllowedDepartment> findAllByOrderByDeptIdAsc()`

- [ ] **Step 1: 写失败测试**

```java
@DisplayName("部门准入白名单仓储")
class PoolAllowedDepartmentRepositoryTest extends BaseIntegrationTest {

    @Autowired private PoolAllowedDepartmentRepository repo;

    @BeforeEach
    void setUp() { repo.deleteAll(); }

    @Test
    @DisplayName("按 deptId 升序返回，createdAt 自动填充")
    void savesAndOrdersByDeptId() {
        repo.save(PoolAllowedDepartment.builder()
            .deptId(200L).deptName("后勤").createdBy("tester").build());
        repo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());

        List<PoolAllowedDepartment> rows = repo.findAllByOrderByDeptIdAsc();

        assertThat(rows).extracting(PoolAllowedDepartment::getDeptId)
            .containsExactly(100L, 200L);
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一 deptId 重复保存为更新，不产生重复行")
    void saveSameDeptIdUpdates() {
        repo.save(PoolAllowedDepartment.builder().deptId(100L).deptName("旧名").build());
        repo.save(PoolAllowedDepartment.builder().deptId(100L).deptName("新名").build());

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(100L).orElseThrow().getDeptName()).isEqualTo("新名");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=PoolAllowedDepartmentRepositoryTest test`
Expected: 编译失败，`PoolAllowedDepartment` / `PoolAllowedDepartmentRepository` 不存在。

- [ ] **Step 3: 实现实体、仓储与建表**

`schema.sql` 追加（放在 `global_agent_pool` 相关段落之后）：

```sql
CREATE TABLE IF NOT EXISTS pool_allowed_department (
    dept_id     BIGINT       NOT NULL PRIMARY KEY COMMENT '企微部门ID',
    dept_name   VARCHAR(100) NOT NULL COMMENT '部门名称快照（展示与审计用）',
    created_by  VARCHAR(100) COMMENT '创建人',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_allowed_dept_name (dept_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局员工池允许入池的企微部门';
```

`schema-test.sql` 追加：

```sql
CREATE TABLE IF NOT EXISTS pool_allowed_department (
    dept_id BIGINT PRIMARY KEY,
    dept_name VARCHAR(100) NOT NULL,
    created_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_allowed_dept_name ON pool_allowed_department (dept_name);
```

实体要点：`@Entity @Table(name = "pool_allowed_department")`，`@Id @Column(name = "dept_id") private Long deptId;`，`created_at` 用 `@Column(updatable = false)`，`@PrePersist` 在 `createdAt == null` 时填 `LocalDateTime.now()`。不要加关系注解。

仓储：`List<PoolAllowedDepartment> findAllByOrderByDeptIdAsc();`

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=PoolAllowedDepartmentRepositoryTest test`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/entity/PoolAllowedDepartment.java \
        src/main/java/com/bookstore/qrcode/repository/PoolAllowedDepartmentRepository.java \
        src/main/resources/schema.sql src/test/resources/schema-test.sql \
        src/test/java/com/bookstore/qrcode/integration/PoolAllowedDepartmentRepositoryTest.java
git commit -m "feat: add pool_allowed_department table and repository"
```

---

### Task 2: 企微部门树加载

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/CacheConfig.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/DepartmentService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/DepartmentServiceTest.java`

**Interfaces:**
- Consumes: `WecomApiClient.listDepartments(Long parentId)`（已存在）
- Produces:
  - `public record DeptNode(long id, String name, Long parentId)`（`DepartmentService` 内部 record）
  - `public Map<Long, DeptNode> loadDeptTree() throws WecomApiException`（API 失败时抛出，不吞异常）

- [ ] **Step 1: 写失败测试**

在 `DepartmentServiceTest` 追加：

```java
@Test
@DisplayName("解析部门树：id → 名称 + parentId")
void parsesDeptTree() throws Exception {
    when(wecomApiClient.listDepartments(null)).thenReturn(objectMapper.readTree(
        "{\"department\":[" +
        "{\"id\":100,\"name\":\"校区\",\"parentid\":0}," +
        "{\"id\":101,\"name\":\"校区A\",\"parentid\":100}]}"));

    Map<Long, DepartmentService.DeptNode> tree = departmentService.loadDeptTree();

    assertThat(tree).containsKeys(100L, 101L);
    assertThat(tree.get(101L).name()).isEqualTo("校区A");
    assertThat(tree.get(101L).parentId()).isEqualTo(100L);
    assertThat(tree.get(100L).parentId()).isEqualTo(0L);
}

@Test
@DisplayName("部门树 API 失败时向上抛出，不吞异常")
void loadDeptTreePropagatesFailure() throws Exception {
    when(wecomApiClient.listDepartments(null)).thenThrow(new RuntimeException("api down"));

    assertThatThrownBy(() -> departmentService.loadDeptTree())
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("api down");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentServiceTest test`
Expected: 编译失败，`loadDeptTree` 不存在。

- [ ] **Step 3: 实现**

`CacheConfig` 的 `setCaches(...)` 中追加 `buildCache("departmentTree", 30)`。

`DepartmentService` 新增：

```java
public record DeptNode(long id, String name, Long parentId) {}

@Cacheable(value = "departmentTree", unless = "#result == null || #result.isEmpty()")
public Map<Long, DeptNode> loadDeptTree() throws WecomApiException {
    // 调用 listDepartments(null)，department 数组每项取 id/name；
    // parentid 缺失时置 null，存在时用 asLong()
}
```

注意：不要复用 `departments` 缓存名——两个无参方法同缓存会 key 冲突。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentServiceTest test`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/config/CacheConfig.java \
        src/main/java/com/bookstore/qrcode/service/DepartmentService.java \
        src/test/java/com/bookstore/qrcode/service/DepartmentServiceTest.java
git commit -m "feat: load wecom department tree with parent links"
```

---

### Task 3: 白名单判定服务

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/DepartmentWhitelistServiceTest.java`

**Interfaces:**
- Consumes: `PoolAllowedDepartmentRepository`、`EmployeeRepository`、`DepartmentService.loadDeptTree()`
- Produces（后续任务的唯一判定入口）：

```java
public class DepartmentWhitelistService {
    public boolean isActive();                                   // repo.count() > 0
    public Set<Long> loadAllowedDeptIds(boolean strict);         // 白名单 + 全部子孙
    public boolean isEmployeeAllowed(Employee emp, Set<Long> allowedDeptIds);
    public boolean isAllowed(String userid);                     // 便捷单查
    public List<Employee> filterAllowedEmployees(Collection<Employee> employees);
    public Set<String> filterAllowedUserids(Collection<String> userids);
    public long countAllowedCandidatesNotInPool(Set<String> pooledUserids);
    @Transactional public void replaceWhitelist(List<Long> deptIds, String operator);
    @Transactional public void clearWhitelist();
    public static class DeptTreeUnavailableException extends RuntimeException {}
}
```

- [ ] **Step 1: 写失败测试**

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("部门准入白名单判定")
class DepartmentWhitelistServiceTest {

    @Mock private PoolAllowedDepartmentRepository repo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private DepartmentService departmentService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private DepartmentWhitelistService service;

    private static PoolAllowedDepartment row(long id) {
        return PoolAllowedDepartment.builder().deptId(id).deptName("d" + id).build();
    }

    @Test
    @DisplayName("空表 → 未启用")
    void inactiveWhenEmpty() {
        when(repo.count()).thenReturn(0L);
        assertThat(service.isActive()).isFalse();
    }

    @Test
    @DisplayName("允许集合包含全部子孙部门")
    void expandsDescendants() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L),
            101L, new DepartmentService.DeptNode(101, "校区A", 100L),
            102L, new DepartmentService.DeptNode(102, "校区B", 101L),
            200L, new DepartmentService.DeptNode(200, "后勤", 0L)));

        assertThat(service.loadAllowedDeptIds(true))
            .containsExactlyInAnyOrder(100L, 101L, 102L);
    }

    @Test
    @DisplayName("strict 模式部门树失败 → 抛 DeptTreeUnavailableException")
    void strictFailureThrows() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.loadAllowedDeptIds(true))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
    }

    @Test
    @DisplayName("非 strict 模式失败 → 退化为精确白名单 ID")
    void nonStrictDegrades() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenThrow(new RuntimeException("down"));

        assertThat(service.loadAllowedDeptIds(false)).containsExactly(100L);
    }

    @Test
    @DisplayName("兼属多部门：任一命中即允许")
    void anyDepartmentMembershipAllows() {
        Employee emp = Employee.builder().userid("u1").department("[200,100]").build();
        assertThat(service.isEmployeeAllowed(emp, Set.of(100L))).isTrue();
    }

    @Test
    @DisplayName("无部门或空数组 → 不允许")
    void emptyDepartmentNotAllowed() {
        assertThat(service.isEmployeeAllowed(
            Employee.builder().userid("u1").department(null).build(), Set.of(100L))).isFalse();
        assertThat(service.isEmployeeAllowed(
            Employee.builder().userid("u2").department("[]").build(), Set.of(100L))).isFalse();
    }

    @Test
    @DisplayName("每次调用都读 DB（跨节点实时一致，不做本地强缓存）")
    void readsDbEveryCall() {
        when(repo.findAll()).thenReturn(List.of(row(100L)));
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));

        service.loadAllowedDeptIds(false);
        service.loadAllowedDeptIds(false);

        verify(repo, times(2)).findAll();
    }

    @Test
    @DisplayName("保存白名单时用部门树名称写入快照")
    void replaceWhitelistUsesTreeNames() {
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));

        service.replaceWhitelist(List.of(100L), "tester");

        verify(repo).deleteAll();
        verify(repo).saveAll(argThat(rows -> {
            List<PoolAllowedDepartment> list = (List<PoolAllowedDepartment>) rows;
            return list.size() == 1
                && list.get(0).getDeptId().equals(100L)
                && list.get(0).getDeptName().equals("校区")
                && list.get(0).getCreatedBy().equals("tester");
        }));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistServiceTest test`
Expected: 编译失败，类不存在。

- [ ] **Step 3: 实现**

实现要点：

- `isActive()` = `repo.count() > 0`。
- `loadAllowedDeptIds(strict)`：先 `repo.findAll()`；为空返回 `Set.of()`；再 `departmentService.loadDeptTree()`：
  - 异常：`strict` → `throw new DeptTreeUnavailableException("部门树拉取失败", e)`；否则 WARN + 返回白名单 ID 集合。
  - 成功但树为空：`strict` → 抛 `DeptTreeUnavailableException`。
  - 用 `parentId` 建 `Map<Long, List<Long>>` 子索引，从白名单 ID 递归收集（`LinkedHashSet` 防环）。
- `isEmployeeAllowed`：`allowedDeptIds` 为空返回 false；解析 `emp.getDepartment()`（JSON 数组）取 long 集合，与 allowed 求交集非空即 true；解析异常按空处理。
- `isAllowed(userid)`：`!isActive()` 直接 true；否则 `employeeRepo.findByUserid`，无记录 false；调用 `isEmployeeAllowed`。
- `filterAllowedEmployees`：未启用原样返回；否则加载一次 allowed，过滤（无 Employee 记录不在列表中，天然被排除）。
- `filterAllowedUserids`：`employeeRepo.findByUseridIn(...)` 后走同一逻辑，返回允许集合。
- `countAllowedCandidatesNotInPool`：未启用返回 0；加载 allowed；遍历 `employeeRepo.findAllByActiveTrueOrderByName()`，条件 `wechatStatus == null || == 1`、`!pooledUserids.contains(userid)`、`isEmployeeAllowed`，计数。
- `replaceWhitelist`：`@Transactional`；空列表抛 `IllegalArgumentException("至少选择一个部门")`；strict 加载树；每个 ID 必须在树中，否则抛 `IllegalArgumentException("未知部门: " + id)`；`repo.deleteAll()` + `repo.saveAll(rows)`。
- `clearWhitelist`：`@Transactional`，`repo.deleteAll()`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistServiceTest test`
Expected: PASS（8 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistService.java \
        src/test/java/com/bookstore/qrcode/service/DepartmentWhitelistServiceTest.java
git commit -m "feat: add department whitelist eligibility service"
```

---

### Task 4: 入池过滤与漂移清理（syncToGlobalPool）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java`
- Modify: `src/test/java/com/bookstore/qrcode/integration/WecomApiMockConfig.java`（补 `listDepartments` 默认 stub）
- Test: `src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistEntryFlowTest.java`

**Interfaces:**
- Consumes: `DepartmentWhitelistService.isActive()`、`loadAllowedDeptIds(false)`、`isEmployeeAllowed(Employee, Set<Long>)`、`filterAllowedEmployees`
- Produces: `syncToGlobalPool()` 行为变更——只新增允许员工；启用态下移出已漂移的池记录

- [ ] **Step 1: 在 `WecomApiMockConfig` 补部门树默认 stub**

在 `mockWecomApiClient` 的通讯录段落与 `reapplyBaseStubs` 都加上：

```java
when(mock.listDepartments(null)).thenReturn(objectMapper.readTree(
    "{\"department\":[" +
    "{\"id\":100,\"name\":\"校区\",\"parentid\":0}," +
    "{\"id\":101,\"name\":\"校区A\",\"parentid\":100}," +
    "{\"id\":102,\"name\":\"校区B\",\"parentid\":100}," +
    "{\"id\":200,\"name\":\"后勤\",\"parentid\":0}]}"));
```

（`reapplyBaseStubs` 里用局部 `ObjectMapper om`，沿用文件现有写法。）

- [ ] **Step 2: 写失败测试**

```java
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：入池过滤与漂移清理")
class DepartmentWhitelistEntryFlowTest extends BaseIntegrationTest {

    @Autowired private EmployeeSyncService employeeSyncService;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    @BeforeEach
    void setUp() {
        poolRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
    }

    private Employee emp(String uid, String dept) {
        return Employee.builder().userid(uid).name(uid).department(dept)
            .active(true).wechatStatus(1).build();
    }

    @Test
    @DisplayName("只新增允许部门员工，移出已漂移的池记录")
    void syncAddsAllowedAndRemovesDrifted() {
        employeeRepo.saveAll(List.of(
            emp("ok", "[100]"),
            emp("mixed", "[200,100]"),
            emp("bad", "[200]"),
            emp("none", null)));
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
        poolRepo.save(GlobalAgentPool.builder().agentUserid("bad")
            .status(GlobalAgentPool.PoolStatus.standby).build());

        employeeSyncService.syncToGlobalPool();

        assertThat(poolRepo.findByAgentUserid("ok")).isPresent();
        assertThat(poolRepo.findByAgentUserid("mixed")).isPresent();
        assertThat(poolRepo.findByAgentUserid("bad")).isEmpty();
        assertThat(poolRepo.findByAgentUserid("none")).isEmpty();
    }

    @Test
    @DisplayName("白名单未启用时保持原行为：全部在职可用员工入池")
    void inactiveWhitelistAllowsAll() {
        whitelistRepo.deleteAll();
        employeeRepo.save(emp("bad", "[200]"));

        employeeSyncService.syncToGlobalPool();

        assertThat(poolRepo.findByAgentUserid("bad")).isPresent();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistEntryFlowTest test`
Expected: FAIL——`bad` 被重新加回池、`none` 也在池中。

- [ ] **Step 4: 实现**

`EmployeeSyncService` 注入 `DepartmentWhitelistService whitelist`。

在 `syncToGlobalPool()` 中：

1. 在步骤 3 已构造出 `pooledUserIds`（`Set<String> pooledUserIds = new HashSet<>(poolRepo.findAllAgentUserids());`）之后、构造 `activeNotInPool` 之前，加入漂移清理。**必须放在 `pooledUserIds` 定义之后**，否则变量未定义；不要放到回填 `departmentId` 之前。
```java
if (whitelist.isActive()) {
    List<GlobalAgentPool> pooled = poolRepo.findByAgentUseridIn(new ArrayList<>(pooledUserIds));
    Map<String, Employee> empMap = employeeRepo.findByUseridIn(pooledUserIds).stream()
        .collect(Collectors.toMap(Employee::getUserid, e -> e, (a, b) -> a));
    Set<Long> allowed = whitelist.loadAllowedDeptIds(false);
    List<GlobalAgentPool> drifted = pooled.stream()
        .filter(p -> {
            Employee e = empMap.get(p.getAgentUserid());
            return e == null || !whitelist.isEmployeeAllowed(e, allowed);
        })
        .toList();
    if (!drifted.isEmpty()) {
        poolRepo.deleteAll(drifted);
        pooledUserIds.removeAll(drifted.stream()
            .map(GlobalAgentPool::getAgentUserid).toList());
    }
}
```
注意 `pooledUserIds` 是 `Set<String>`，需要可修改（当前实现是 `new HashSet<>(poolRepo.findAllAgentUserids())`，满足）。

2. 在现有「纯服务老师」过滤之后追加：
```java
int beforeDept = activeNotInPool.size();
activeNotInPool = whitelist.filterAllowedEmployees(activeNotInPool);
if (activeNotInPool.size() < beforeDept) {
    log.info("全局池同步：排除 {} 个不在部门准入范围的员工", beforeDept - activeNotInPool.size());
}
```

3. 不要改动 `ensureInPool`（Task 5 处理）。

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistEntryFlowTest test`
Expected: PASS（2 个测试）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/EmployeeSyncService.java \
        src/test/java/com/bookstore/qrcode/integration/WecomApiMockConfig.java \
        src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistEntryFlowTest.java
git commit -m "feat: gate global pool sync by department whitelist"
```

---

### Task 5: 入池与取人防御（ensureInPool / takeStandby）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/GlobalAgentPoolServiceTest.java`

**Interfaces:**
- Consumes: `DepartmentWhitelistService.isActive()`、`isAllowed(String)`、`loadAllowedDeptIds(false)`、`isEmployeeAllowed(Employee, Set<Long>)`
- Produces: `ensureInPool`、`takeStandby`（两个重载）在启用态下跳过并懒清理无关员工

- [ ] **Step 1: 更新并追加测试**

在 `GlobalAgentPoolServiceTest` 增加字段：

```java
@Mock private DepartmentWhitelistService whitelistService;
```

追加：

```java
@Test
@DisplayName("ensureInPool — 无关部门员工不入池，并清理已有记录")
void ensureInPoolSkipsDisallowed() {
    when(whitelistService.isActive()).thenReturn(true);
    when(whitelistService.isAllowed("user1")).thenReturn(false);
    GlobalAgentPool existing = GlobalAgentPool.builder().id(1L).agentUserid("user1").build();
    when(poolRepo.findByAgentUserid("user1")).thenReturn(Optional.of(existing));

    poolService.ensureInPool("user1", 100);

    verify(poolRepo).delete(existing);
    verify(poolRepo, never()).save(any());
}

@Test
@DisplayName("takeStandby — 懒清理无关部门候选并跳过")
void takeStandbyDeletesDisallowedCandidate() {
    when(whitelistService.isActive()).thenReturn(true);
    when(whitelistService.loadAllowedDeptIds(false)).thenReturn(Set.of(100L));
    GlobalAgentPool p = GlobalAgentPool.builder()
        .id(1L).agentUserid("user1").sortOrder(1)
        .status(GlobalAgentPool.PoolStatus.standby).build();
    Employee bad = Employee.builder().userid("user1")
        .department("[200]").active(true).wechatStatus(1).build();
    when(poolRepo.findStandbysForUpdate(GlobalAgentPool.PoolStatus.standby))
        .thenReturn(List.of(p));
    when(employeeRepo.findByUserid("user1")).thenReturn(Optional.of(bad));
    when(whitelistService.isEmployeeAllowed(bad, Set.of(100L))).thenReturn(false);

    assertThat(poolService.takeStandby(Set.of())).isNull();
    verify(poolRepo).delete(p);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=GlobalAgentPoolServiceTest test`
Expected: 编译失败（`whitelistService` 未注入）或断言失败。

- [ ] **Step 3: 实现**

`GlobalAgentPoolService` 注入 `DepartmentWhitelistService whitelist`。

- `ensureInPool` 方法开头：
```java
if (whitelist.isActive() && !whitelist.isAllowed(userid)) {
    poolRepo.findByAgentUserid(userid).ifPresent(pool -> {
        poolRepo.delete(pool);
        log.info("部门准入：移出池记录 userid={}", userid);
    });
    return;
}
```
- `takeStandby(Set<String>)` 开头（用 boolean 记住启用态，避免 `isActive()` 与 `loadAllowedDeptIds()` 两次读表之间白名单被清空导致整段过滤被跳过）：
```java
boolean whitelistActive = whitelist.isActive();
Set<Long> allowedDeptIds = whitelistActive
    ? whitelist.loadAllowedDeptIds(false) : Set.of();
```
每个候选在加载 `Employee emp` 之后追加：
```java
if (whitelistActive && (emp == null || !whitelist.isEmployeeAllowed(emp, allowedDeptIds))) {
    poolRepo.delete(p);
    log.info("部门准入：懒清理无关部门员工 userid={}", p.getAgentUserid());
    continue;
}
```
- `selectFromDeptCandidates` 同样处理（它由带部门的 `takeStandby` 调用，allowed 集合需要作为参数传入或在方法内加载一次；选择在方法内加载一次，保持签名不变）。
- 注意：`emp == null` 在现有逻辑中放行，启用态下必须改为不放行。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=GlobalAgentPoolServiceTest test`
Expected: PASS（原 7 个 + 新增 2 个）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java \
        src/test/java/com/bookstore/qrcode/service/GlobalAgentPoolServiceTest.java
git commit -m "feat: enforce department whitelist in pool entry and standby selection"
```

---

### Task 6: 手动入口（addAgent / restore）

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/QrCodeService.java`（`addAgent`）
- Modify: `src/main/java/com/bookstore/qrcode/controller/AgentController.java`（`restore`）
- Modify: `src/test/java/com/bookstore/qrcode/controller/AgentControllerTest.java`（构造器补 mock）
- Test: `src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistManualEntryTest.java`

**Interfaces:**
- Consumes: `DepartmentWhitelistService.isActive()`、`isAllowed(String)`
- Produces: `addAgent` 拒绝无关员工（`RuntimeException`，消息含「部门准入」）；`restore` 跳过无关接待员的 `removed` 绑定并在提示中列出

- [ ] **Step 1: 写失败测试**

```java
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：手动入口")
class DepartmentWhitelistManualEntryTest extends BaseIntegrationTest {

    @Autowired private QrCodeService qrCodeService;
    @Autowired private AgentController agentController;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        qrAgentRepo.deleteAll();
        qrCodeRepo.deleteAll();
        whitelistRepo.deleteAll();
        employeeRepo.deleteAll();
        agentRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        employeeRepo.save(Employee.builder().userid("ok").name("ok")
            .department("[100]").active(true).wechatStatus(1).build());
        employeeRepo.save(Employee.builder().userid("bad").name("bad")
            .department("[200]").active(true).wechatStatus(1).build());
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("准入测试").schoolId("SCH-WL-1")
            .regionCity("深圳").regionDistrict("南山区").build());
    }

    @Test
    @DisplayName("addAgent 拒绝无关部门员工")
    void addAgentRejectsDisallowed() {
        assertThatThrownBy(() -> qrCodeService.addAgent(qr.getId(), "bad"))
            .hasMessageContaining("部门准入");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad")).isEmpty();
    }

    @Test
    @DisplayName("addAgent 允许部门员工正常添加")
    void addAgentAllowsEligible() {
        qrCodeService.addAgent(qr.getId(), "ok");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "ok"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("restore 不复活无关部门接待员的 removed 绑定")
    void restoreSkipsDisallowedRemovedBinding() {
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist)
            .overallStatus(Agent.OverallStatus.melted).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist)
            .status(QrAgent.AgentStatus.removed).build());

        var redirect = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        agentController.restoreAgent("bad", "pool", "", redirect);

        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(redirect.getFlashAttributes().get("message").toString())
            .contains("部门准入");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistManualEntryTest test`
Expected: FAIL——`bad` 被成功添加/复活。

- [ ] **Step 3: 实现**

- `QrCodeService` 注入 `DepartmentWhitelistService whitelist`；在 `addAgent` 的 `getById(qrCodeId)` 之后：
```java
if (whitelist.isActive() && !whitelist.isAllowed(agentUserid)) {
    throw new RuntimeException("该员工所属部门不在部门准入范围内: " + agentUserid);
}
```
- `AgentController` 注入 `DepartmentWhitelistService whitelist`；在 `restoreAgent` 的绑定循环里，非 active 分支、`alreadyActiveQrIds` 判断之后加入：
```java
if (qa.getRole() == QrAgent.AgentRole.receptionist
    && whitelist.isActive() && !whitelist.isAllowed(userid)) {
    skippedByWhitelist.add(qrName);
    continue;
}
```
新增局部 `List<String> skippedByWhitelist = new ArrayList<>();`；在构造反馈消息处追加：
```java
if (!skippedByWhitelist.isEmpty()) {
    msg.append("；按部门准入跳过活码：").append(String.join("、", skippedByWhitelist));
}
```
- 更新 `AgentControllerTest` 的构造器：新增 `mock(DepartmentWhitelistService.class)`（按构造器参数顺序放在新增位置）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistManualEntryTest,AgentControllerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java \
        src/main/java/com/bookstore/qrcode/controller/AgentController.java \
        src/test/java/com/bookstore/qrcode/controller/AgentControllerTest.java \
        src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistManualEntryTest.java
git commit -m "feat: block disallowed departments on manual agent entry and restore"
```

---

### Task 7: 活码撤人核心（补位后撤 active，full 直接撤）

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistRemovalService.java`
- Test: `src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistRemovalTest.java`

**Interfaces:**
- Consumes: `GlobalAgentPoolService.takeStandby(Set<String>, Long)`、`QrAgentRepository`、`QrRotateLogRepository`
- Produces:

```java
public class DepartmentWhitelistRemovalService {
    public record QrRemovalOutcome(int removed, int backfilled, boolean skipped, String skipReason) {}
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QrRemovalOutcome removeReceptionistsFromQr(Long qrCodeId, Set<String> disallowedUserids);
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeFullReceptionists(Collection<Long> qrAgentIds);
}
```

- [ ] **Step 1: 写失败测试**

```java
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：活码撤接待员")
class DepartmentWhitelistRemovalTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistRemovalService removalService;
    @Autowired private GlobalAgentPoolService poolService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private QrRotateLogRepository rotateLogRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        rotateLogRepo.deleteAll();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
        whitelistRepo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());
        employeeRepo.save(Employee.builder().userid("bad").name("bad")
            .department("[200]").active(true).wechatStatus(1).build());
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("撤人测试").schoolId("SCH-RM-1")
            .regionCity("深圳").regionDistrict("南山区").build());
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
    }

    private QrAgent bind(String uid, QrAgent.AgentRole role, QrAgent.AgentStatus status) {
        return qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qr.getId()).agentUserid(uid).role(role)
            .dailyMax(150).sortOrder(0).status(status).build());
    }

    @Test
    @DisplayName("最后一名无关接待员：先补位再撤，活码不为空")
    void backfillsThenRemoves() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);
        // 先建允许部门的员工记录，再入池（白名单启用态下无 Employee 记录会被拦截）
        employeeRepo.save(Employee.builder().userid("good").name("good")
            .department("[100]").active(true).wechatStatus(1).build());
        poolService.ensureInPool("good", 150);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(outcome.backfilled()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        long active = qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active).size();
        assertThat(active).isEqualTo(1);
        assertThat(rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(
            qr.getId(), org.springframework.data.domain.Pageable.unpaged())).isNotEmpty();
    }

    @Test
    @DisplayName("池枯竭：保留原人并标记 skipped")
    void keepsOriginalWhenPoolExhausted() {
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).contains("池");
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }

    @Test
    @DisplayName("有服务老师兜底时无需补位，只撤接待员")
    void removesWithoutBackfillWhenServicePresent() {
        agentRepo.save(Agent.builder().userid("svc").name("svc")
            .role(Agent.AgentRole.service).build());
        bind("svc", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);
        bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.backfilled()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
    }

    @Test
    @DisplayName("full 接待员直接标 removed，不需要补位")
    void removesFullReceptionists() {
        QrAgent full = bind("bad", QrAgent.AgentRole.receptionist, QrAgent.AgentStatus.full);

        int removed = removalService.removeFullReceptionists(List.of(full.getId()));

        assertThat(removed).isEqualTo(1);
        assertThat(qrAgentRepo.findById(full.getId()).orElseThrow().getStatus())
            .isEqualTo(QrAgent.AgentStatus.removed);
    }

    @Test
    @DisplayName("服务老师即使传入 disallowedUserids 也不动")
    void neverRemovesServiceRole() {
        bind("bad", QrAgent.AgentRole.service, QrAgent.AgentStatus.active);

        var outcome = removalService.removeReceptionistsFromQr(qr.getId(), Set.of("bad"));

        assertThat(outcome.removed()).isZero();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.active));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistRemovalTest test`
Expected: 编译失败，服务不存在。

- [ ] **Step 3: 实现**

`removeReceptionistsFromQr` 要点：

1. `QrCode qr = qrCodeRepo.findById(qrCodeId).orElseThrow(...)`。
2. 取 `all = qrAgentRepo.findByQrCodeId(qrCodeId)`；`active = all 中 status == active`。
3. `D = active 中 role == receptionist 且 userid ∈ disallowedUserids`；若 `D.isEmpty()` 返回 `(0,0,false,null)`。
4. `remaining = active.size() - D.size()`。
5. `exclude = all 中 status != removed 的 userid 集合`。
6. 当 `remaining < 1`：
   - 循环调用 `poolService.takeStandby(exclude, qr.getDepartmentId())`；
   - 为 null → 返回 `new QrRemovalOutcome(0, 0, true, "全局池枯竭，无法补位")`；
   - 否则创建 `QrAgent`：`role=receptionist`、`dailyMax=pool.getDailyMax()`、`sortOrder=当前最大+1`、`status=active`、`temporary=false`；`exclude.add(userid)`；`backfilled++`；`remaining++`。
7. `D` 全部 `status=removed` 并 `saveAll`；每人写一条 `QrRotateLog`（`fromUserid`=userid，`reason="部门准入白名单移出"`）。
8. 返回 `new QrRemovalOutcome(D.size(), backfilled, false, null)`。

`removeFullReceptionists`：按 ID 加载，仅处理 `status == full && role == receptionist`，标 `removed`，返回实际数量。

不要在此服务里操作 Redis 锁——锁由 Task 8 的编排层负责。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistRemovalTest test`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistRemovalService.java \
        src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistRemovalTest.java
git commit -m "feat: remove disallowed receptionists from qr codes with backfill"
```

---

### Task 8: 预览、确认与编排（apply）

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/dto/DepartmentWhitelistPreview.java`
- Create: `src/main/java/com/bookstore/qrcode/dto/DepartmentWhitelistApplyResult.java`
- Create: `src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistTxService.java`
- Create: `src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistApplyService.java`
- Test: `src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistApplyFlowTest.java`

**Interfaces:**
- Consumes: `DepartmentWhitelistService`、`DepartmentWhitelistRemovalService`、`DepartmentService.loadDeptTree()`、`GlobalAgentPoolRepository`、`QrAgentRepository`、`EmployeeRepository`、`OperationLogService`、`AgentRotationService.syncQrCodeToWechatAsync(Long)`、`StringRedisTemplate`、`SAFE_UNLOCK_SCRIPT`
- Produces:

```java
public record DepartmentWhitelistPreview(
    boolean active,
    List<PoolRemoval> poolRemovals,
    List<QrRemoval> qrRemovals,
    List<NoDeptEmployee> noDepartmentEmployees,
    int serviceBindingsExempt,
    List<String> warnings) {
    public record PoolRemoval(String userid, String name, Long departmentId, String poolStatus) {}
    public record QrRemoval(Long qrCodeId, String schoolName, List<Member> members,
                            boolean needsBackfill, boolean poolCanBackfill) {}
    public record Member(String userid, String name, Long departmentId) {}
    public record NoDeptEmployee(String userid, String name) {}
}

public record DepartmentWhitelistApplyResult(
    int allowedDeptCount, int poolRemoved,
    int activeReceptionistsRemoved, int fullReceptionistsRemoved,
    int backfilled, int qrCodesProcessed, int qrCodesSkipped,
    List<String> failures) {}
```

```java
public class DepartmentWhitelistTxService {
    @Transactional
    public int replaceWhitelistAndRemoveFromPool(
        List<Long> deptIds, Set<Long> allowedDeptIds, String operator);
}
```

```java
public class DepartmentWhitelistApplyService {
    public DepartmentWhitelistPreview preview(List<Long> deptIds);
    public DepartmentWhitelistApplyResult apply(List<Long> deptIds, String operator);
    public long countDriftedActiveReceptionists();
}
```

- [ ] **Step 1: 写失败测试**

```java
@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：预览与应用")
class DepartmentWhitelistApplyFlowTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistApplyService applyService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private OperationLogRepository operationLogRepo;
    @Autowired private WecomApiClient wecomApi;
    @Autowired private CacheManager cacheManager;

    private QrCode qr;

    @BeforeEach
    void setUp() {
        // CacheConfig 是无条件 @Bean，spring.cache.type=none 不会生效；
        // departmentTree 无参缓存 key 固定，前一用例缓存后，部门树故障用例的 stub 会命中缓存而失效
        var treeCache = cacheManager.getCache("departmentTree");
        if (treeCache != null) treeCache.clear();
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        operationLogRepo.deleteAll();
        whitelistRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("应用测试").schoolId("SCH-AP-1")
            .regionCity("深圳").regionDistrict("南山区").build());
    }

    private Employee emp(String uid, String dept) {
        return Employee.builder().userid(uid).name(uid).department(dept)
            .active(true).wechatStatus(1).build();
    }

    @Test
    @DisplayName("apply：保存白名单、移出池、撤接待员、写审计")
    void appliesWhitelistAndCleansUp() {
        employeeRepo.saveAll(List.of(emp("bad", "[200]"), emp("good", "[100]")));
        agentRepo.saveAll(List.of(
            Agent.builder().userid("bad").name("bad")
                .role(Agent.AgentRole.receptionist).build(),
            Agent.builder().userid("good").name("good")
                .role(Agent.AgentRole.receptionist).build()));
        poolRepo.saveAll(List.of(
            GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
                .status(GlobalAgentPool.PoolStatus.standby).build(),
            GlobalAgentPool.builder().agentUserid("good").departmentId(100L)
                .status(GlobalAgentPool.PoolStatus.standby).build()));
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        var result = applyService.apply(List.of(100L), "tester");

        assertThat(result.allowedDeptCount()).isEqualTo(1);
        assertThat(result.poolRemoved()).isEqualTo(1);
        assertThat(result.activeReceptionistsRemoved()).isEqualTo(1);
        assertThat(result.backfilled()).isEqualTo(1);
        assertThat(poolRepo.findByAgentUserid("bad")).isEmpty();
        assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
            .hasValueSatisfying(qa -> assertThat(qa.getStatus())
                .isEqualTo(QrAgent.AgentStatus.removed));
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .isNotEmpty();
        assertThat(whitelistRepo.findAllByOrderByDeptIdAsc()).hasSize(1);
        assertThat(operationLogRepo.findAll()).anySatisfy(log ->
            assertThat(log.getAction()).isEqualTo("dept_whitelist_apply"));
    }

    @Test
    @DisplayName("preview 只读：按待应用部门算出影响，但不写白名单、不动数据")
    void previewDoesNotWrite() {
        employeeRepo.save(emp("bad", "[200]"));
        poolRepo.save(GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
            .status(GlobalAgentPool.PoolStatus.standby).build());

        var preview = applyService.preview(List.of(100L));

        assertThat(preview.poolRemovals()).hasSize(1);
        assertThat(preview.poolRemovals().get(0).userid()).isEqualTo("bad");
        assertThat(whitelistRepo.count()).isZero();
        assertThat(poolRepo.findByAgentUserid("bad")).isPresent();
    }

    @Test
    @DisplayName("部门树失败：apply 中止且不写库")
    void applyAbortsWhenTreeUnavailable() {
        when(wecomApi.listDepartments(null)).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> applyService.apply(List.of(100L), "tester"))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
        assertThat(whitelistRepo.count()).isZero();
    }

    @Test
    @DisplayName("部门树失败：preview 同样中止且不写库")
    void previewAbortsWhenTreeUnavailable() {
        when(wecomApi.listDepartments(null)).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> applyService.preview(List.of(100L)))
            .isInstanceOf(DepartmentWhitelistService.DeptTreeUnavailableException.class);
        assertThat(whitelistRepo.count()).isZero();
    }

    @Test
    @DisplayName("重复 apply 幂等：第二次不再移除")
    void applyIsIdempotent() {
        employeeRepo.save(emp("bad", "[200]"));
        agentRepo.save(Agent.builder().userid("bad").name("bad")
            .role(Agent.AgentRole.receptionist).build());
        poolRepo.save(GlobalAgentPool.builder().agentUserid("bad").departmentId(200L)
            .status(GlobalAgentPool.PoolStatus.standby).build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        applyService.apply(List.of(100L), "tester");
        var second = applyService.apply(List.of(100L), "tester");

        assertThat(second.poolRemoved()).isZero();
        assertThat(second.activeReceptionistsRemoved()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistApplyFlowTest test`
Expected: 编译失败，服务与 DTO 不存在。

- [ ] **Step 3: 实现 DTO 与事务服务**

- `DepartmentWhitelistTxService.replaceWhitelistAndRemoveFromPool`（注入 `DepartmentWhitelistService`、`GlobalAgentPoolRepository`、`EmployeeRepository`）：
  1. 直接调用 `whitelist.replaceWhitelist(deptIds, operator)` 保存白名单——**不要在这里再写一遍 deleteAll/saveAll**，否则同一份保存逻辑存在两处实现（`replaceWhitelist` 自身带 `@Transactional`，会加入当前事务，并负责部门名校验与快照写入）；
  2. `poolRepo.findAll()` + `employeeRepo.findByUseridIn(userids)`，`emp == null || !whitelist.isEmployeeAllowed(emp, allowedDeptIds)` → 收集删除；`poolRepo.deleteAll(disallowed)`；返回删除数。

- `DepartmentWhitelistApplyService`：注入上述依赖 + `DepartmentWhitelistRemovalService` + `AgentRotationService` + `OperationLogService` + `StringRedisTemplate`。

- [ ] **Step 4: 实现 apply 编排**

`apply(deptIds, operator)`：

1. `deptIds` 为空 → `IllegalArgumentException("至少选择一个部门")`。
2. 获取全局锁：`setIfAbsent("lock:pool:dept-whitelist", uuid, Duration.ofMinutes(10))`；失败抛 `IllegalStateException("已有部门准入操作在执行，请稍后重试")`。`finally` 用 `SAFE_UNLOCK_SCRIPT` 释放。
3. `Map<Long, DeptNode> tree = departmentService.loadDeptTree()`（异常 → `DeptTreeUnavailableException`）。
4. 校验每个 ID 在树中；用 `tree` 在编排层本地递归展开 `allowed`（白名单根 ID + 全部子孙）。**不要调用 `loadAllowedDeptIds(true)`**——它读的是旧表，此时新白名单尚未写入。
5. `poolRemoved = txService.replaceWhitelistAndRemoveFromPool(deptIds, allowed, operator)`。
6. 计算无关员工：
   - `activeReceptionists = qrAgentRepo.findByStatusAndRole(active, receptionist)`；
   - `fullReceptionists = qrAgentRepo.findByStatusAndRole(full, receptionist)`；
   - 批量取 Employee（`findByUseridIn`），无关 = 无 Employee 或 `!isEmployeeAllowed`。
7. 按 `qrCodeId` 分组 active 无关成员；对每个活码（**加轮换锁** `rotate:lock:{qrId}:rotate`，TTL 30s，拿不到则计入 skipped）调用 `removalService.removeReceptionistsFromQr(qrId, userids)`；累计结果，收集失败的活码。锁在编排层获取，调用返回（REQUIRES_NEW 已提交）后释放。
8. `fullRemoved = removalService.removeFullReceptionists(fullDisallowedIds)`。
9. 对每个受影响活码注册 `TransactionSynchronizationManager.registerSynchronization`？由于需要异步触发且没有外层事务，直接在移除完成后调用 `agentRotationService.syncQrCodeToWechatAsync(qrId)`（`@Async`，不阻塞）。
10. `operationLogService.log(operator, "dept_whitelist_apply", "agent_pool_department", "-", 汇总描述)`。
11. 返回 `DepartmentWhitelistApplyResult`。

`preview(deptIds)`：同样的步骤 3–4（不加锁、不写库），计算池移除名单、活码撤人名单（含 `needsBackfill = 该码 active 总数 - 待撤数 < 1`、`poolCanBackfill = 存在允许的 standby 且不在该码上`）、无部门员工、服务老师豁免计数与警告；返回 DTO。

`countDriftedActiveReceptionists()`：统计当前 active 接待员中部门不命中的人数（启用态），供页面提醒。

- [ ] **Step 5: 增加缺失的仓储方法**

`QrAgentRepository` 追加：

```java
List<QrAgent> findByStatusAndRole(QrAgent.AgentStatus status, QrAgent.AgentRole role);
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistApplyFlowTest test`
Expected: PASS（4 个测试）。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/dto/DepartmentWhitelistPreview.java \
        src/main/java/com/bookstore/qrcode/dto/DepartmentWhitelistApplyResult.java \
        src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistTxService.java \
        src/main/java/com/bookstore/qrcode/service/DepartmentWhitelistApplyService.java \
        src/main/java/com/bookstore/qrcode/repository/QrAgentRepository.java \
        src/test/java/com/bookstore/qrcode/integration/DepartmentWhitelistApplyFlowTest.java
git commit -m "feat: add department whitelist preview and apply orchestration"
```

---

### Task 9: 每日重置护栏与巡检告警

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java`
- Modify: `src/test/java/com/bookstore/qrcode/worker/DailyResetWorkerReleaseTest.java`
- Modify: `src/test/java/com/bookstore/qrcode/worker/PatrolWorkerTest.java`

**Interfaces:**
- Consumes: `DepartmentWhitelistService.isActive()`、`filterAllowedUserids`、`countAllowedCandidatesNotInPool`
- Produces: `recoverFullAgents` 不复活无关接待员；`checkGlobalPoolLow` 在「已无人可补」时不再告警

- [ ] **Step 1: 写失败测试**

`DailyResetWorkerReleaseTest` 追加：

```java
@Test
@DisplayName("每日重置：无关接待员 full 不复活，服务老师不受影响")
void doesNotReviveDisallowedReceptionist() {
    whitelistRepo.deleteAll();
    whitelistRepo.save(PoolAllowedDepartment.builder()
        .deptId(100L).deptName("校区").createdBy("tester").build());
    employeeRepo.save(Employee.builder().userid("bad").name("bad")
        .department("[200]").active(true).wechatStatus(1).build());
    agentRepo.save(Agent.builder().userid("bad").name("bad")
        .role(Agent.AgentRole.receptionist)
        .overallStatus(Agent.OverallStatus.normal).build());
    qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("bad")
        .role(QrAgent.AgentRole.receptionist)
        .status(QrAgent.AgentStatus.full).build());

    dailyResetWorker.recoverFullAgents();

    assertThat(qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "bad"))
        .hasValueSatisfying(qa -> assertThat(qa.getStatus())
            .isEqualTo(QrAgent.AgentStatus.removed));
}
```

（该测试类需新增 `@Autowired` 的 `PoolAllowedDepartmentRepository`、`EmployeeRepository`，并在 `setUp` 里清空这两个表。）

`PatrolWorkerTest` 追加（需把 `checkGlobalPoolLow` 改成包级可见）：

```java
@Test
@DisplayName("允许范围内已无人可补时，池枯竭不再告警")
void suppressesAlertWhenNoAllowedCandidates() {
    when(poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby)).thenReturn(0L);
    when(employeeSyncService.syncToGlobalPool()).thenReturn(0);
    when(whitelistService.isActive()).thenReturn(true);
    when(poolRepo.findAllAgentUserids()).thenReturn(List.of());
    when(whitelistService.countAllowedCandidatesNotInPool(any())).thenReturn(0L);

    worker.checkGlobalPoolLow();

    verify(alertService, never()).alertEmptyBackup(any(), any());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DailyResetWorkerReleaseTest,PatrolWorkerTest test`
Expected: FAIL——无关接待员被复活；或编译失败（`checkGlobalPoolLow` 不可见 / 构造器参数不匹配）。

- [ ] **Step 3: 实现**

`DailyResetWorker`：注入 `DepartmentWhitelistService whitelist`；在 `recoverFullAgents` 的 `full → active` 循环前：

```java
Set<String> allowedReceptionists = null;
if (whitelist.isActive()) {
    List<String> receptionistIds = fullAgents.stream()
        .filter(qa -> qa.getRole() == QrAgent.AgentRole.receptionist)
        .map(QrAgent::getAgentUserid).toList();
    allowedReceptionists = whitelist.filterAllowedUserids(receptionistIds);
}
```

**放在 `for (var qa : fullAgents)` 循环体的最开头**（在 `agentRepo.findById` 与 `setStatus(active)` 之前），否则会先复活再标移除，多一次无意义写入：

```java
if (qa.getRole() == QrAgent.AgentRole.receptionist
    && allowedReceptionists != null
    && !allowedReceptionists.contains(qa.getAgentUserid())) {
    qa.setStatus(QrAgent.AgentStatus.removed);
    qrAgentRepo.save(qa);
    affectedQrIds.add(qa.getQrCodeId());
    log.info("部门准入：每日重置不复活无关接待员 qr={}, userid={}",
        qa.getQrCodeId(), qa.getAgentUserid());
    continue;
}
```

`PatrolWorker`：注入 `DepartmentWhitelistService whitelist`；把 `private void checkGlobalPoolLow()` 改成包级可见 `void checkGlobalPoolLow()`；补池后计算：

```java
boolean whitelistActive = whitelist.isActive();
long allowedCandidates = whitelistActive
    ? whitelist.countAllowedCandidatesNotInPool(new HashSet<>(poolRepo.findAllAgentUserids()))
    : -1L;
String suffix = whitelistActive
    ? "（受部门准入限制，可入池上限 " + allowedCandidates + " 人）" : "";
```

- `standbyCount == 0` 且 `whitelistActive && allowedCandidates == 0` → 只 `log.info`，不告警；
- 其余告警文案追加 `suffix`。

同步更新 `PatrolWorkerTest.buildWorker(...)`：新增 `mock(DepartmentWhitelistService.class)` 参数；把测试里的 `poolRepo` / `employeeSyncService` / `whitelistService` 提为字段 mock，便于断言。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DailyResetWorkerReleaseTest,PatrolWorkerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java \
        src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java \
        src/test/java/com/bookstore/qrcode/worker/DailyResetWorkerReleaseTest.java \
        src/test/java/com/bookstore/qrcode/worker/PatrolWorkerTest.java
git commit -m "feat: keep disallowed receptionists out on daily reset and mute impossible pool alerts"
```

---

### Task 10: 后台配置页与控制器

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/dto/DeptOption.java`
- Modify: `src/main/java/com/bookstore/qrcode/controller/AgentController.java`
- Modify: `src/main/resources/templates/agent/list.html`
- Create: `src/main/resources/templates/agent/departments.html`
- Create: `src/test/java/com/bookstore/qrcode/controller/DepartmentWhitelistControllerTest.java`
- Create: `src/test/java/com/bookstore/qrcode/controller/DepartmentWhitelistPageRenderTest.java`
- Modify: `src/test/java/com/bookstore/qrcode/controller/AgentControllerTest.java`（构造器再补 mock）

**Interfaces:**
- Consumes: `DepartmentWhitelistService`、`DepartmentWhitelistApplyService`、`DepartmentService.loadDeptTree()`、`PoolAllowedDepartmentRepository`
- Produces（HTTP）：
  - `GET /agents/departments` → 视图 `agent/departments`，model：`view="departments"`、`whitelistActive`、`allowedDepts`、`deptOptions`、`driftedReceptionistCount`、`title`
  - `POST /agents/departments/preview`（`List<Long> deptIds`）→ JSON `DepartmentWhitelistPreview`
  - `POST /agents/departments/apply`（`List<Long> deptIds`）→ flash + `redirect:/agents/departments`
  - `POST /agents/departments/disable` → 清空白名单 + `redirect:/agents/departments`
- `DeptOption`：`record DeptOption(Long id, String name, int depth, int descendantCount)`

- [ ] **Step 1: 写失败测试**

控制器单测：

```java
@DisplayName("部门准入控制器")
class DepartmentWhitelistControllerTest {

    private MockMvc mockMvc;
    private final DepartmentWhitelistApplyService applyService = mock(DepartmentWhitelistApplyService.class);

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(
            mock(GlobalAgentPoolRepository.class),
            mock(GlobalAgentPoolService.class),
            mock(QrAgentRepository.class),
            mock(QrCodeRepository.class),
            mock(QrCodeService.class),
            mock(EmployeeSyncService.class),
            mock(EmployeeRepository.class),
            mock(AgentRepository.class),
            mock(OperationLogService.class),
            mock(DepartmentWhitelistService.class),
            applyService,
            mock(DepartmentService.class),
            mock(PoolAllowedDepartmentRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST preview 返回 JSON")
    void previewReturnsJson() throws Exception {
        when(applyService.preview(List.of(100L))).thenReturn(
            new DepartmentWhitelistPreview(true, List.of(), List.of(), List.of(), 0, List.of()));

        mockMvc.perform(post("/agents/departments/preview").param("deptIds", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST apply 重定向回配置页")
    void applyRedirects() throws Exception {
        when(applyService.apply(eq(List.of(100L)), anyString())).thenReturn(
            new DepartmentWhitelistApplyResult(1, 0, 0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(post("/agents/departments/apply").param("deptIds", "100"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/agents/departments"));
    }
}
```

页面渲染测试：

```java
@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
@DisplayName("部门准入页渲染")
class DepartmentWhitelistPageRenderTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private GlobalAgentPoolRepository poolRepo;
    @MockBean private GlobalAgentPoolService poolService;
    @MockBean private QrAgentRepository qrAgentRepo;
    @MockBean private QrCodeRepository qrCodeRepo;
    @MockBean private QrCodeService qrCodeService;
    @MockBean private EmployeeSyncService employeeSyncService;
    @MockBean private EmployeeRepository employeeRepo;
    @MockBean private AgentRepository agentRepo;
    @MockBean private OperationLogService operationLogService;
    @MockBean private DepartmentWhitelistService whitelistService;
    @MockBean private DepartmentWhitelistApplyService applyService;
    @MockBean private DepartmentService departmentService;
    @MockBean private PoolAllowedDepartmentRepository whitelistRepo;
    @MockBean(name = "rateLimitRedisTemplate") private StringRedisTemplate rateLimitRedisTemplate;
    @MockBean private LoginSuccessHandler loginSuccessHandler;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /agents/departments 正常渲染")
    void rendersPage() throws Exception {
        when(departmentService.loadDeptTree()).thenReturn(Map.of(
            100L, new DepartmentService.DeptNode(100, "校区", 0L)));
        when(whitelistRepo.findAllByOrderByDeptIdAsc()).thenReturn(List.of());

        mockMvc.perform(get("/agents/departments"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("部门准入")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=DepartmentWhitelistControllerTest,DepartmentWhitelistPageRenderTest test`
Expected: 编译失败，端点/模板不存在。

- [ ] **Step 3: 实现控制器**

`AgentController` 新增依赖（加在现有字段末尾，便于更新手动构造的测试）：

```java
private final DepartmentWhitelistService whitelistService;
private final DepartmentWhitelistApplyService whitelistApplyService;
private final DepartmentService departmentService;
private final PoolAllowedDepartmentRepository whitelistRepo;
```

`GET /agents/departments`：

- 读白名单 `whitelistRepo.findAllByOrderByDeptIdAsc()`；
- 读部门树 `departmentService.loadDeptTree()`，异常时 `error` flash + 空列表；
- 扁平化为 `List<DeptOption>`：按 `parentId` 递归输出（根 = `parentId` 为 null 或不在树中的节点），`depth` 从 0 递增；`descendantCount` 递归统计；
- `driftedReceptionistCount = whitelistApplyService.countDriftedActiveReceptionists()`；
- 返回 `agent/departments`。

`POST /agents/departments/preview`：`@ResponseBody`，`@RequestParam(name="deptIds", required=false) List<Long> deptIds`，调用 `preview`；捕获 `DeptTreeUnavailableException` → 返回 `ResponseEntity.status(409).body(Map.of("error", "部门树拉取失败，请稍后重试"))`。

`POST /agents/departments/apply`：同样的参数；操作人取 `SecurityContextHolder`，沿用 `restoreAgent` 的写法 `authentication != null ? authentication.getName() : "system"`（直接解引用在无认证的测试环境会 NPE）；调用 `apply`；成功 flash `"部门准入已应用：移出池 X 人，撤下接待员 Y 人，补位 Z 人"`；失败 flash error；重定向 `/agents/departments`。

`POST /agents/departments/disable`：`whitelistService.clearWhitelist()` + 审计日志 + flash + 重定向。

- [ ] **Step 4: 实现模板**

`agent/list.html`：在「全量视图」按钮旁加：

```html
<a th:href="@{/agents/departments}" class="btn btn-outline-primary">
    🏢 部门准入
</a>
```

`agent/departments.html`（要点）：

- 使用 `layout` 布局，标题「员工管理」；
- 隐藏字段：
```html
<input type="hidden" id="csrfToken" th:value="${_csrf.token}">
<input type="hidden" id="csrfHeader" th:value="${_csrf.headerName}">
```
- 部门树多选：遍历 `deptOptions`，`<input type="checkbox" name="deptIds" th:value="${opt.id}" th:checked="${selectedIds.contains(opt.id)}">` + 缩进 `opt.depth` + 名称 + `含 N 个子部门`；
- 「预览影响」按钮：`fetch('/agents/departments/preview', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded', [csrfHeader]: csrfToken}, body: formData })`，把返回的 `poolRemovals`、`qrRemovals`、`noDepartmentEmployees`、`warnings` 渲染到结果区；
- 「确认应用」表单：`th:action="@{/agents/departments/apply}" method="post"` + `onsubmit="return confirm('确认应用部门准入并执行清理？')"`；
- 「清空白名单并停用」按钮：独立表单 + 二次确认；
- 顶部提示：`driftedReceptionistCount > 0` 时展示「仍有 N 名无关部门接待员在活码上，建议重新预览并应用」；
- 预览结果区明示「预览与执行可能有偏差，以确认执行时为准」。

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw -q -Dtest=DepartmentWhitelistControllerTest,DepartmentWhitelistPageRenderTest,AgentControllerTest test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bookstore/qrcode/dto/DeptOption.java \
        src/main/java/com/bookstore/qrcode/controller/AgentController.java \
        src/main/resources/templates/agent/list.html \
        src/main/resources/templates/agent/departments.html \
        src/test/java/com/bookstore/qrcode/controller/DepartmentWhitelistControllerTest.java \
        src/test/java/com/bookstore/qrcode/controller/DepartmentWhitelistPageRenderTest.java \
        src/test/java/com/bookstore/qrcode/controller/AgentControllerTest.java
git commit -m "feat: add department whitelist admin page with preview and apply"
```

---

### Task 11: 全链路验收

**Files:**
- Test: `src/test/java/com/bookstore/qrcode/worker/DepartmentWhitelistEndToEndTest.java`（必须放在 `com.bookstore.qrcode.worker` 包：`DailyResetWorker.recoverFullAgents()` 是包级可见，`integration` 包调不到）

**Interfaces:**
- Consumes: Task 3–9 的全部服务
- Produces: 一条覆盖「配置 → 预览 → 应用 → 日常维护」的端到端回归

- [ ] **Step 1: 写测试**

```java
package com.bookstore.qrcode.worker;

@Import(WecomApiMockConfig.class)
@DisplayName("部门准入：全链路")
class DepartmentWhitelistEndToEndTest extends BaseIntegrationTest {

    @Autowired private DepartmentWhitelistApplyService applyService;
    @Autowired private EmployeeSyncService employeeSyncService;
    @Autowired private DepartmentWhitelistService whitelistService;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private DailyResetWorker dailyResetWorker;

    @BeforeEach
    void setUp() {
        qrAgentRepo.deleteAll();
        poolRepo.deleteAll();
        qrCodeRepo.deleteAll();
        agentRepo.deleteAll();
        employeeRepo.deleteAll();
        whitelistRepo.deleteAll();
    }

    @Test
    @DisplayName("配置白名单 → 清理 → 日常同步只补允许员工 → 次日不复活无关接待员")
    void endToEnd() {
        // 1. 种子：允许部门(100，含子部门101)、无关部门(200)
        employeeRepo.saveAll(List.of(
            Employee.builder().userid("allowed").name("allowed").department("[101]")
                .active(true).wechatStatus(1).build(),
            Employee.builder().userid("other").name("other").department("[200]")
                .active(true).wechatStatus(1).build()));
        agentRepo.saveAll(List.of(
            Agent.builder().userid("allowed").name("allowed")
                .role(Agent.AgentRole.receptionist).overallStatus(Agent.OverallStatus.normal).build(),
            Agent.builder().userid("other").name("other")
                .role(Agent.AgentRole.receptionist).overallStatus(Agent.OverallStatus.normal).build()));
        poolRepo.saveAll(List.of(
            GlobalAgentPool.builder().agentUserid("allowed").departmentId(101L)
                .status(GlobalAgentPool.PoolStatus.standby).build(),
            GlobalAgentPool.builder().agentUserid("other").departmentId(200L)
                .status(GlobalAgentPool.PoolStatus.standby).build()));
        QrCode qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("全链路").schoolId("SCH-E2E-1")
            .regionCity("深圳").regionDistrict("南山区").build());
        qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId()).agentUserid("other")
            .role(QrAgent.AgentRole.receptionist).dailyMax(150).sortOrder(0)
            .status(QrAgent.AgentStatus.active).build());

        // 2. 应用白名单：子部门 101 命中，other 被移出池并撤码，allowed 补位
        var result = applyService.apply(List.of(100L), "tester");
        assertThat(result.poolRemoved()).isEqualTo(1);
        assertThat(result.activeReceptionistsRemoved()).isEqualTo(1);
        assertThat(qrAgentRepo.findByQrCodeIdAndStatus(qr.getId(), QrAgent.AgentStatus.active))
            .extracting(QrAgent::getAgentUserid).containsExactly("allowed");

        // 3. 日常同步不再把 other 加回池
        employeeSyncService.syncToGlobalPool();
        assertThat(poolRepo.findByAgentUserid("other")).isEmpty();

        // 4. 次日重置：即使 other 被手工恢复成 full，也不会复活
        QrAgent other = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), "other").orElseThrow();
        other.setStatus(QrAgent.AgentStatus.full);
        qrAgentRepo.save(other);
        dailyResetWorker.recoverFullAgents();
        assertThat(qrAgentRepo.findById(other.getId()).orElseThrow().getStatus())
            .isEqualTo(QrAgent.AgentStatus.removed);
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `./mvnw -q -Dtest=DepartmentWhitelistEndToEndTest test`
Expected: 首次运行可能暴露 Task 3–9 的接口不一致；按错误修正后必须 PASS。

- [ ] **Step 3: 运行相关测试全集**

Run: `./mvnw -q -Dtest='DepartmentWhitelist*,GlobalAgentPoolServiceTest,AgentControllerTest,PatrolWorkerTest,DailyResetWorkerReleaseTest,QrCodeCreationFlowTest,AgentRotationFlowTest' test`
Expected: 全部 PASS（无既有回归）。

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/bookstore/qrcode/worker/DepartmentWhitelistEndToEndTest.java
git commit -m "test: add department whitelist end-to-end coverage"
```

---

## 上线核对清单（非编码步骤）

- [ ] `schema.sql` 已在生产启动时执行，`pool_allowed_department` 表存在；空表期间行为与上线前一致。
- [ ] 后台「员工管理 → 部门准入」可打开，部门树显示父子层级与子部门计数。
- [ ] 先「预览影响」，核对：池移除名单、活码撤人名单、无部门员工、补位可行性；再「确认应用」。
- [ ] 应用后抽查：无关部门接待员已从活码撤下、活码仍有 active 成员、企微侧联系我成员已同步。
- [ ] 次日 00:00 后抽查：无关接待员未复活。
- [ ] 双节点：在另一台节点触发一次同步，确认白名单立即生效（白名单以 DB 为准）。
