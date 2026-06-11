# 全局员工池 + 手动继承 + 单码轮换 实现计划

> **For agentic workers:** 按任务顺序执行，每完成一个任务后编译验证。

**Goal:** 将每码独立后备池重构为全局共享员工池，在职继承改为手动触发，消除 CallbackWorker 主链路上的企微 API 调用。

**Architecture:** 新增 `global_agent_pool` 表替代 `qr_backup_pool`，员工从全局池按需分配到活码。日计数全局统一，单码独立满员处理。活码创建时指定在职继承目标员工（`transfer_target_userid`），继承由管理员手动触发。删除 `TransferWorker` 和 `wecom:transfer:stream`。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data JPA, Redis Stream, MySQL, Lombok

---

## 影响文件清单

| 文件 | 操作 | 职责 |
|------|:--:|------|
| `entity/GlobalAgentPool.java` | 新建 | 全局员工池实体 |
| `repository/GlobalAgentPoolRepository.java` | 新建 | 全局池数据访问 |
| `service/GlobalAgentPoolService.java` | 新建 | 全局池业务（取人、标记满员、统计、日重置） |
| `schema.sql` | 修改 | 加 `global_agent_pool` 表；`qr_code` 加 `transfer_target_userid` + `initial_agent_count` |
| `entity/QrCode.java` | 修改 | 加 `transferTargetUserid`、`initialAgentCount` |
| `dto/QrCodeCreateRequest.java` | 修改 | 加 `transferTargetUserid`、`initialAgentCount`、`initialAgentUserids` |
| `service/AgentBindService.java` | 修改 | 轮换逻辑从 `QrBackupPool` 改为 `GlobalAgentPool`；修 `@Async` bug |
| `service/QrCodeService.java` | 修改 | `bindAgents` 改为从全局池取人；后备池管理改写 |
| `controller/QrCodeController.java` | 修改 | 后备池 API 改为全局池 API；列表统计调整 |
| `worker/CallbackWorker.java` | 修改 | 删除步骤⑤ XADD transfer 事件 |
| `worker/TransferWorker.java` | 删除 | 不再需要 |
| `config/RedisConfig.java` | 修改 | 删除 `TRANSFER_STREAM_*` 常量和 bean |
| `worker/PatrolWorker.java` | 修改 | `checkEmptyBackupPools` → `checkGlobalPoolLow` |
| `config/AsyncConfig.java` | 修改 | Javadoc 移除 TransferWorker 引用 |
| `worker/DailyResetWorker.java` | 修改 | reset 改为调 `GlobalAgentPoolService.dailyReset()` |

---

### Task 1: 数据库 Schema + GlobalAgentPool 实体+Repository

**Files:**
- Create: `entity/GlobalAgentPool.java`
- Create: `repository/GlobalAgentPoolRepository.java`
- Modify: `schema.sql`

- [x] **Step 1: schema.sql — 新增 global_agent_pool 表、qr_code 加字段**

在 `qr_backup_pool` 定义后插入：

```sql
-- global_agent_pool：全局员工池（替代 qr_backup_pool）
CREATE TABLE IF NOT EXISTS global_agent_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_userid VARCHAR(100) NOT NULL UNIQUE COMMENT '企微员工UserID',
    daily_max INT NOT NULL DEFAULT 200 COMMENT '全局日接待上限',
    daily_current INT NOT NULL DEFAULT 0 COMMENT '今日已接待（所有活码合计）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '分配优先级，越小越先被分配',
    status VARCHAR(20) NOT NULL DEFAULT 'standby' COMMENT 'standby/full/blocked',
    last_reset_at DATETIME COMMENT '上次日重置时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid),
    INDEX idx_status (status),
    INDEX idx_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局员工池';
```

`qr_code` 表在 `custom_tags` 字段之前加两个新字段：

```sql
ALTER TABLE qr_code
    ADD COLUMN IF NOT EXISTS transfer_target_userid VARCHAR(100) COMMENT '在职继承目标员工',
    ADD COLUMN IF NOT EXISTS initial_agent_count INT DEFAULT 1 COMMENT '活码创建时初始上码人数';
```

