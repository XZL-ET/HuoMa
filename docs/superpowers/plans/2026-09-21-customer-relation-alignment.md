# customer_relation 关系表对齐企微 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `customer_relation` 表镜像企微 `follow_user`，解决多归属/多校/统计口径/继承四类数据丢失问题，且不回归现有 `customer` 单值逻辑。

**Architecture:** 新增一张「客户 × 员工」关系表作为真相源，通过增量回调（add/delete/transfer）精确写入 + 全量同步（逐员工拉客户列表）兜底对账。继承改造为「从关系表定位、按 `(customer_id, qr_code_id)` 去重」。`customer` 表字段与现有逻辑一律不动。

**Tech Stack:** Spring Boot 3 / Spring Data JPA（无关系注解，显式 FK 字段）/ Redis Stream / MySQL 8 / H2（测试）/ JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-21-customer-relation-alignment-design.md`（计划据此展开，执行时两者一起读）。

## Global Constraints

- **不改 `customer` 表字段、不删任何现有字段**（§2 硬约束）。
- **无 JPA 关系注解**：实体用显式 `Long customerId` / `String employeeUserid`，不用 `@ManyToOne`；跨表查询用 JPQL 手动 JOIN（CLAUDE.md）。
- **`state` = 学校 ID（schoolId）**，`school↔QR` 是 1:1（`qr_code.school_id` UNIQUE）。`findBySchoolId(state)` 反查活码主键 `qr.getId()`。
- **去重维度从 `customer_id` 改为 `(customer_id, qr_code_id)`**，且 `qr_code_id IS NULL` 的历史行降级为仅按 `customer_id` 判定（`t.qrCodeId = :qrCodeId OR t.qrCodeId IS NULL`）。
- **来源字段（qr_code_id/school_id/add_time）仅在为 null 时近似回填**（COALESCE 语义），绝不覆盖增量回调的精确值。
- **全量同步 removed 对账只针对 `updated_at < T0` 的稳定行**，`updated_at >= T0` 的行一律不动。
- **企微 API 单员工调用失败必须整体跳过该员工**（不能把「调用失败」当「空列表」）。
- 枚举一律 `@Enumerated(EnumType.STRING)`，列名 snake_case，表 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。

---

## File Structure

| 文件 | 责任 |
|---|---|
| `src/main/java/com/bookstore/qrcode/entity/CustomerRelation.java` | **新建**：关系表实体，含 `RelationStatus{active,removed}` 枚举 |
| `src/main/java/com/bookstore/qrcode/repository/CustomerRelationRepository.java` | **新建**：关系表仓库（按 customer+employee 查、按 employee 查、继承定位查询） |
| `src/main/java/com/bookstore/qrcode/service/CustomerRelationService.java` | **新建**：关系写入的单一入口（upsert active / mark removed / transfer 确认），封装 COALESCE 语义 |
| `src/main/java/com/bookstore/qrcode/service/CustomerSyncService.java` | **新建**：全量同步（逐员工拉客户列表 + removed 对账 + 稀疏 customer 补建） |
| `src/main/java/com/bookstore/qrcode/service/CustomerService.java` | 修改 `upsertFromCallback`（挂关系 upsert）+ `handleDelete`（读 userid 置 removed） |
| `src/main/java/com/bookstore/qrcode/service/TransferService.java` | 修改 `initiate`（顺序重排 + 去重带 qrCodeId）+ `retryFailedTransfers`（锁/去重带 qrCodeId）+ `updateCustomerCurrentAgent`（转移确认维护关系） |
| `src/main/java/com/bookstore/qrcode/job/InheritanceJob.java` | 修改定位查询调用 + Redis 入队去重键 |
| `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java` | 修改 `del_follow_user` 分支补置 removed |
| `src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java` | 替换 `findWithoutTransferByAgentAndSchoolIdAndAddTimeBetween` 为 `findForInheritance` |
| `src/main/java/com/bookstore/qrcode/repository/CustomerTransferRepository.java` | 去重方法加 `qrCodeId` 维度（含 null 降级） |
| `src/main/resources/schema.sql` / `src/test/resources/schema-test.sql` | 加 `customer_relation` DDL + 索引 |

---

## Task 1: `customer_relation` 表 — DDL + 实体 + 仓库

**Files:**
- Modify: `src/main/resources/schema.sql`（末尾追加）
- Modify: `src/test/resources/schema-test.sql`（末尾追加）
- Create: `src/main/java/com/bookstore/qrcode/entity/CustomerRelation.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/CustomerRelationRepository.java`
- Test: `src/test/java/com/bookstore/qrcode/repository/CustomerRelationRepositoryTest.java`

**Interfaces:**
- Produces: `CustomerRelation` 实体（`getCustomerId()`、`getEmployeeUserid()`、`getQrCodeId()`、`getSchoolId()`、`getAddTime()`、`getStatus()`、`RelationStatus`）；`CustomerRelationRepository` 方法：
  - `Optional<CustomerRelation> findByCustomerIdAndEmployeeUserid(Long, String)`
  - `List<CustomerRelation> findByEmployeeUseridAndStatus(String, RelationStatus)`
  - `List<CustomerRelation> findByCustomerIdAndStatus(Long, RelationStatus)`
  - `List<CustomerRelation> findByCustomerId(Long)`
  - `List<Customer> findForInheritance(String employeeUserid, Long qrCodeId, LocalDateTime start, LocalDateTime end)`

- [ ] **Step 1: 写失败的测试 — 仓库唯一键 + 继承定位查询**

`src/test/java/com/bookstore/qrcode/repository/CustomerRelationRepositoryTest.java`：

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerRelationRepository 关系表查询")
class CustomerRelationRepositoryTest {

    @Autowired private CustomerRelationRepository relationRepo;
    @Autowired private TestEntityManager em;

    private Customer c1, c2;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now().withNano(0);
        c1 = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-c1").name("客户1").type(1)
                .addedAgent("recA").currentAgent("recA")
                .sourceQrId(1L).schoolId("SCHOOL-1")
                .status(Customer.CustomerStatus.active).addTime(now.minusMinutes(30)).build());
        c2 = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-c2").name("客户2").type(1)
                .addedAgent("recA").currentAgent("recA")
                .sourceQrId(2L).schoolId("SCHOOL-2")
                .status(Customer.CustomerStatus.active).addTime(now.minusMinutes(20)).build());
        // c1 有两个员工归属；c2 一个
        relationRepo.save(CustomerRelation.builder().customerId(c1.getId()).employeeUserid("recA")
                .qrCodeId(1L).schoolId("SCHOOL-1").addTime(now.minusMinutes(30)).status(RelationStatus.active).build());
        relationRepo.save(CustomerRelation.builder().customerId(c1.getId()).employeeUserid("svcA")
                .qrCodeId(1L).schoolId("SCHOOL-1").addTime(now.minusMinutes(29)).status(RelationStatus.active).build());
        relationRepo.save(CustomerRelation.builder().customerId(c2.getId()).employeeUserid("recA")
                .qrCodeId(2L).schoolId("SCHOOL-2").addTime(now.minusMinutes(20)).status(RelationStatus.active).build());
    }

    @Test
    @DisplayName("findByCustomerIdAndEmployeeUserid — 按 (customer, employee) 精确定位")
    void findByCustomerIdAndEmployeeUserid() {
        assertThat(relationRepo.findByCustomerIdAndEmployeeUserid(c1.getId(), "svcA")).isPresent();
        assertThat(relationRepo.findByCustomerIdAndEmployeeUserid(c1.getId(), "nobody")).isEmpty();
    }

    @Test
    @DisplayName("findByCustomerIdAndStatus — 多归属客户有多行")
    void multiOwnershipYieldsMultipleRows() {
        List<CustomerRelation> rows = relationRepo.findByCustomerIdAndStatus(c1.getId(), RelationStatus.active);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CustomerRelation::getEmployeeUserid)
                .containsExactlyInAnyOrder("recA", "svcA");
    }

    @Test
    @DisplayName("findForInheritance — 按 (接待员, 活码) 定位，多校客户各命中一次")
    void findForInheritanceMultiSchool() {
        // recA 在 SCHOOL-1（qrCodeId=1）下：只命中 c1
        List<Customer> q1 = relationRepo.findForInheritance("recA", 1L,
                now.minusHours(1), now);
        assertThat(q1).extracting(Customer::getExternalUserid).containsExactly("wm-c1");

        // recA 在 SCHOOL-2（qrCodeId=2）下：只命中 c2
        List<Customer> q2 = relationRepo.findForInheritance("recA", 2L,
                now.minusHours(1), now);
        assertThat(q2).extracting(Customer::getExternalUserid).containsExactly("wm-c2");

        // svcA 不是接待员维度（不在本查询范围内）：按 svcA 查询在 qrCodeId=1 命中 c1
        List<Customer> q3 = relationRepo.findForInheritance("svcA", 1L,
                now.minusHours(1), now);
        assertThat(q3).extracting(Customer::getExternalUserid).containsExactly("wm-c1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerRelationRepositoryTest test`
