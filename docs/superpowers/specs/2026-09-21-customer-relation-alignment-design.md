# 火马客户数据对齐企微 — customer_relation 关系表设计

> 状态：待评审
> 日期：2026-09-21
> 关联：[[wecom-customer-count-reconciliation]]、[[wecom-dual-system-callback]]、[[prod-db-access]]

## 1. 背景与问题

火马的 `customer` 表用**单值字段**存企微的**多值事实**，导致数据与企微后台对不上：

| 火马字段（单值） | 企微事实（多值） | 丢失的信息 |
|---|---|---|
| `current_agent` | `follow_user[]`（客户被多个员工添加） | 多归属：客户被第 2 个员工添加后，第 1 个丢 |
| `school_id` | 客户通过多个活码（学校）扫码 | 多校：多孩家庭扫 2 个学校码，第 1 个学校丢 |
| `added_agent` | 多个员工分别添加 | 多添加人：只记最初一个 |

根因：企微的真相是一张「客户 × 员工」的关系表（`follow_user`），火马把它压扁成了 `customer` 表上的几个单值字段。对齐的唯一办法，是**把这张关系表镜像回火马**。

## 2. 目标

建一张 `customer_relation` 表，镜像企微 `follow_user`，一次性解决四个问题：

1. **多归属**：一个客户多行（每个添加他的员工一行）。
2. **多校**：不同接待员通过不同学校活码添加，各记一行、各带 `school_id`。
3. **统计口径**：`customer_relation` active 行数 = 企微后台「未去重客户总数」口径。
4. **继承**：从关系表定位「客户-接待员-学校」，按真实关系转移。

**硬约束：`customer` 表字段与现有逻辑一律不动**，`customer_relation` 作为新增真相源，逐步让下游切换，保证「之前功能不受影响」。

## 3. 核心设计：customer_relation 表

### 3.1 DDL