- [x] **Step 2: 创建 GlobalAgentPool 实体**

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "global_agent_pool")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalAgentPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_userid", nullable = false, length = 100, unique = true)
    private String agentUserid;

    @Column(name = "daily_max", nullable = false)
    @Builder.Default
    private Integer dailyMax = 200;

    @Column(name = "daily_current")
    @Builder.Default
    private Integer dailyCurrent = 0;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PoolStatus status = PoolStatus.standby;

    @Column(name = "last_reset_at")
    private LocalDateTime lastResetAt;

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

    public enum PoolStatus {
        standby,   // 待命，可被分配
        full,      // 今日配额用完
        blocked    // 管理员暂停
    }
}
```

- [x] **Step 3: 创建 GlobalAgentPoolRepository**

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GlobalAgentPoolRepository
        extends JpaRepository<GlobalAgentPool, Long> {

    Optional<GlobalAgentPool> findByAgentUserid(String agentUserid);

    List<GlobalAgentPool> findByStatusOrderBySortOrder(
        GlobalAgentPool.PoolStatus status);

    long countByStatus(GlobalAgentPool.PoolStatus status);
}
```

- [x] **Step 4: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/resources/schema.sql \
        src/main/java/com/bookstore/qrcode/entity/GlobalAgentPool.java \
        src/main/java/com/bookstore/qrcode/repository/GlobalAgentPoolRepository.java
git commit -m "feat: 新增全局员工池表、实体和Repository"
```

---

### Task 2: GlobalAgentPoolService

**Files:**
- Create: `service/GlobalAgentPoolService.java`

- [x] **Step 1: 创建 Service**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalAgentPoolService {

    private final GlobalAgentPoolRepository poolRepo;
    private final AgentRepository agentRepo;
    private final WecomApiClient wecomApi;

    /** 从全局池取优先级最高的 standby 员工 */
    @Transactional
    public GlobalAgentPool takeStandby() {
        List<GlobalAgentPool> standbys = poolRepo
            .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
        if (standbys.isEmpty()) {
            log.warn("全局员工池无 standby 员工可用！");
            return null;
        }
        return standbys.get(0);
    }

    /** 标记员工日限到达 */
    @Transactional
    public void markFull(String agentUserid) {
        poolRepo.findByAgentUserid(agentUserid).ifPresent(pool -> {
            pool.setStatus(GlobalAgentPool.PoolStatus.full);
            pool.setLastResetAt(LocalDateTime.now());
            poolRepo.save(pool);
        });
    }

    /** 更新今日累计接待数 */
    @Transactional
    public void updateDailyCurrent(String agentUserid, int count) {
        poolRepo.findByAgentUserid(agentUserid).ifPresent(pool -> {
            pool.setDailyCurrent(count);
            poolRepo.save(pool);
        });
    }

    /** 确保员工在全局池中（不存在则创建） */
    @Transactional
    public void ensureInPool(String userid, int dailyMax) {
        if (poolRepo.findByAgentUserid(userid).isPresent()) return;

        // 确保 Agent 全局表存在
        if (!agentRepo.existsById(userid)) {
            String name = userid;
            try {
                JsonNode result = wecomApi.getUserSimplelist();
                if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                    for (JsonNode u : result.get("userlist")) {
                        if (userid.equals(u.get("userid").asText())) {
                            name = u.get("name").asText();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取员工姓名失败: userid={}", userid);
            }
            agentRepo.save(Agent.builder()
                .userid(userid).name(name)
                .role(Agent.AgentRole.receptionist)
                .dailyTotalCap(500).build());
        }

        int maxOrder = poolRepo.findAll().stream()
            .mapToInt(GlobalAgentPool::getSortOrder).max().orElse(0);

        poolRepo.save(GlobalAgentPool.builder()
            .agentUserid(userid).dailyMax(dailyMax)
            .sortOrder(maxOrder + 1)
            .status(GlobalAgentPool.PoolStatus.standby).build());
    }

    /** standby 余量 */
    public long countStandby() {
        return poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);
    }

    /** 获取所有 standby 员工列表（供管理后台展示） */
    public List<GlobalAgentPool> listStandby() {
        return poolRepo.findByStatusOrderBySortOrder(
            GlobalAgentPool.PoolStatus.standby);
    }

    /** 每日重置 */
    @Transactional
    public void dailyReset() {
        List<GlobalAgentPool> fulls = poolRepo
            .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.full);
        for (GlobalAgentPool p : fulls) {
            p.setStatus(GlobalAgentPool.PoolStatus.standby);
            p.setDailyCurrent(0);
            p.setLastResetAt(LocalDateTime.now());
            poolRepo.save(p);
        }
        log.info("全局池日重置: 恢复 {} 个 full 员工", fulls.size());
    }
}
```