Expected: 编译失败（`CustomerRelation`、`CustomerRelationRepository` 不存在）。

- [ ] **Step 3: 写 DDL（生产 + 测试）**

`src/main/resources/schema.sql` 末尾追加：

```sql
-- ============================================
-- customer_relation：客户-员工关系表（镜像企微 follow_user）
-- ============================================
CREATE TABLE IF NOT EXISTS customer_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL COMMENT '客户ID（customer.id）',
    employee_userid VARCHAR(100) NOT NULL COMMENT '添加该客户的员工userid（= follow_user 元素）',
    qr_code_id BIGINT COMMENT '来源活码ID（增量回调精确；全量同步从 customer 近似）',
    school_id VARCHAR(50) COMMENT '来源学校ID（增量回调精确；全量同步从 customer 近似）',
    add_time DATETIME COMMENT '该员工添加该客户的时间',
    status ENUM('active','removed') NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_employee (customer_id, employee_userid),
    INDEX idx_employee (employee_userid),
    INDEX idx_qr (qr_code_id),
    INDEX idx_school (school_id),
    INDEX idx_status (status),
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户-员工关系表（镜像企微follow_user）';
```

`src/test/resources/schema-test.sql` 末尾追加（H2 语法）：

```sql
-- customer_relation：客户-员工关系表
CREATE TABLE IF NOT EXISTS customer_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    employee_userid VARCHAR(100) NOT NULL,
    qr_code_id BIGINT,
    school_id VARCHAR(50),
    add_time TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','removed')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_customer_employee UNIQUE (customer_id, employee_userid),
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cr_employee ON customer_relation (employee_userid);
CREATE INDEX IF NOT EXISTS idx_cr_qr ON customer_relation (qr_code_id);
CREATE INDEX IF NOT EXISTS idx_cr_school ON customer_relation (school_id);
CREATE INDEX IF NOT EXISTS idx_cr_status ON customer_relation (status);
```

- [ ] **Step 4: 写实体**

`src/main/java/com/bookstore/qrcode/entity/CustomerRelation.java`：

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_relation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "employee_userid", nullable = false, length = 100)
    private String employeeUserid;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "school_id", length = 50)
    private String schoolId;

    @Column(name = "add_time")
    private LocalDateTime addTime;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RelationStatus status = RelationStatus.active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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

    public enum RelationStatus {
        active, removed
    }
}
```

- [ ] **Step 5: 写仓库**

`src/main/java/com/bookstore/qrcode/repository/CustomerRelationRepository.java`：

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRelationRepository extends JpaRepository<CustomerRelation, Long> {

    Optional<CustomerRelation> findByCustomerIdAndEmployeeUserid(Long customerId, String employeeUserid);

    List<CustomerRelation> findByEmployeeUseridAndStatus(String employeeUserid, RelationStatus status);

    List<CustomerRelation> findByCustomerIdAndStatus(Long customerId, RelationStatus status);

    List<CustomerRelation> findByCustomerId(Long customerId);

    /**
     * 在职继承定位：按 (接待员, 活码) 查窗口内、status=active、且无「该活码下进行中/已完成」转移记录的客户。
     * 多校客户各自命中其扫码的活码；NOT EXISTS 按 (customer_id, qr_code_id) 排除，
     * qr_code_id IS NULL 的历史转移记录降级为仅按 customer_id 判定（保守防重复转移）。
     */
    @Query("SELECT c FROM Customer c, CustomerRelation cr "
        + "WHERE cr.customerId = c.id "
        + "AND cr.employeeUserid = :employeeUserid "
        + "AND cr.qrCodeId = :qrCodeId "
        + "AND cr.status = 'active' "
        + "AND cr.addTime >= :start AND cr.addTime < :end "
        + "AND NOT EXISTS (SELECT t FROM CustomerTransfer t WHERE t.customerId = c.id "
        + "AND (t.qrCodeId = :qrCodeId OR t.qrCodeId IS NULL) "
        + "AND t.status IN ('pending_confirm', 'confirmed', 'api_failed')) "
        + "ORDER BY cr.addTime ASC")
    List<Customer> findForInheritance(@Param("employeeUserid") String employeeUserid,
                                      @Param("qrCodeId") Long qrCodeId,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q -Dtest=CustomerRelationRepositoryTest test`
Expected: PASS（3 个测试全绿）。

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/schema.sql src/test/resources/schema-test.sql \
  src/main/java/com/bookstore/qrcode/entity/CustomerRelation.java \
  src/main/java/com/bookstore/qrcode/repository/CustomerRelationRepository.java \
  src/test/java/com/bookstore/qrcode/repository/CustomerRelationRepositoryTest.java
git commit -m "feat: customer_relation 关系表 DDL/实体/仓库 + 继承定位查询"
```

---

## Task 2: 关系写入服务 + `add_external_contact` 增量 upsert

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/CustomerRelationService.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerService.java`（`upsertFromCallback` 挂 upsert）
- Test: `src/test/java/com/bookstore/qrcode/service/CustomerRelationServiceTest.java`