```sql
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

### 3.2 唯一键语义

`(customer_id, employee_userid)` 对齐企微「客户 × 成员」单关系：

- 客户被不同员工添加 → 多行（多归属）。
- 客户被同一员工通过不同活码添加 → **只有一行**（企微侧本就是单关系，`school_id` 记最近扫码的学校）。这对应「同一接待员多学校」边界：企微无法拆分，火马也无需拆。

### 3.3 实体与仓库

按 CLAUDE.md「No JPA Relationship Annotations」约定，`CustomerRelation` 实体用显式 `Long customerId`、`String employeeUserid`，不用 `@ManyToOne`。仓库提供：

- `Optional<CustomerRelation> findByCustomerIdAndEmployeeUserid(Long, String)`
- `List<CustomerRelation> findByEmployeeUseridAndStatus(String, Status)`
- `List<CustomerRelation> findByCustomerIdAndStatus(Long, Status)`
- `@Query` 继承定位查询（见 §5）

## 4. 数据流

### 4.1 全量同步：CustomerSyncService（新）

**成本关键决策：逐员工拉客户列表，不逐客户拉 follow_user。**

- `getUserList()` → 全部在职员工 userid（1 次 API）。
- 逐员工 `getExternalContactList(userid)` → 该员工的客户 external_userid 数组。
- 汇总为「客户 × 员工」关系全集，反查 `external_userid → customer.id`。
- upsert `customer_relation`（active），对账标记「该员工名下已不在企微列表」的关系为 removed。

成本 ≈ 客户联系成员数（历史对账约 1774 个）× 1 次 API ≈ 1775 次，限频 600/min 下约 3 分钟。可每 30 分钟（参考 `EmployeeSyncService` 的 `0 7,37 * * * *`）或每日跑。

**顺带收益**：同步中发现的「企微有、`customer` 表没有」的 external_userid（历史回调丢失，约 655 个），补建 `customer` 稀疏记录，抬升覆盖率（当前 99.5% → 接近 100%）。稀疏记录只写 `external_userid`（唯一键），其余字段（姓名等）留空，由 `DataFillWorker` 异步补齐；**不额外调 `getExternalContact`**，避免 655 次单查拖慢同步。

**边界**：`getExternalContactList` 只返回 external_userid，不含添加时间与来源活码。故全量同步只能精确得到「关系」维度（customer_id, employee_userid），`add_time` / `qr_code_id` / `school_id` 从现有 `customer` 表近似回填（`customer.add_time` / `source_qr_id` / `school_id`）。

**分页风险**：现 `WecomApiClient.getExternalContactList`（`:705-710`）是单次 GET，无 `next_cursor` 循环。企微对单个成员客户列表可能分页截断（`externalcontact/list` 的官方截断上限未能从文档确认，但企微有分页批接口 `externalcontact/batch/get_by_user`，POST + cursor + limit，每次最多 100 成员）。当前人均客户 ~80，但个别接待员/服务老师可能超限。**实现时必须先实测确认 `externalcontact/list` 单员工是否截断**：若有截断，则全量同步改用分页接口（或对超限员工补 `batch/get_by_user` 翻页）；若确认无截断（返回全量），沿用现有方法即可。此验证是 §8 第 3 步「跑全量同步」的前置检查。

**离职员工处理**：`getUserList` 只取 `status=active` 的员工 userid（对齐 `EmployeeSyncService`），离职员工的 `getExternalContactList` 会返回 60111（userid 不存在）。同步中对单员工调用的 60111 / 84061 错误**静默跳过**（记日志计数，不中断整批），它们多为已离职 userid 失效，不影响 active 员工的关系对账。

**单员工 API 失败必须整体跳过该员工**：`getExternalContactList(userid)` 一旦报错（60111/84061/限流/网络），要**同时跳过该员工的 upsert 和 removed 对账**，绝不能把「调用失败」当成「空列表」——否则限流或网络抖动会把这个员工名下所有 active 关系一次性误标 removed。区分三种情况：成功返回列表 → 正常 upsert + 对账；返回空列表 → 该员工确无客户，标记其名下遗留关系为 removed；调用失败 → 整员工跳过，留待下一轮。

**离职员工的关系清理**：全量同步只处理 active 员工，离职员工的旧关系不在本次对账范围。它们主要靠 `transfer_customer confirmed` 钩子清理（离职继承把客户转给他人时，`from_userid`=离职员工 → 置 removed）；残留的「离职未分配」客户历史对账已确认为 0（§10），本期不做 `get_unassigned_list`。

### 4.2 增量维护（改现有回调链路）

| 事件 | 现有处理 | 新增 |
|---|---|---|
| `add_external_contact` | `upsertFromCallback` 写 customer 单值 | upsert `customer_relation(customer_id, employee_userid=userId, qr_code_id=活码, school_id=state, add_time=now, status=active)` |
| `del_external_contact`（员工删客户） | `handleDelete` 查 follow_user 决定是否 markDeleted | 将 `customer_relation(customer_id, employee_userid=删除人)` 置 `removed` |
| `del_follow_user`（客户删员工） | 仅 `recordCustomerDeletedAgent` 记日报 | 同样将 `customer_relation(customer_id, employee_userid=被删员工)` 置 `removed` |
| `transfer_customer` 结果 confirmed | `updateCustomerCurrentAgent` 更新 `current_agent` | 置 `(customer_id, from_userid)` 为 `removed`，upsert `(customer_id, to_userid)` 为 `active`（follow_user 里 from 被 to 替换）。to_userid 关系行的 `qr_code_id` 取自 `customer_transfer.qr_code_id`（`CustomerTransfer` 实体无 `state` 字段），`school_id` 经 `qrCodeRepo.findById(qrCodeId).map(QrCode::getSchoolId)` 反查，`add_time` = transfer 确认时间 |

增量回调写入的 `qr_code_id` / `school_id` 是精确的（`state` = 学校），逐步覆盖全量同步的近似值。

**实现要点（三处回调都要照做）：**

1. **删客户事件要读 `event.userid`**。`del_external_contact` / `del_follow_user` 的归一化事件都带 `userid`（删除/被删员工，`WecomCallbackController` 把 XML `UserID` → `event.userid`）。现 `CustomerService.handleDelete` 只读 `external_userid`，**需新增读 `event.get("userid")`** 才能定位要置 removed 的关系。
2. **`del_follow_user` 目前不进 `handleDelete`**（`CallbackWorker.handleExternalContactEvent` 只调 `recordCustomerDeletedAgent`）。需在该分支补一行置 removed 的逻辑，不能只记日报。
3. **内部转移的 `add_external_contact`（`state` 为空）不写来源**。企微 transfer 完成后服务老师会收到一条 `state=空` 的 add 回调（非扫码）。此时只确保 `(customer, serviceTeacher)` 关系为 active，**不写 `qr_code_id`/`school_id`**（保持 null 或已有值），否则会用 null 覆盖 transfer-confirmed 钩子写入的值。即：`state` 非空才写来源字段，与现有 `upsertFromCallback` 的 `state != null` 分支一致。

4. **防御性边界（确保新增写盘不回归现有删除链路）**：
   - **先查 customer 存在再写关系**：`del_follow_user` 可能对应从未入过库的客户（回调丢失的 ~655 个），直接写会撞 FK `customer_id → customer.id` 而抛异常。customer 不存在 → 跳过关系写，绝不因此打断现有 `recordCustomerDeletedAgent` 日报记录。
   - **`event.userid` 为空则跳过**：顶层 `del_external_contact`（非 XML 路径）等边界格式可能缺 `userid`，缺了只跳过关系置 removed（全量同步兜底），不崩、不影响 customer 级删除判定。
   - **关系写与 `handleDelete` 同事务**：`handleDelete` 是 fail-closed（瞬时 API 错抛异常回滚重试），关系写须在同一 `@Transactional` 内，保证「API 失败回滚」时关系写一并回滚，不留半成品；`del_follow_user` 分支的关系写用独立 try-catch，失败不回滚日报记录。

5. **transfer confirmed 的关系维护须与 `current_agent` 更新原子**：`updateCustomerCurrentAgent` 在非事务的 `trackResults` 里被调用，每条 `customerRepo.save` 是独立事务。置 removed + upsert active 应和 `current_agent` 更新包进同一个小事务（或明确 best-effort、由全量同步兜底），避免「`current_agent` 已改、关系未改」的半成品。

### 4.3 同步与增量的一致性

- 唯一键 `(customer_id, employee_userid)` 保证两路写入合并到同一行，互不覆盖「关系」维度。
- 增量回调只 upsert，不删除；删除/失效只由 `del_external_contact` 或全量同步对账置 `removed`。
- 全量同步的 removed 对账只针对「该员工名下」的关系，避免误删其他员工的关系（多归属）。

### 4.4 全量同步 vs 增量回调的并发一致性（最关键，必须按此实现）

全量同步跑一次约 3 分钟，期间增量回调持续写入。若实现不严，全量同步会「污染」增量的精确数据，必须遵循三条铁律：

1. **来源字段只在为 null 时近似回填**。全量同步的 `qr_code_id` / `school_id` / `add_time` 来自 `customer` 单值近似，而增量回调写的是精确值（`state`=学校、活码、精确时间）。全量 upsert 对这三个字段一律 `COALESCE(现有值, 近似值)`——即仅当该关系行尚未有来源时才填近似，**绝不覆盖增量已写的精确值**。
2. **removed 对账只针对快照时间戳之前的稳定行**。同步开始先取快照时间戳 `T0`。removed 对账的判定是「该员工名下、`status=active`、且 `updated_at < T0`、又不在企微快照集合里」的行才置 `removed`。`updated_at >= T0` 的行（多为同步期间增量刚 upsert 的新关系）一律不动，留待下次全量同步或 `del_external_contact` 处理。**否则会把刚加好友的关系误判为 removed。**
3. **关系维度由唯一键保护，天然幂等**。两路写 `(customer_id, employee_userid)` 都合并到同一行，`ON DUPLICATE KEY` 不破坏关系本身。增量回调永远不删行（除 `del_external_contact` 定向置 removed），全量同步的 removed 只按规则 2 定向生效，互不覆盖。

## 5. 继承改造

### 5.1 定位：从 customer_relation 查

`InheritanceJob.processTransferWindow` 现有查询：

```
customer WHERE added_agent = :rec AND school_id = :qr.schoolId AND add_time 在窗口
```

改为：

```
customer_relation WHERE employee_userid = :rec AND qr_code_id = :qr.id
  AND status = 'active' AND add_time 在窗口
  （join customer 拿 external_userid）