- [x] **Step 2: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/service/GlobalAgentPoolService.java
git commit -m "feat: 全局员工池服务"
```

---

### Task 3: 修 @Async bug + 重构 AgentBindService

**Files:**
- Modify: `service/AgentBindService.java`

**当前问题：**
1. `syncQrCodeToWechatAsync` 的 `@Async` 被内部调用绕过代理，不会异步执行
2. 轮换逻辑用 `QrBackupPool` → 改为 `GlobalAgentPool`
3. 日计数阈值检查用 `qa.getDailyMax()`（每码独立）→ 改为全局 `totalKey` 计数

**改动：**

- [x] **Step 1: 注入自身代理引用**

```java
// 在类字段区末尾加：
private final AgentBindService self;  // Lombok @RequiredArgsConstructor 自动注入
```

- [x] **Step 2: 修改 incrementDailyCount — 日计数改为全局阈值检查**

```java
public void incrementDailyCount(String userId, String state) {
    QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
    if (qr == null) return;

    String key = RedisConfig.AGENT_DAILY_KEY_PREFIX + userId + ":" + qr.getId();
    String totalKey = RedisConfig.AGENT_DAILY_TOTAL_PREFIX + userId;

    long newCount = redisTemplate.opsForValue().increment(key);
    long totalNew = redisTemplate.opsForValue().increment(totalKey);

    redisTemplate.expire(key, getSecondsUntilMidnight(), TimeUnit.SECONDS);
    redisTemplate.expire(totalKey, getSecondsUntilMidnight(), TimeUnit.SECONDS);

    // 同步到 DB
    QrAgent qa = qrAgentRepo.findByQrCodeIdAndAgentUserid(qr.getId(), userId).orElse(null);
    if (qa != null) {
        qa.setDailyCurrent((int) newCount);
        qrAgentRepo.save(qa);
    }
    // 同步全局池的 daily_current
    poolService.updateDailyCurrent(userId, (int) totalNew);

    // 阈值检查使用全局计数
    checkAndRotate(qr.getId(), userId, (int) totalNew);
}
```

- [x] **Step 3: 修改 checkAndRotate — 用 GlobalAgentPool.dailyMax 判断**

```java
@Transactional
public void checkAndRotate(Long qrCodeId, String userId, int globalCount) {
    GlobalAgentPool pool = poolRepo.findByAgentUserid(userId).orElse(null);
    if (pool == null) return;

    QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
    if (qr == null) return;

    int dailyMax = pool.getDailyMax();
    int warnThreshold = (dailyMax * qr.getWarnRatio()) / 100;
    int urgentThreshold = (dailyMax * qr.getUrgentRatio()) / 100;

    if (globalCount >= dailyMax) {
        log.warn("员工 {} 全局日限到达 {}/{}，从活码 {} 下码", userId, globalCount, dailyMax, qrCodeId);
        expandQrCodeUsers(qrCodeId, userId, qr, pool);
    } else if (globalCount >= urgentThreshold) {
        log.warn("员工 {} 全局紧急阈值 {}/{}，活码 {} 提前激活后备",
            userId, globalCount, dailyMax, qrCodeId);
        preActivateBackup(qrCodeId, qr);
    } else if (globalCount >= warnThreshold) {
        log.info("员工 {} 全局预警阈值 {}/{}", userId, globalCount, dailyMax);
    }
}
```

- [x] **Step 4: 修改 expandQrCodeUsers — 从全局池取人，标记全局满员**

```java
@Transactional
public void expandQrCodeUsers(Long qrCodeId, String fullUserId,
                                QrCode qr, GlobalAgentPool fullPool) {
    String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":expand";
    Boolean locked = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
    if (Boolean.FALSE.equals(locked)) return;

    try {
        if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

        // 从全局池取人
        GlobalAgentPool backup = poolService.takeStandby();
        if (backup == null) {
            log.error("全局池枯竭！活码 {} 无法扩容", qrCodeId);
            return;
        }
        String backupUserid = backup.getAgentUserid();

        // 创建 QrAgent
        QrAgent newAgent = QrAgent.builder()
            .qrCodeId(qrCodeId).agentUserid(backupUserid)
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(backup.getDailyMax())
            .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
            .status(QrAgent.AgentStatus.active).build();
        qrAgentRepo.save(newAgent);

        // 满员员工下码
        QrAgent fullAgent = qrAgentRepo.findByQrCodeIdAndAgentUserid(qrCodeId, fullUserId).orElse(null);
        if (fullAgent != null) {
            fullAgent.setStatus(QrAgent.AgentStatus.full);
            fullAgent.setReplacedBy(backupUserid);
            fullAgent.setUpdatedAt(LocalDateTime.now());
            qrAgentRepo.save(fullAgent);
        }

        // 全局池标记满员
        poolService.markFull(fullUserId);

        // 事务提交后异步同步企微（通过代理调用确保 @Async 生效）
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.syncQrCodeToWechatAsync(qrCodeId);
                }
            });

        rotateLogRepo.save(QrRotateLog.builder()
            .qrCodeId(qrCodeId).toUserid(backupUserid)
            .reason("全局日限到达 — 自动扩容").build());

        log.info("扩容完成: 活码{} 员工{}下码, {}上码", qrCodeId, fullUserId, backupUserid);
    } finally {
        redisTemplate.delete(lockKey);
    }
}
```

- [x] **Step 5: 修改 preActivateBackup — 从全局池取人**

```java
@Transactional
public void preActivateBackup(Long qrCodeId, QrCode qr) {
    String lockKey = RedisConfig.ROTATE_LOCK_PREFIX + qrCodeId + ":preactivate";
    Boolean locked = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
    if (Boolean.FALSE.equals(locked)) return;

    try {
        if (qr.getRotateMode() == QrCode.RotateMode.manual) return;

        long activeReceptionists = qrAgentRepo
            .findByQrCodeIdAndStatus(qrCodeId, QrAgent.AgentStatus.active)
            .stream().filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).count();
        if (activeReceptionists > 0) return;

        GlobalAgentPool backup = poolService.takeStandby();
        if (backup == null) {
            log.warn("全局池无 standby，活码 {} 无法预激活", qrCodeId);
            return;
        }
        String backupUserid = backup.getAgentUserid();

        QrAgent newAgent = QrAgent.builder()
            .qrCodeId(qrCodeId).agentUserid(backupUserid)
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(backup.getDailyMax())
            .sortOrder(qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId).size())
            .status(QrAgent.AgentStatus.active).build();
        qrAgentRepo.save(newAgent);

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.syncQrCodeToWechatAsync(qrCodeId);
                }
            });

        rotateLogRepo.save(QrRotateLog.builder()
            .qrCodeId(qrCodeId).toUserid(backupUserid)
            .reason("全局紧急阈值触发 — 提前激活").build());

        log.info("预激活: 活码{} 加入 {}", qrCodeId, backupUserid);
    } finally {
        redisTemplate.delete(lockKey);
    }
}
```

- [x] **Step 6: 修改 dailyReset — 全局池重置 + QrAgent 恢复**

```java
@Transactional
public void dailyReset() {
    // 全局池恢复 full → standby
    poolService.dailyReset();

    // QrAgent 恢复 full → active
    List<QrAgent> fullAgents = qrAgentRepo.findByStatus(QrAgent.AgentStatus.full);
    for (QrAgent qa : fullAgents) {
        qa.setStatus(QrAgent.AgentStatus.active);
        qa.setDailyCurrent(0);
        qa.setLastResetAt(LocalDateTime.now());
        qrAgentRepo.save(qa);
    }
    log.info("每日重置: 恢复 {} 个 full 员工", fullAgents.size());

    // 同步所有受影响的活码
    fullAgents.stream()
        .map(QrAgent::getQrCodeId)
        .distinct()
        .forEach(qrCodeId ->
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.syncQrCodeToWechatAsync(qrCodeId);
                    }
                }));
}
```

- [x] **Step 7: 替换依赖，删除不再用的 backupPoolRepo**

```java
// 旧依赖（删除）
// private final QrBackupPoolRepository backupPoolRepo;