**Interfaces:**
- Consumes: `CustomerRelationRepository`（Task 1）、`QrCodeRepository`、`CustomerRepository`。
- Produces: `CustomerRelationService`：
  - `void upsertActive(Long customerId, String employeeUserid, Long qrCodeId, String schoolId, LocalDateTime addTime)` — upsert active，来源字段 COALESCE（仅在现有值 null 时写入）。
  - `void markRemoved(Long customerId, String employeeUserid)` — 置 removed（存在才置，不存在静默跳过）。
  - `void applyTransferConfirmed(Long customerId, String fromUserid, String toUserid, Long qrCodeId, String schoolId, LocalDateTime confirmTime)` — from 置 removed + to upsert active。

- [ ] **Step 1: 写失败的测试 — COALESCE 语义 + markRemoved 幂等**

`src/test/java/com/bookstore/qrcode/service/CustomerRelationServiceTest.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerRelationService 关系写入")
class CustomerRelationServiceTest {

    @Mock private CustomerRelationRepository relationRepo;
    @InjectMocks private CustomerRelationService service;

    @Test
    @DisplayName("upsertActive — 新关系写入来源字段")
    void upsertNewRelationWritesSource() {
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.empty());
        LocalDateTime now = LocalDateTime.now();

        service.upsertActive(1L, "recA", 10L, "SCHOOL-1", now);

        verify(relationRepo).save(argThat(r -> r.getQrCodeId() == 10L
                && "SCHOOL-1".equals(r.getSchoolId())
                && r.getAddTime().equals(now)
                && r.getStatus() == RelationStatus.active));
    }

    @Test
    @DisplayName("upsertActive — 已有精确来源时不覆盖（COALESCE 语义）")
    void upsertExistingKeepsPreciseSource() {
        CustomerRelation existing = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("recA")
                .qrCodeId(10L).schoolId("SCHOOL-1").addTime(LocalDateTime.now())
                .status(RelationStatus.active).build();
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.of(existing));

        service.upsertActive(1L, "recA", null, null, null); // 全量同步近似值，全 null

        assertThat(existing.getQrCodeId()).isEqualTo(10L);   // 未被 null 覆盖
        assertThat(existing.getSchoolId()).isEqualTo("SCHOOL-1");
        verify(relationRepo).save(existing);
    }

    @Test
    @DisplayName("markRemoved — 存在才置 removed")
    void markRemovedExisting() {
        CustomerRelation existing = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("recA").status(RelationStatus.active).build();
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.of(existing));

        service.markRemoved(1L, "recA");

        assertThat(existing.getStatus()).isEqualTo(RelationStatus.removed);
        verify(relationRepo).save(existing);
    }

    @Test
    @DisplayName("markRemoved — 不存在时静默跳过（不抛异常）")
    void markRemovedMissingSilentlySkips() {
        when(relationRepo.findByCustomerIdAndEmployeeUserid(1L, "recA")).thenReturn(Optional.empty());

        service.markRemoved(1L, "recA");

        verify(relationRepo, never()).save(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerRelationServiceTest test`
Expected: 编译失败（`CustomerRelationService` 不存在）。

- [ ] **Step 3: 实现 `CustomerRelationService`**

`src/main/java/com/bookstore/qrcode/service/CustomerRelationService.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRelationService {

    private final CustomerRelationRepository relationRepo;

    /**
     * upsert active 关系。来源字段（qr_code_id/school_id/add_time）仅在现有值为 null 时写入，
     * 绝不覆盖增量回调已写的精确值（COALESCE 语义，spec §4.4 铁律 1）。
     */
    @Transactional
    public void upsertActive(Long customerId, String employeeUserid,
                             Long qrCodeId, String schoolId, LocalDateTime addTime) {
        CustomerRelation rel = relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
            .orElseGet(() -> CustomerRelation.builder()
                .customerId(customerId).employeeUserid(employeeUserid)
                .status(RelationStatus.active).build());
        rel.setStatus(RelationStatus.active);
        if (rel.getQrCodeId() == null && qrCodeId != null) rel.setQrCodeId(qrCodeId);
        if (rel.getSchoolId() == null && schoolId != null) rel.setSchoolId(schoolId);
        if (rel.getAddTime() == null && addTime != null) rel.setAddTime(addTime);
        relationRepo.save(rel);
    }

    /** 置 removed。关系不存在时静默跳过（防御：回调丢失的客户从未入过关系表）。 */
    @Transactional
    public void markRemoved(Long customerId, String employeeUserid) {
        relationRepo.findByCustomerIdAndEmployeeUserid(customerId, employeeUserid)
            .ifPresent(rel -> {
                rel.setStatus(RelationStatus.removed);
                relationRepo.save(rel);
            });
    }

    /** 转移确认：from 置 removed + to upsert active（follow_user 里 from 被 to 替换）。 */
    @Transactional
    public void applyTransferConfirmed(Long customerId, String fromUserid, String toUserid,
                                       Long qrCodeId, String schoolId, LocalDateTime confirmTime) {
        markRemoved(customerId, fromUserid);
        upsertActive(customerId, toUserid, qrCodeId, schoolId, confirmTime);
    }
}
```

- [ ] **Step 4: 在 `upsertFromCallback` 挂关系 upsert**

`src/main/java/com/bookstore/qrcode/service/CustomerService.java` — 注入 `CustomerRelationService`，并在 `upsertFromCallback` 返回 `customer.getId()` 前、`state` 非空且能反查到活码时 upsert 关系。在 `upsertFromCallback` 顶部（`findBySchoolId` 解析后、写 customer 前）调用：

```java
// 在 upsertFromCallback 内，customer 持久化后（新客户 return 前 + 老客户 return 前两处）统一挂关系写。
// 关系写与 customer 写同一事务，customer.getId() 已确定。
if (qrCodeId != null && userId != null) {
    customerRelationService.upsertActive(customer.getId(), userId, qrCodeId, schoolId, LocalDateTime.now());
}
```

> 实现要点：老客户分支（existing != null）与并发重试分支（retry != null）以及新客户分支（customer 保存后）三处都要挂；`state` 为空（内部转移）时 `qrCodeId == null`，不写关系（由 transfer confirmed 钩子负责，见 Task 4）。

- [ ] **Step 5: 运行两个测试类确认通过**

Run: `mvn -q -Dtest=CustomerRelationServiceTest,CustomerServiceTest test`
Expected: PASS（新 4 个测试 + 既有 CustomerServiceTest 不回归）。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/CustomerRelationService.java \
  src/main/java/com/bookstore/qrcode/service/CustomerService.java \
  src/test/java/com/bookstore/qrcode/service/CustomerRelationServiceTest.java