```

语义变化：`added_agent`（最初添加人，单值）→ `employee_userid`（每次扫码的接待员，多值）；`school_id` 单值 → `qr_code_id`（活码主键，与 §5.2 去重维度一致）。多孩家庭第二个学校（换了接待员）不再漏。

> 注：继承只处理「窗口内新扫码」客户，这些客户的 `qr_code_id` 均由增量回调精确写入（非全量同步近似值），定位准确。
>
> 索引：该查询过滤 `employee_userid + qr_code_id + status + add_time 范围`，每 15 分钟按 (接待员 × 活码) 逐对扫。`idx_employee` 已把单员工缩到 ~83 行（147k/1774），够用；若后续实测慢，再加复合索引 `(qr_code_id, status, add_time)`，本期不强求。

### 5.2 去重维度：7 处 `customer_id` → `(customer_id, qr_code_id)`

| # | 位置 | 现在 | 改法 |
|---|---|---|---|
| 1 | `InheritanceJob:307` Redis 入队去重 | `transfer:queued:{customerId}` | `transfer:queued:{customerId}:{qrCodeId}` |
| 2 | 继承定位查询 NOT EXISTS（`CustomerRepository.findWithoutTransferByAgentAndSchoolIdAndAddTimeBetween` 内 `t.customerId = c.id`） | 仅按 customer_id 排除 | 加 `AND t.qrCodeId = :qrId` |
| 3 | `TransferService:131` Redis 锁（initiate） | `lock:transfer:{customerId}` | `lock:transfer:{customerId}:{qrCodeId}` |
| 4 | `TransferService:161` pending 去重 | `existsByCustomerIdAndStatusIn` | 加 `qrCodeId` 维度 |
| 5 | `TransferService:168` 冷却期 | `existsRecentTerminalByCustomerId` | 加 `qrCodeId` 维度 |
| 6 | `TransferService:663` Redis 锁（retryFailedTransfers） | `lock:transfer:{customerId}` | 与 #3 **同键**，必须同步改为 `lock:transfer:{customerId}:{qrCodeId}`（`qrCodeId` 取自 `t.getQrCodeId()`） |
| 7 | `TransferService:672` pending 去重（retryFailedTransfers） | `existsByCustomerIdAndStatusIn` | 加 `qrCodeId` 维度 |

> **#2 是满足「多校继承」的关键**：若定位查询的 NOT EXISTS 仍只按 `customer_id` 排除，则一个客户在 A 校已 confirmed 转移后，会被 B 校的继承扫描**错误排除**，多校继承就失效了。必须让 A 校的转移记录只挡 A 校的重复入队、不挡 B 校。
>
> **#6 是锁协调一致性的关键**：`initiate` 与 `retryFailedTransfers` 用同一把 `lock:transfer:*` 锁。若只改 #3 不改 #6，两把锁键不同、互不排斥，会出现「同一客户同一学校」被并发发起两次转移。

配套：`customer_transfer` 加 `(customer_id, qr_code_id)` 索引（现有 `idx_customer_transfer` 仅 `customer_id`）。

**历史 `customer_transfer.qr_code_id` 可能为 null**：切换去重维度前，先回填存量 `customer_transfer` 的 `qr_code_id`（据其 `state`/`school_id` 反查活码主键）。若个别行反查不到（活码已删），去重查询对 `qr_code_id IS NULL` 的行降级为仅按 `customer_id` 判定，避免「null ≠ null」导致同一客户被误判为无历史而重复转移。回填是切换 §5.2 去重维度的**前置步骤**（放迁移第 5 步之前）。

### 5.3 `TransferService.initiate` 顺序重排

现 `initiate` 的顺序是：`state` 判空 → Redis 锁（`:130`，按 customer_id）→ pending 去重（`:161`）→ 冷却期（`:168`）→ 反查活码（`:176` `findBySchoolId(state)`）。要按 `(customer_id, qr_code_id)` 加锁和去重，必须把**反查活码提前到 Redis 锁之前**（不是只提前到去重之前），否则锁键拿不到 `qr.getId()`：

新顺序：`state` 判空 → 反查活码 `findBySchoolId(state)`（`qr==null` 即 return，语义不变）→ Redis 锁 `lock:transfer:{customerId}:{qr.getId()}` → pending 去重（含 qrCodeId）→ 冷却期（含 qrCodeId）→ 后续逻辑。

把反查活码提到锁之前是安全的：`findBySchoolId` 是纯读、无副作用，且原「state 无效 → return」的语义在锁外更早触发（反而省去无效 state 的锁开销）。

### 5.4 `current_agent` 的已知副作用（非回归，二期处理）

去重维度改为 `(customer_id, qr_code_id)` 后，多校客户会被发起**两次**转移（A 校、B 校各一次），`updateCustomerCurrentAgent`（单值 `current_agent = toUserid`）会 last-write-wins，`current_agent` 落在哪个老师上不确定。这与现状的单值语义一致（当前多校客户本就只转一次、丢一个学校），只是现在两个都转、主归属随最后确认者。打标签（`autoTag`）走活码不依赖 `current_agent`，不受影响；`search` 按 `current_agent` 筛选、`markTag` 兜底用 `current_agent`，对多校客户本就是「主归属」近似。属 §10 明确「不改 `current_agent` 单值语义」的既有边界，不做进一步处理。

## 6. 统计口径（可选，独立决策）

- 现有 `CustomerService.countTotal()` = `customerRepo.count()`（去重客户数）。
- 对齐企微「未去重客户总数」= `customer_relation` active 行数。
- 建议**本期先做数据对齐，不改 `countTotal`**，避免后台数字突变；口径切换作为二期，等关系表稳定、对账通过后再切。后台「总客户数」与企微 147079 对齐的最终口径即关系表 active 行数。

## 7. 对账与验收

1. **关系表对账**：全量同步后，`customer_relation` 按 customer_id 去重数 ≈ 企微去重客户数（141,673）；active 行数 ≈ 企微未去重关系数（147,079）。
2. **多归属对账**：`customer_relation` 多行（>1 员工）客户数 ≈ 企微多归属比例（~3.6%）。
3. **继承不回归**：切换前，用 `customer_relation` 定位结果 ⊇ 旧 `added_agent + school_id` 定位结果（并集去重后，旧路径能继承的单校客户一条不漏）。
4. **覆盖率**：同步后 `customer` 表补齐回调丢失客户，覆盖率 99.5% → 接近 100%。

## 8. 迁移步骤（顺序执行，每步可回滚）

1. 执行 DDL 建 `customer_relation`（纯新增，不影响现有）。
2. 上线增量写入（§4.2 四处：`add_external_contact` / `del_external_contact` / `del_follow_user` / `transfer_customer confirmed`），开始累积精确来源。
3. 跑一次全量同步（按 §4.4 并发铁律），回填存量「关系」维度 + 仅对 null 近似回填「来源」。
4. 对账（§7），确认关系表覆盖 ⊇ 现有单校继承。
5. 回填 `customer_transfer.qr_code_id`（§5.2 前置），切换继承定位 + 去重维度（§5），灰度观察继承成功率。
6. （二期）切统计口径。

每步独立部署，1-4 步对现有行为零影响。

## 9. 风险与边界

1. **同一接待员多学校**：企微单关系，`customer_relation` 单行，`school_id` 记最近学校。多孩家长扫的第二个学校若恰好同一接待员，继承只能转一个老师（企微硬边界，用户已确认接受「失败走重试/告警兜底」）。
2. **历史来源近似**：存量客户「扫过哪些活码」只有 `customer.school_id` 单值，全量同步回填的来源是近似的；精确来源靠上线后的增量回调逐步累积。
3. **回调丢失**：`add_external_contact` 丢失会导致某条关系缺失，靠全量同步对账兜底。
4. **统计口径二期切换**：后台「总客户数」从去重跳变到未去重，需提前与业务确认口径。
5. **全量同步污染增量数据**：全量同步与增量回调并发，若不按 §4.4 三条铁律（null 才回填来源、removed 只针对 `T0` 前稳定行、唯一键保护关系），会覆盖精确来源或误删刚加好友的关系。这是实现正确性的核心风险。
6. **`getExternalContactList` 可能截断**：单员工客户数超企微上限时，单次 GET 会漏掉该员工的部分客户，导致全量同步把这些客户的遗留关系误标 removed。前置实测确认是否截断（§4.1），必要时改分页接口，绝不把截断当「该员工没有这些客户」。

## 10. 不做的事（明确排除）

- 不改 `customer` 表字段、不删任何现有字段。
- 不做「同一接待员多学校」的关系拆分（企微不支持）。
- 本期不改 `current_agent` 的单值语义（仍作为「主归属」缓存，由现有逻辑维护）。
- 不做离职未分配客户（`get_unassigned_list`，历史对账已确认为 0）。
- **不改手动转移路径的定位查询**：`transferTrigger` / `transferBackfill` / 选择性转移（`QrCodeController` 内 `findByAddedAgentAndSchoolId*`）本期仍用 `added_agent + school_id` 单值模型，多校客户手动触发时仍只看到最近一校。它们都经 `TransferService.initiate`（由 `state` 反查 `qrCodeId`）执行，去重维度改动不破坏它们；是否迁到 `customer_relation` 定位留作后续（与自动继承同一套查询可复用）。