// 新依赖（加）
private final GlobalAgentPoolRepository poolRepo;
private final GlobalAgentPoolService poolService;
private final AgentBindService self;  // 自身代理，确保 @Async 生效
```

- [x] **Step 8: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/service/AgentBindService.java
git commit -m "refactor: AgentBindService 改用全局池 + 修 @Async bug

- 日计数阈值检查改为全局计数 (agent:daily:total:{userid})
- 扩容/预激活从 GlobalAgentPool 取人
- 自身代理注入 self，确保 @Async 通过 AOP 生效
- 删除 QrBackupPoolRepository 依赖"
```

---

### Task 4: 修改 QrCode 实体 + CreateRequest DTO

**Files:**
- Modify: `entity/QrCode.java`
- Modify: `dto/QrCodeCreateRequest.java`

- [x] **Step 1: QrCode 加两个字段**

在 `QrCode.java` 的 `customTags` 字段之前加：

```java
/** 在职继承目标员工（手动触发继承时的导入对象） */
@Column(name = "transfer_target_userid", length = 100)
private String transferTargetUserid;

/** 活码创建时初始上码人数，默认 1 */
@Column(name = "initial_agent_count")
@Builder.Default
private Integer initialAgentCount = 1;
```

- [x] **Step 2: QrCodeCreateRequest 加字段**