git commit -m "feat: 关系写入服务 + add_external_contact 增量 upsert"
```

---

## Task 3: 删除事件维护关系 — `handleDelete` 读 userid + `del_follow_user` 补置 removed

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/CustomerService.java`（`handleDelete` 读 `event.userid` 调 markRemoved）
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java`（`del_follow_user` 分支补 markRemoved）
- Test: `src/test/java/com/bookstore/qrcode/service/CustomerServiceTest.java`（追加 handleDelete 关系断言）、`src/test/java/com/bookstore/qrcode/worker/` 新增或复用

**Interfaces:**
- Consumes: `CustomerRelationService.markRemoved`（Task 2）。

- [ ] **Step 1: 写失败的测试 — handleDelete 置 removed**

在 `src/test/java/com/bookstore/qrcode/service/CustomerServiceTest.java` 追加（并在 `@InjectMocks` 的 CustomerService 构造注入处补 `@Mock CustomerRelationService customerRelationService`）：

```java
@Test
@DisplayName("handleDelete — 删除事件同时将对应员工关系置 removed")
void shouldMarkRelationRemovedOnDelete() throws Exception {
    Customer customer = Customer.builder()
            .id(1L).externalUserid("wm-abc123")
            .currentAgent("agent1").status(Customer.CustomerStatus.active).build();
    JsonNode detail = objectMapper.readTree("""
        {"errcode":0,"follow_user":[{"userid":"agent2"}]}
        """);
    when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
    when(wecomApiClient.getExternalContact("wm-abc123")).thenReturn(detail);

    JsonNode event = objectMapper.readTree("""
        {"external_userid":"wm-abc123","userid":"agent1"}
        """);

    customerService.handleDelete(event);

    verify(customerRelationService).markRemoved(1L, "agent1");
}
```

> 注意：`CustomerService` 当前构造器用 `@RequiredArgsConstructor`，新增 `CustomerRelationService` 字段后 Mockito `@InjectMocks` 会按类型注入，需在测试类加 `@Mock private CustomerRelationService customerRelationService;`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerServiceTest test`
Expected: 新测试 FAIL（`verify` 失败，markRemoved 从未被调用）。

- [ ] **Step 3: 修改 `handleDelete`**

`src/main/java/com/bookstore/qrcode/service/CustomerService.java` — 在 `handleDelete` 中 `customer` 非空后、调用 getExternalContact 前，先读 `userid` 并标记关系：

```java
// 在 customer == null return 之后、getExternalContact 之前插入：
String userId = event.has("userid") && !event.get("userid").isNull()
    ? event.get("userid").asText() : null;
// 员工删客户/客户删员工：该员工与客户的关系解除，关系表置 removed。
// 先查 customer 存在（已保证），userId 为空则跳过（全量同步兜底），不打断 customer 级删除判定。
if (userId != null) {
    customerRelationService.markRemoved(customer.getId(), userId);
}
```

> 放在 `getExternalContact` 之前：关系写用独立 `@Transactional`（`markRemoved` 自身事务），即使后续 `getExternalContact` 抛瞬时异常回滚重试，`markRemoved` 已提交的关系写会在重试时再次幂等置 removed，无副作用。若想严格同事务，可移到 getExternalContact 成功后；但「关系解除」是事实，早置 removed 更简单且幂等。

- [ ] **Step 4: 修改 `CallbackWorker.del_follow_user` 分支**

`src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java` — `handleExternalContactEvent` 的 `del_follow_user` 分支（现仅 `deletionService.recordCustomerDeletedAgent(event)`）补关系置 removed：

```java
} else if ("del_follow_user".equals(changeType)) {
    deletionService.recordCustomerDeletedAgent(event);
    // 客户删员工：该员工与客户的关系解除，关系表置 removed。
    // 独立 try-catch，失败不回滚日报记录，也不阻断消费主链路。
    try {
        String externalUserId = event.has("external_userid") ? event.get("external_userid").asText() : null;
        String userId = event.has("userid") ? event.get("userid").asText() : null;
        if (externalUserId != null && userId != null) {
            customerRepo.findByExternalUserid(externalUserId)
                .ifPresent(c -> customerRelationService.markRemoved(c.getId(), userId));
        }
    } catch (Exception e) {
        log.warn("del_follow_user 关系置 removed 失败（不影响日报）: {}", e.getMessage());
    }
}
```

> `CallbackWorker` 需注入 `CustomerRelationService`（现应已注入 `CustomerService`；新增字段即可）。用 `customerRepo.findByExternalUserid` 先查 customer 存在，避免 FK 撞（客户从未入过库的 ~655 个回调丢失场景）。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -Dtest=CustomerServiceTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/CustomerService.java \
  src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java \
  src/test/java/com/bookstore/qrcode/service/CustomerServiceTest.java
git commit -m "feat: 删除事件维护关系 — handleDelete 读 userid + del_follow_user 置 removed"
```

---

## Task 4: 转移确认维护关系 — `updateCustomerCurrentAgent` 挂 applyTransferConfirmed

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java`（`updateCustomerCurrentAgent` 挂关系维护）
- Test: `src/test/java/com/bookstore/qrcode/service/TransferServiceTest.java`（如不存在则新建，或复用既有 TransferService 测试）

**Interfaces:**
- Consumes: `CustomerRelationService.applyTransferConfirmed`（Task 2）、`QrCodeRepository.findById`。

- [ ] **Step 1: 写失败的测试**

新建 `src/test/java/com/bookstore/qrcode/service/TransferServiceRelationTest.java`（聚焦关系维护，隔离其它 TransferService 依赖）：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.CustomerTransfer.TransferStatus;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService 转移确认关系维护")
class TransferServiceRelationTest {

    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private CustomerRelationService customerRelationService;
    @InjectMocks private TransferService transferService;

    @Test
    @DisplayName("转移确认后 from 置 removed + to upsert active（带来源）")
    void confirmedTransferMaintainsRelation() {
        Customer c = Customer.builder().id(1L).externalUserid("wm-1").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(c));
        QrCode qr = new QrCode();
        qr.setId(10L);
        qr.setSchoolId("SCHOOL-1");
        when(qrCodeRepo.findById(10L)).thenReturn(Optional.of(qr));

        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(10L).status(TransferStatus.confirmed).build();

        transferService.applyTransferConfirmedRelations(t);