在 `customTags` 字段之前加：

```java
/** 在职继承目标员工 userid */
private String transferTargetUserid;

/** 初始上码员工数，默认 1 */
private Integer initialAgentCount;

/** 初始上码员工 userid 列表（逗号分隔，如 "zhangsan,lisi"） */
private String initialAgentUserids;
```

- [x] **Step 3: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/entity/QrCode.java \
        src/main/java/com/bookstore/qrcode/dto/QrCodeCreateRequest.java
git commit -m "feat: QrCode 加 transferTargetUserid/initialAgentCount"
```

---

### Task 5: 重构 QrCodeService

**Files:**
- Modify: `service/QrCodeService.java`

- [x] **Step 1: 替换依赖**

```java
// 删除
// private final QrBackupPoolRepository backupRepo;

// 新增
private final GlobalAgentPoolRepository poolRepo;
private final GlobalAgentPoolService poolService;
```

- [x] **Step 2: 重写 bindAgents — 从全局池取初始员工**

```java
private void bindAgents(Long qrCodeId, QrCodeCreateRequest req) {
    try {
        int sortOrder = 0;

        // ① 确保在职继承目标在全局池中
        if (req.getTransferTargetUserid() != null && !req.getTransferTargetUserid().isBlank()) {
            poolService.ensureInPool(req.getTransferTargetUserid().trim(), 200);
        }

        // ② 初始上码员工：优先使用 initialAgentUserids 列表
        //    其次从 serviceTeacherUserid / receptionistUserid 兼容旧格式
        //    最后从全局池自动取人
        List<String> initialUserids = new ArrayList<>();
        if (req.getInitialAgentUserids() != null && !req.getInitialAgentUserids().isBlank()) {
            for (String uid : req.getInitialAgentUserids().split(",")) {
                String trimmed = uid.trim();
                if (!trimmed.isEmpty()) initialUserids.add(trimmed);
            }
        } else {
            // 兼容旧格式：服务老师 + 接待员都作为初始上码员工
            if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                for (String uid : req.getServiceTeacherUserid().split(",")) {
                    String t = uid.trim();
                    if (!t.isEmpty()) initialUserids.add(t);
                }
            }
            if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                for (String uid : req.getReceptionistUserid().split(",")) {
                    String t = uid.trim();
                    if (!t.isEmpty() && !initialUserids.contains(t)) initialUserids.add(t);
                }
            }
        }

        int needCount = req.getInitialAgentCount() != null
            ? req.getInitialAgentCount() : 1;

        // 确保指定员工在全局池中，并写入 QrAgent
        for (String uid : initialUserids) {
            poolService.ensureInPool(uid, req.getServiceDailyMax() != null
                ? req.getServiceDailyMax() : 200);
            qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qrCodeId).agentUserid(uid)
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(req.getServiceDailyMax() != null ? req.getServiceDailyMax() : 200)
                .sortOrder(sortOrder++)
                .status(QrAgent.AgentStatus.active).build());
            needCount--;
        }

        // 不够数，从全局池自动补
        while (needCount > 0) {
            GlobalAgentPool next = poolService.takeStandby();
            if (next == null) break;
            qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qrCodeId).agentUserid(next.getAgentUserid())
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(next.getDailyMax())
                .sortOrder(sortOrder++)
                .status(QrAgent.AgentStatus.active).build());
            needCount--;
        }

    } catch (Exception e) {
        log.error("绑定员工失败", e);
        throw new RuntimeException("绑定员工失败: " + e.getMessage(), e);
    }
}
```

- [x] **Step 3: 重写 getBackups — 改为展示全局池 standby 列表**

```java
public List<GlobalAgentPool> getBackups(Long qrCodeId) {
    // 返回全局池中所有 standby 员工（不再按活码过滤）
    return poolService.listStandby();
}
```

- [x] **Step 4: addBackup / removeBackup / moveBackup 改为操作全局池**

```java
// addBackup → 加入全局池
@Transactional
public void addBackup(Long qrCodeId, String agentUserid) {
    getById(qrCodeId); // 校验活码存在
    poolService.ensureInPool(agentUserid, 200);
}

// removeBackup → 从全局池中删除
@Transactional
public void removeBackup(Long qrCodeId, Long backupId) {
    // qrCodeId 仅用于权限校验，实际操作全局池
    poolRepo.findById(backupId).ifPresent(poolRepo::delete);
}

// moveBackup → 调整全局池排序
@Transactional
public void moveBackup(Long qrCodeId, Long backupId, String direction) {
    GlobalAgentPool target = poolRepo.findById(backupId).orElse(null);
    if (target == null) return;

    List<GlobalAgentPool> all = poolRepo
        .findByStatusOrderBySortOrder(GlobalAgentPool.PoolStatus.standby);
    int idx = -1;
    for (int i = 0; i < all.size(); i++) {
        if (all.get(i).getId().equals(backupId)) { idx = i; break; }
    }
    if (idx < 0) return;

    if ("up".equals(direction) && idx > 0) {
        GlobalAgentPool other = all.get(idx - 1);
        int tmp = target.getSortOrder();
        target.setSortOrder(other.getSortOrder());
        other.setSortOrder(tmp);
        poolRepo.save(target);
        poolRepo.save(other);
    } else if ("down".equals(direction) && idx < all.size() - 1) {
        GlobalAgentPool other = all.get(idx + 1);
        int tmp = target.getSortOrder();
        target.setSortOrder(other.getSortOrder());
        other.setSortOrder(tmp);
        poolRepo.save(target);
        poolRepo.save(other);
    }
}
```

- [x] **Step 5: create() 方法中保存新字段**

```java
// 在 create() 的 QrCode.builder() 中加：
QrCode qr = QrCode.builder()
    // ... 原有字段 ...
    .transferTargetUserid(req.getTransferTargetUserid())
    .initialAgentCount(req.getInitialAgentCount() != null
        ? req.getInitialAgentCount() : 1)
    // ... 其余不变 ...
    .build();
```

- [x] **Step 6: import 清理**

```java
// 加:
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;