        verify(customerRelationService).applyTransferConfirmed(
                eq(1L), eq("recA"), eq("svcA"), eq(10L), eq("SCHOOL-1"), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=TransferServiceRelationTest test`
Expected: 编译失败（`applyTransferConfirmedRelations` 方法不存在）。

- [ ] **Step 3: 实现并挂接**

`src/main/java/com/bookstore/qrcode/service/TransferService.java` — 注入 `CustomerRelationService customerRelationService`；在 `updateCustomerCurrentAgent` 内，`current_agent` 更新后追加关系维护（best-effort，失败由全量同步兜底）：

```java
// updateCustomerCurrentAgent 内，c.setCurrentAgent + save 之后追加：
try {
    applyTransferConfirmedRelations(t);
} catch (Exception e) {
    log.warn("转移确认关系维护失败（由全量同步兜底）: transferId={}, err={}",
        t.getId(), e.getMessage());
}
```

新增私有方法：

```java
/**
 * 转移确认后的关系维护：from 置 removed + to upsert active。
 * 来源：qr_code_id 取自 transfer 记录，school_id 经活码反查；add_time = 确认时间。
 * best-effort（非事务）：失败由 CustomerSyncService 全量同步对账兜底。
 */
void applyTransferConfirmedRelations(CustomerTransfer t) {
    Long qrCodeId = t.getQrCodeId();
    String schoolId = qrCodeId != null
        ? qrCodeRepo.findById(qrCodeId).map(QrCode::getSchoolId).orElse(null)
        : null;
    customerRelationService.applyTransferConfirmed(
        t.getCustomerId(), t.getFromUserid(), t.getToUserid(),
        qrCodeId, schoolId, LocalDateTime.now());
}
```

> 包私有（无 public）便于测试同包调用；生产由 `updateCustomerCurrentAgent` 调。`QrCode` 需 import（已 import）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=TransferServiceRelationTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/test/java/com/bookstore/qrcode/service/TransferServiceRelationTest.java
git commit -m "feat: 转移确认维护关系 — from 置 removed + to upsert active"
```

---

## Task 5: 全量同步 `CustomerSyncService`

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/CustomerSyncService.java`
- Test: `src/test/java/com/bookstore/qrcode/service/CustomerSyncServiceTest.java`

**Interfaces:**
- Consumes: `WecomApiClient.getUserList()` / `getExternalContactList(String)`；`CustomerRelationService.upsertActive` / `markRemoved`；`CustomerRelationRepository.findByEmployeeUseridAndStatus`；`CustomerRepository.findByExternalUseridIn` / `save`。
- Produces: `int syncOnce()` 返回处理的员工数。

- [ ] **Step 1: 写失败的测试**

`src/test/java/com/bookstore/qrcode/service/CustomerSyncServiceTest.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomTransientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerSyncService 全量同步")
class CustomerSyncServiceTest {

    @Mock private WecomApiClient wecomApi;
    @Mock private CustomerRepository customerRepo;
    @Mock private CustomerRelationRepository relationRepo;
    @Mock private CustomerRelationService relationService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CustomerSyncService syncService;

    @Test
    @DisplayName("单员工 API 失败整体跳过该员工，不误标 removed")
    void skipEntireEmployeeOnApiFailure() throws Exception {
        JsonNode userList = objectMapper.readTree("""
            {"userlist":[{"userid":"emp1","name":"甲","status":1}]}
            """);
        when(wecomApi.getUserList()).thenReturn(userList);
        when(wecomApi.getExternalContactList("emp1"))
                .thenThrow(new WecomTransientException(-1, "timeout", "{}"));

        syncService.syncOnce();

        // 绝不把调用失败当空列表：不 upsert、不 markRemoved
        verify(relationService, never()).upsertActive(any(), any(), any(), any(), any());
        verify(relationService, never()).markRemoved(any(), any());
    }

    @Test
    @DisplayName("员工返回空列表 → 其名下遗留 active 关系置 removed")
    void emptyListReconcilesRemoved() throws Exception {
        JsonNode userList = objectMapper.readTree("""
            {"userlist":[{"userid":"emp1","name":"甲","status":1}]}
            """);
        JsonNode emptyList = objectMapper.readTree("""
            {"errcode":0,"external_userid":[]}
            """);
        when(wecomApi.getUserList()).thenReturn(userList);
        when(wecomApi.getExternalContactList("emp1")).thenReturn(emptyList);
        when(customerRepo.findByExternalUseridIn(anyList())).thenReturn(List.of());
        CustomerRelation stale = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("emp1").status(RelationStatus.active).build();
        when(relationRepo.findByEmployeeUseridAndStatus("emp1", RelationStatus.active))
                .thenReturn(List.of(stale));

        syncService.syncOnce();

        verify(relationService).markRemoved(1L, "emp1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerSyncServiceTest test`
Expected: 编译失败（`CustomerSyncService` 不存在）。

- [ ] **Step 3: 实现 `CustomerSyncService`**

`src/main/java/com/bookstore/qrcode/service/CustomerSyncService.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSyncService {

    private final WecomApiClient wecomApi;
    private final CustomerRepository customerRepo;
    private final CustomerRelationRepository relationRepo;
    private final CustomerRelationService relationService;

    /**
     * 全量同步一次：逐在职员工拉客户列表，upsert 关系 + removed 对账。
     * 遵循 spec §4.1/§4.4 铁律：单员工失败整员工跳过；来源字段 COALESCE；removed 只针对 T0 前稳定行。
     *
     * @return 处理的员工数
     */
    public int syncOnce() {
        JsonNode resp = wecomApi.getUserList();
        if (resp == null || !resp.has("userlist") || !resp.get("userlist").isArray()) {
            log.warn("企微员工列表为空或格式异常，跳过同步");
            return 0;
        }
        LocalDateTime t0 = LocalDateTime.now();
        int processed = 0;
        for (JsonNode u : resp.get("userlist")) {
            String userid = u.has("userid") ? u.get("userid").asText() : "";
            int status = u.has("status") ? u.get("status").asInt(-1) : -1;
            if (userid.isEmpty()) continue;
            if (status != 1) continue; // 只同步已激活员工（1=已激活），离职/禁用不在对账范围
            processed++;
            syncEmployee(userid, t0);
        }
        log.info("customer_relation 全量同步完成: 处理 {} 员工", processed);
        return processed;
    }

    private void syncEmployee(String userid, LocalDateTime t0) {
        List<String> snapshotExternalUserids;
        try {
            JsonNode list = wecomApi.getExternalContactList(userid);
            snapshotExternalUserids = new ArrayList<>();
            JsonNode arr = list != null && list.has("external_userid") ? list.get("external_userid") : null;
            if (arr != null && arr.isArray()) {
                for (JsonNode e : arr) snapshotExternalUserids.add(e.asText());
            }
        } catch (Exception e) {
            // 铁律：单员工 API 失败整体跳过，绝不当空列表处理
            log.warn("客户列表拉取失败，整员工跳过（留待下轮）: userid={}, err={}", userid, e.getMessage());
            return;
        }

        // external_userid → customer.id 批量反查
        Map<String, Long> idByExternal = new HashMap<>();
        if (!snapshotExternalUserids.isEmpty()) {
            for (Customer c : customerRepo.findByExternalUseridIn(snapshotExternalUserids)) {
                idByExternal.put(c.getExternalUserid(), c.getId());
            }
        }

        // upsert 快照中的关系（来源字段 COALESCE；稀疏 customer 由调用方补建后这里再 upsert）
        for (String externalUserid : snapshotExternalUserids) {
            Long customerId = idByExternal.get(externalUserid);
            if (customerId == null) {
                customerId = backfillSparseCustomer(externalUserid);
                if (customerId == null) continue;
            }
            relationService.upsertActive(customerId, userid, null, null, null);
        }

        // removed 对账：该员工名下 active 且 updated_at < T0、又不在快照里 → 置 removed
        List<CustomerRelation> active = relationRepo.findByEmployeeUseridAndStatus(userid, RelationStatus.active);
        for (CustomerRelation rel : active) {
            if (rel.getUpdatedAt() != null && !rel.getUpdatedAt().isBefore(t0)) continue; // 同步期间新增，留待下轮
            Long relCustomerId = rel.getCustomerId();
            boolean inSnapshot = customerRepo.findById(relCustomerId)
                .map(c -> snapshotExternalUserids.contains(c.getExternalUserid()))
                .orElse(false);
            if (!inSnapshot) {
                relationService.markRemoved(relCustomerId, userid);
            }
        }
    }

    /** 回调丢失的 external_userid 补建稀疏 customer 记录（只写 external_userid，其余 DataFillWorker 异步补）。 */
    private Long backfillSparseCustomer(String externalUserid) {
        Customer c = Customer.builder()
            .externalUserid(externalUserid)
            .name("未知")
            .type(1)
            .status(Customer.CustomerStatus.active)
            .addTime(LocalDateTime.now())
            .build();
        try {
            c = customerRepo.save(c);
            return c.getId();
        } catch (Exception e) {
            log.warn("稀疏 customer 补建失败: external={}, err={}", externalUserid, e.getMessage());
            return null;
        }
    }
}
```

> 注意：`Customer` 实体的 `builder()` 需包含 `type` 字段（已确认 `Customer.builder().type(1)` 在 CustomerService 中使用）。稀疏补建不额外调 `getExternalContact`（spec §4.1）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=CustomerSyncServiceTest test`
Expected: PASS。

- [ ] **Step 5: 添加调度（默认关闭）**

在 `CustomerSyncService` 顶部加：

```java
@org.springframework.beans.factory.annotation.Value("${app.customer-relation-sync.enabled:false}")
private boolean syncEnabled;

@org.springframework.scheduling.annotation.Scheduled(cron = "${app.customer-relation-sync.cron:0 37 * * * *}")
public void scheduledSync() {
    if (!syncEnabled) return;
    try { syncOnce(); }
    catch (Exception e) { log.error("customer_relation 全量同步失败", e); }
}
```

> 默认 `enabled=false`：全量同步是「上线后手动跑一次」的第 3 步，生产确认对账通过前不自动周期跑。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/CustomerSyncService.java \
  src/test/java/com/bookstore/qrcode/service/CustomerSyncServiceTest.java
git commit -m "feat: customer_relation 全量同步（逐员工拉列表 + removed 对账 + 稀疏补建）"
```

---

## Task 6: 继承改造 — 去重维度 `(customer_id, qr_code_id)` + 定位查询切换

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/CustomerTransferRepository.java`（去重方法加 qrCodeId）
- Modify: `src/main/java/com/bookstore/qrcode/service/TransferService.java`（initiate 顺序重排 + 两处锁/去重带 qrCodeId）
- Modify: `src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java`（删旧方法、留 `findForInheritance`）
- Modify: `src/main/java/com/bookstore/qrcode/job/InheritanceJob.java`（调用新查询 + Redis 去重键加 qrCodeId）
- Test: `src/test/java/com/bookstore/qrcode/repository/CustomerTransferRepositoryTest.java`（如不存在则新建）

**Interfaces:**
- Consumes: `findForInheritance`（Task 1）、`existsByCustomerIdAndQrCodeIdAndStatusIn` / `existsRecentTerminalByCustomerIdAndQrCodeId`（本任务新增）。
- Produces: `CustomerTransferRepository`：
  - `boolean existsByCustomerIdAndQrCodeIdAndStatusIn(Long customerId, Long qrCodeId, List<TransferStatus> statuses)`
  - `boolean existsRecentTerminalByCustomerIdAndQrCodeId(Long customerId, Long qrCodeId, LocalDateTime since)`

- [ ] **Step 1: 写失败的测试 — 去重按 (customer, qr) 隔离**

`src/test/java/com/bookstore/qrcode/repository/CustomerTransferRepositoryTest.java`（新建，`@DataJpaTest` + `schema-test.sql`）：

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.CustomerTransfer.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("CustomerTransferRepository 去重维度")
class CustomerTransferRepositoryTest {

    @Autowired private CustomerTransferRepository transferRepo;
    @Autowired private TestEntityManager em;

    private Customer c;

    @BeforeEach
    void setUp() {
        c = em.persistFlushFind(Customer.builder()
                .externalUserid("wm-1").name("客户").type(1)
                .status(Customer.CustomerStatus.active).addTime(LocalDateTime.now()).build());
        // c 在活码 10 有一条 confirmed 转移；活码 20 无
        transferRepo.save(CustomerTransfer.builder()
                .customerId(c.getId()).fromUserid("recA").toUserid("svcA")
                .qrCodeId(10L).status(TransferStatus.confirmed).build());
    }

    @Test
    @DisplayName("按 (customer, qr) 去重 — 不同活码互不遮挡")
    void dedupIsolatedByQrCode() {
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 10L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isTrue();  // 活码 10 有 confirmed → 挡
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 20L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isFalse(); // 活码 20 无 → 不挡（多校继承不被活码 10 的历史挡住）
    }

    @Test
    @DisplayName("qr_code_id IS NULL 的历史行降级为按 customer_id 判定")
    void nullQrDegradesToCustomerLevel() {
        transferRepo.save(CustomerTransfer.builder()
                .customerId(c.getId()).fromUserid("recB").toUserid("svcB")
                .qrCodeId(null).status(TransferStatus.confirmed).build());

        // null-qr 的历史记录仍挡任意活码（保守防重复）
        assertThat(transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(
                c.getId(), 99L, List.of(TransferStatus.pending_confirm, TransferStatus.confirmed)))
                .isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerTransferRepositoryTest test`
Expected: 编译失败（新方法不存在）。

- [ ] **Step 3: 仓库去重方法加 qrCodeId**

`src/main/java/com/bookstore/qrcode/repository/CustomerTransferRepository.java` — 替换两个旧方法为带 qrCodeId 的版本（保留旧方法名会重复，直接改名并更新全部调用点）：

```java
/**
 * 判断指定客户+活码是否存在给定状态集合的转移记录。
 * qr_code_id IS NULL 的历史行降级为仅按 customer_id 判定（保守防重复）。
 */
@Query("SELECT COUNT(t) > 0 FROM CustomerTransfer t WHERE t.customerId = :customerId AND (t.qrCodeId = :qrCodeId OR t.qrCodeId IS NULL) AND t.status IN :statuses")
boolean existsByCustomerIdAndQrCodeIdAndStatusIn(@Param("customerId") Long customerId,
                                                 @Param("qrCodeId") Long qrCodeId,
                                                 @Param("statuses") List<CustomerTransfer.TransferStatus> statuses);

/**
 * 判断指定客户+活码是否存在最近 N 天内的 terminal 转移记录（冷却期），null 降级同上。
 */
@Query("SELECT COUNT(t) > 0 FROM CustomerTransfer t WHERE t.customerId = :customerId AND (t.qrCodeId = :qrCodeId OR t.qrCodeId IS NULL) AND t.status IN ('timeout','rejected','retry_limit') AND t.updatedAt >= :since")
boolean existsRecentTerminalByCustomerIdAndQrCodeId(@Param("customerId") Long customerId,
                                                    @Param("qrCodeId") Long qrCodeId,
                                                    @Param("since") LocalDateTime since);
```

删除旧的 `existsByCustomerIdAndStatusIn`（line 135）和 `existsRecentTerminalByCustomerId`（line 254-255）。

- [ ] **Step 4: 重排 `initiate` 顺序 + 改锁/去重**

`src/main/java/com/bookstore/qrcode/service/TransferService.java` — 把「反查活码」提前到 Redis 锁之前（spec §5.3），并把锁键、两处去重改为带 `qr.getId()`：

新顺序（替换原 line 124–180 区间）：

```java
    public void initiate(Long customerId, String fromUserid,
                          String toUserid, String externalUserid, String state) {
        if (state == null) {
            log.warn("继承发起跳过: state 为空, customerId={}, external={}", customerId, externalUserid);
            return;
        }

        // 先反查活码（纯读，无副作用），锁键需要 qr.getId()
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) {
            log.warn("继承发起跳过: 找不到对应活码, state={}, customerId={}", state, customerId);
            return;
        }

        // Redis 分布式锁：按 (customer, qr) 维度，防止同一客户同一学校并发重复继承
        String lockKey = "lock:transfer:" + customerId + ":" + qr.getId();
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(120));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("客户 {} 活码 {} 继承正在处理中（锁占用），稍后重试", customerId, qr.getId());
            throw new LockContentionException("客户 " + customerId + " 活码 " + qr.getId() + " 继承锁占用，稍后重试");
        }
        boolean syncActive = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            redisTemplate.delete(lockKey);
                        }
                    });
                syncActive = true;
            }

        List<CustomerTransfer.TransferStatus> dedupStatuses = List.of(
            CustomerTransfer.TransferStatus.pending_confirm,
            CustomerTransfer.TransferStatus.confirmed);
        if (transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(customerId, qr.getId(), dedupStatuses)) {
            log.info("客户 {} 活码 {} 已有进行中/已完成继承记录，跳过", customerId, qr.getId());
            return;
        }

        if (transferRepo.existsRecentTerminalByCustomerIdAndQrCodeId(customerId, qr.getId(),
                LocalDateTime.now().minus(TRANSFER_COOLDOWN))) {
            log.info("客户 {} 活码 {} 在冷却期内，跳过", customerId, qr.getId());
            return;
        }

        // 后续逻辑从「查找服务老师」开始（原 findBySchoolId 已提前，删除此处的旧调用）
        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            ...
```

> 关键：删掉原 line 176 `QrCode qr = qrCodeRepo.findBySchoolId(state)...`（已提前到锁前），其余（服务老师判定、异常落库等）不变，仅把 `qr.getId()` 用于锁键与去重。

- [ ] **Step 5: 改 `retryFailedTransfers` 锁/去重带 qrCodeId**

`src/main/java/com/bookstore/qrcode/service/TransferService.java` — `retryFailedTransfers` 内两处（spec §5.2 #6/#7）：

```java
// 原 line 663：
String retryLockKey = "lock:transfer:" + t.getCustomerId() + ":" + t.getQrCodeId();

// 原 line 672：
if (transferRepo.existsByCustomerIdAndQrCodeIdAndStatusIn(t.getCustomerId(), t.getQrCodeId(),
        List.of(CustomerTransfer.TransferStatus.pending_confirm,
                CustomerTransfer.TransferStatus.confirmed))) {
```

> `t.getQrCodeId()` 可能为 null（历史记录），锁键 `...:null` 与 initiate 的 `...:{qrId}` 不同——这正是 null 降级语义：历史 null 记录的重试锁不阻塞任何具体活码的 initiate，反之亦然。可接受（null 记录本就归属不明）。

- [ ] **Step 6: 删除旧查询，切换 InheritanceJob**

`src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java` — 删除 `findWithoutTransferByAgentAndSchoolIdAndAddTimeBetween`（line 308-313，确认无其它调用点）。

`src/main/java/com/bookstore/qrcode/job/InheritanceJob.java` — 把 line 300-302 的调用改为：

```java
List<Customer> customers = customerRepo.findForInheritance(
    rec.getAgentUserid(), qr.getId(), windowStart, windowEnd);
```

并把 Redis 入队去重键（line 307）改为：

```java
String dedupKey = "transfer:queued:" + c.getId() + ":" + qr.getId();
```

> 注意：`InheritanceJob` 的 `customerRepo` 需注入 `CustomerRepository`（已注入）；`findForInheritance` 在 `CustomerRepository` 上（Task 1 把它放进了 `CustomerRelationRepository`——此处调整：`findForInheritance` 实际应放在 `CustomerRepository`，因为返回 `List<Customer>` 且 InheritanceJob 已注入 customerRepo）。

**修正 Task 1 的接口归属**：`findForInheritance` 归属 `CustomerRepository`（不是 `CustomerRelationRepository`）。执行 Task 1 时把该方法写进 `CustomerRepository.java`，`CustomerRelationRepositoryTest` 里通过 `customerRepository.findForInheritance(...)` 调用（测试需同时注入 `CustomerRepository`）。若 Task 1 已按 `CustomerRelationRepository` 落库，本任务把它移到 `CustomerRepository`。

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q -Dtest=CustomerTransferRepositoryTest,CustomerRelationRepositoryTest test`
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/CustomerTransferRepository.java \
  src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java \
  src/main/java/com/bookstore/qrcode/service/TransferService.java \
  src/main/java/com/bookstore/qrcode/job/InheritanceJob.java \
  src/test/java/com/bookstore/qrcode/repository/CustomerTransferRepositoryTest.java
git commit -m "feat: 继承去重维度改为 (customer_id, qr_code_id) + 定位查询切换"
```

---

## Task 7: 回填 `customer_transfer.qr_code_id`（切换去重维度的前置迁移）

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/service/CustomerTransferQrBackfillService.java`
- Modify: `src/main/resources/schema.sql`（加 `customer_transfer` 复合索引）
- Test: `src/test/java/com/bookstore/qrcode/service/CustomerTransferQrBackfillServiceTest.java`

**Interfaces:**
- Consumes: `CustomerTransferRepository`（新增 `findByQrCodeIdIsNull`）、`QrCodeRepository.findBySchoolId`、`CustomerRepository`。
- Produces: `CustomerTransferRepository.findByQrCodeIdIsNull()`；`int backfill()` 返回回填行数。

- [ ] **Step 1: 写失败的测试**

`src/test/java/com/bookstore/qrcode/service/CustomerTransferQrBackfillServiceTest.java`（Mockito）：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.entity.CustomerTransfer.TransferStatus;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerTransferQrBackfillService 回填 qr_code_id")
class CustomerTransferQrBackfillServiceTest {

    @Mock private CustomerTransferRepository transferRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @InjectMocks private CustomerTransferQrBackfillService backfillService;

    @Test
    @DisplayName("据 customer.school_id 反查活码回填 qr_code_id")
    void backfillFromSchoolId() {
        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(null).status(TransferStatus.confirmed).build();
        when(transferRepo.findByQrCodeIdIsNull()).thenReturn(List.of(t));
        Customer c = Customer.builder().id(1L).schoolId("SCHOOL-1").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(c));
        QrCode qr = new QrCode();
        qr.setId(10L);
        when(qrCodeRepo.findBySchoolId("SCHOOL-1")).thenReturn(Optional.of(qr));

        int n = backfillService.backfill();

        assertThat(n).isEqualTo(1);
        assertThat(t.getQrCodeId()).isEqualTo(10L);
        verify(transferRepo).save(t);
    }

    @Test
    @DisplayName("反查不到活码（活码已删）→ 保持 null，不误填")
    void leaveNullWhenQrMissing() {
        CustomerTransfer t = CustomerTransfer.builder()
                .id(1L).customerId(1L).fromUserid("recA").toUserid("svcA")
                .qrCodeId(null).status(TransferStatus.confirmed).build();
        when(transferRepo.findByQrCodeIdIsNull()).thenReturn(List.of(t));
        Customer c = Customer.builder().id(1L).schoolId("SCHOOL-GONE").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(c));
        when(qrCodeRepo.findBySchoolId("SCHOOL-GONE")).thenReturn(Optional.empty());

        int n = backfillService.backfill();

        assertThat(n).isEqualTo(0);
        assertThat(t.getQrCodeId()).isNull();
        verify(transferRepo, never()).save(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=CustomerTransferQrBackfillServiceTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现回填服务 + 仓库方法**

`CustomerTransferRepository` 加：

```java
List<CustomerTransfer> findByQrCodeIdIsNull();
```

`CustomerTransferQrBackfillService.java`：

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTransfer;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerTransferQrBackfillService {

    private final CustomerTransferRepository transferRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;

    /** 回填存量 customer_transfer.qr_code_id：据 customer.school_id 反查活码主键。返回回填行数。 */
    public int backfill() {
        List<CustomerTransfer> nulls = transferRepo.findByQrCodeIdIsNull();
        int filled = 0;
        for (CustomerTransfer t : nulls) {
            Customer c = customerRepo.findById(t.getCustomerId()).orElse(null);
            if (c == null || c.getSchoolId() == null) continue;
            qrCodeRepo.findBySchoolId(c.getSchoolId()).ifPresent(qr -> {
                t.setQrCodeId(qr.getId());
                transferRepo.save(t);
            });
            if (t.getQrCodeId() != null) filled++;
        }
        log.info("customer_transfer.qr_code_id 回填: {} 行中回填 {} 行", nulls.size(), filled);
        return filled;
    }
}
```

> 回填不可反查的（活码已删）保持 null，依赖 Task 6 的 `(t.qrCodeId = :qrCodeId OR t.qrCodeId IS NULL)` 降级语义兜底。

- [ ] **Step 4: 加复合索引**

`src/main/resources/schema.sql` 追加（动态 SQL 兼容）：

```sql
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND INDEX_NAME = 'idx_transfer_customer_qr') = 0,
    'CREATE INDEX idx_transfer_customer_qr ON customer_transfer (customer_id, qr_code_id)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

`src/test/resources/schema-test.sql` 追加：

```sql
CREATE INDEX IF NOT EXISTS idx_transfer_customer_qr ON customer_transfer (customer_id, qr_code_id);
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -Dtest=CustomerTransferQrBackfillServiceTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/service/CustomerTransferQrBackfillService.java \
  src/main/java/com/bookstore/qrcode/repository/CustomerTransferRepository.java \
  src/main/resources/schema.sql src/test/resources/schema-test.sql \
  src/test/java/com/bookstore/qrcode/service/CustomerTransferQrBackfillServiceTest.java
git commit -m "feat: customer_transfer.qr_code_id 回填服务 + 复合索引"
```

---

## Self-Review

**1. Spec coverage**（逐节核对）：
- §3 DDL/实体/仓库 → Task 1 ✅
- §4.1 全量同步（逐员工、COALESCE、removed 对账、稀疏补建、单员工失败跳过、离职过滤）→ Task 5 ✅
- §4.2 增量回调（add/delete/del_follow_user/transfer confirmed + 4 个实现要点）→ Task 2/3/4 ✅
- §4.3 同步与增量一致性（唯一键合并）→ Task 1/5 ✅
- §4.4 并发三条铁律 → Task 2（COALESCE）+ Task 5（T0 removed 对账 + 整员工跳过）✅
- §5.1 定位查询 → Task 1（findForInheritance）+ Task 6（切换）✅
- §5.2 7 处去重维度 → Task 6（#1 Redis 键、#2 NOT EXISTS、#3/#6 锁、#4/#5/#7 去重）✅
- §5.3 initiate 顺序重排 → Task 6 Step 4 ✅
- §5.4 current_agent 已知副作用（不改）→ 无需任务 ✅
- §6 统计口径（二期）→ 明确排除，无任务 ✅
- §7 对账验收 → 手动验收步骤（非代码任务），在部署后执行 ✅
- §8 迁移步骤 → Task 1→7 顺序对应 ✅
- §9 风险 6（分页截断）→ Task 5 前置验证说明（见下方 Note）✅

**2. Placeholder scan**：无 TBD/TODO。唯一「验证」性质步骤是分页前置检查，已给出具体做法（下）。

**3. Type consistency**：`RelationStatus`、`CustomerRelation`、`findForInheritance`、`existsByCustomerIdAndQrCodeIdAndStatusIn`、`existsRecentTerminalByCustomerIdAndQrCodeId`、`applyTransferConfirmed`、`upsertActive`、`markRemoved`、`backfill` 各任务间命名一致；`findForInheritance` 归属在 Task 6 Step 6 已显式纠正为 `CustomerRepository`。

---

## 关键部署注意（非代码任务，执行时执行）

- **分页前置检查（spec §4.1/§9 风险 6）**：Task 5 全量同步前，先在生产 DB 跑一条探查 SQL 确认 `externalcontact/list` 单员工是否截断：
  ```sql
  SELECT employee_userid, COUNT(*) FROM customer_relation GROUP BY employee_userid ORDER BY COUNT(*) DESC LIMIT 20;
  ```
  首次全量同步后若某员工客户数达到 1000 附近，需改用 `externalcontact/batch/get_by_user` 分页接口补拉。当前人均 ~80，预计无截断；此检查作为 §8 第 3 步「跑全量同步」的前置。
- **部署顺序严格按 §8**：1 建表 → 2 增量写入（Task 2/3/4 上线）→ 3 手动跑一次全量同步（Task 5，`enabled=false` 下用管理端/临时入口触发）→ 4 对账 → 5 回填 qr_code_id（Task 7）+ 切继承（Task 6）→ 6（二期）切统计口径。
- **回滚**：每步独立部署，1–4 步零影响（纯新增）；Task 6 切换继承若异常，回退 `InheritanceJob` 与 `TransferService` 到上一版本即可，`customer_relation` 表不动。