// 删:
// import com.bookstore.qrcode.repository.QrBackupPoolRepository;
```

- [x] **Step 7: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/service/QrCodeService.java
git commit -m "refactor: QrCodeService 改用全局池 + bindAgents 支持多员工初始上码"
```

---

### Task 6: 修改 QrCodeController

**Files:**
- Modify: `controller/QrCodeController.java`

- [x] **Step 1: 替换依赖**

```java
// 删除
// private final QrBackupPoolRepository backupPoolRepo;

// 新增
private final GlobalAgentPoolRepository poolRepo;
```

- [x] **Step 2: list() 中后备池统计改为全局池统计**

```java
// 旧代码：
// long backupCount = backupPoolRepo.countByQrCodeIdAndStatus(qr.getId(), ...);

// 新代码：
long backupCount = poolRepo.countByStatus(GlobalAgentPool.PoolStatus.standby);

// agentCountMap 展示也调整：
agentCountMap.put(qr.getId(), activeCount + "/" + backupCount);
// 语义变为："值守/全局后备"（全局后备不再按活码区分）
```

- [x] **Step 3: detail() 中后备列表改用全局池**

```java
// 旧代码：
// List<QrBackupPool> backups = qrCodeService.getBackups(id);

// 新代码：返回 GlobalAgentPool 列表
List<GlobalAgentPool> backups = qrCodeService.getBackups(id);
```

- [x] **Step 4: import 清理 + 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "refactor: QrCodeController 后备池展示改为全局池"
```

---

### Task 7: 更新 PatrolWorker

**Files:**
- Modify: `worker/PatrolWorker.java`

- [x] **Step 1: checkEmptyBackupPools → checkGlobalPoolLow**

```java
// 旧依赖（删除）
// private final QrBackupPoolRepository backupPoolRepo;

// 新依赖（加）
private final GlobalAgentPoolRepository poolRepo;

// 旧方法 checkEmptyBackupPools() → 改为 checkGlobalPoolLow()
private void checkGlobalPoolLow() {
    long standbyCount = poolRepo.countByStatus(
        GlobalAgentPool.PoolStatus.standby);
    if (standbyCount == 0) {
        alertService.alertEmptyBackup(null, "全局后备池完全枯竭！");
    } else if (standbyCount < 5) {
        alertService.alertEmptyBackup(null,
            "全局后备池严重不足: 仅剩 " + standbyCount + " 人");
    }
}
```

- [x] **Step 2: patrol() 中调用新方法**

```java
// 旧: checkEmptyBackupPools();
// 新:
checkGlobalPoolLow();
```

- [x] **Step 3: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/worker/PatrolWorker.java
git commit -m "refactor: PatrolWorker 改为检查全局池余量"
```

---

### Task 8: 清理 TransferWorker 和相关代码

**Files:**
- Delete: `worker/TransferWorker.java`
- Modify: `worker/CallbackWorker.java`
- Modify: `config/RedisConfig.java`

- [x] **Step 1: 删除 TransferWorker.java**

```bash
git rm src/main/java/com/bookstore/qrcode/worker/TransferWorker.java
```

- [x] **Step 2: CallbackWorker — 删除步骤⑤**

删除 `handleAddSuccess()` 中第⑤步全部代码（XADD transfer 事件的 try-catch 块）。

```java
// 删除以下代码块：
// ⑤ 发布在职继承事件 → TransferWorker 异步消费
// ...
// if (customerId != null) { ... }
```

更新 Javadoc 中的步骤描述：5 步 → 4 步。

- [x] **Step 3: RedisConfig — 删除 transfer Stream 常量**

删除：
```java
// TRANSFER_STREAM_KEY, TRANSFER_CONSUMER_GROUP, TRANSFER_CONSUMER_NAME 常量
// transferConsumerGroup() bean
```

- [x] **Step 4: 编译验证 + Commit**

```bash
./mvnw compile -q
git add -A
git commit -m "cleanup: 删除 TransferWorker/transfer Stream，继承改为手动触发"
```

---

### Task 9: 更新 AsyncConfig + DailyResetWorker

**Files:**
- Modify: `config/AsyncConfig.java`
- Modify: `worker/DailyResetWorker.java`

- [x] **Step 1: AsyncConfig Javadoc 移除 TransferWorker 引用**

`taskExecutor` 注释改为：`TagWorker、批量导入、企微活码异步同步等`

- [x] **Step 2: DailyResetWorker 调用 GlobalAgentPoolService**

`DailyResetWorker` 中调 `agentBindService.dailyReset()`（已在 Task 3 中改为同时调 `poolService.dailyReset()`），不再需要额外改动。

- [x] **Step 3: 编译验证 + Commit**

```bash
./mvnw compile -q
git add src/main/java/com/bookstore/qrcode/config/AsyncConfig.java
git commit -m "chore: 更新 AsyncConfig Javadoc"
```

---

### Task 10: 数据迁移 + 部署

**Files:**
- Create: `docs/migration-global-pool.sql`（一次性迁移脚本）

- [x] **Step 1: 写迁移脚本**

```sql
-- 将 qr_backup_pool 中的 standby 员工迁移到 global_agent_pool
INSERT INTO global_agent_pool (agent_userid, daily_max, sort_order, status)
SELECT b.agent_userid,
       COALESCE(b.daily_max, 200),
       b.sort_order,
       CASE b.status
           WHEN 'standby' THEN 'standby'
           WHEN 'activated' THEN 'standby'  -- 已激活的回 standby
           ELSE 'standby'
       END
FROM qr_backup_pool b
WHERE b.status IN ('standby', 'activated')
  AND NOT EXISTS (
      SELECT 1 FROM global_agent_pool g WHERE g.agent_userid = b.agent_userid
  );

-- 将每个活码的 active QrAgent 也加入全局池（如果还没在池中）
INSERT IGNORE INTO global_agent_pool (agent_userid, daily_max, sort_order, status)
SELECT qa.agent_userid,
       COALESCE(qa.daily_max, 200),
       0,
       'standby'
FROM qr_agent qa
WHERE qa.status = 'active';

-- 更新 qr_code 的 initial_agent_count
UPDATE qr_code SET initial_agent_count = 1 WHERE initial_agent_count IS NULL;
```

- [x] **Step 2: 构建 + 部署**

```bash
./mvnw clean package -DskipTests -q
scp target/app.jar ubuntu@<YOUR_SERVER_IP>:/opt/bookstore-qrcode/
ssh ubuntu@<YOUR_SERVER_IP> "
  sudo systemctl stop bookstore-qrcode &&
  cp /opt/bookstore-qrcode/app.jar /opt/bookstore-qrcode/app.jar.bak &&
  mv /opt/bookstore-qrcode/app.jar /opt/bookstore-qrcode/app.jar.new &&
  mv /opt/bookstore-qrcode/app.jar.new /opt/bookstore-qrcode/app.jar &&
  # 执行迁移 SQL
  mysql -u root -p'密码' bookstore_qrcode < /opt/bookstore-qrcode/docs/migration-global-pool.sql &&
  sudo systemctl start bookstore-qrcode &&
  sleep 10 &&
  sudo journalctl -u bookstore-qrcode --no-pager -n 30
"
```

- [x] **Step 3: 验证**

```bash
# 检查 Worker 启动日志
ssh ubuntu@<YOUR_SERVER_IP> "sudo journalctl -u bookstore-qrcode --no-pager -n 50 | grep -E '(Worker|全局|GlobalAgent)'"

# 测试：redis-cli XADD 模拟回调，观察轮换日志
```

---

## 验证清单

- [ ] `GlobalAgentPool` 实体编译通过
- [ ] `AgentBindService` 从全局池取人逻辑正确
- [ ] `CallbackWorker` 步骤⑤已删除
- [ ] `TransferWorker.java` 已删除
- [ ] `RedisConfig` 无 transfer 相关常量
- [ ] 迁移 SQL 在测试环境执行成功
- [ ] 部署后 CallbackWorker / TagWorker 正常消费
- [ ] 打满日限后能从全局池自动补人
- [ ] 00:00 日重置后 full 员工恢复 standby
- [ ] 管理后台能查看全局池
- [ ] 手动继承功能正常
