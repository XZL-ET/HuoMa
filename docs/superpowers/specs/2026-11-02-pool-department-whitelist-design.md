# 全局员工池部门准入白名单 — 设计

日期：2026-11-02
状态：待确认

## 1. 背景与目标

当前全局员工池 `global_agent_pool` 的入池完全自动：`EmployeeSyncService.syncToGlobalPool()`
把「在职 + 企微状态可用 + 不是纯服务老师」的员工全量灌入池中，唯一的部门信息
`global_agent_pool.department_id`（取员工主部门）只用于补员时「同部门优先」，
没有任何部门层面的准入控制。

企微通讯录里存在与客户接待无关的部门（后勤、财务、教研等），其员工可能因为
没有任何活码绑定而被判定为 `receptionist` 资格，进而自动进池、被分配到活码上承接客户。

**目标**：管理员可在后台圈定一批「允许承接客户」的企微部门。启用后：

1. 只有所属部门命中允许范围的员工才能进入全局池（新增入池、熔断恢复、自动解封、巡检补人）。
2. 已进池的无关部门员工，以及已上码的无关部门**接待员**，通过一次「预览 → 确认」操作清理。
3. 服务老师 / 双角色的活码绑定不受影响。

**成功标准**：白名单启用后，自动补员不再取到无关部门员工；被撤下的接待员由允许部门员工补位；
没有活码因为清理变成零成员；未点「确认应用」前不产生任何数据变更。

## 2. 非目标与反目标

非目标：

- 不改变服务老师 / 双角色（`QrAgent.role = service/dual`）的活码绑定，不处理在职继承。
- 不改变「同部门优先取人」逻辑：`qr_code.department_id` 仍是优先级信号，不是硬约束。
- 不做部门级配额、不做多个分区池。
- 不处理已加好友的客户关系，不追溯历史客户归属。
- 不追溯清理已离职、已停用员工的既有数据。

反目标（技术上可行但算失败）：

- 清理导致某个活码在企微「联系我」里的成员列表为空（企微要求至少 1 名成员）。
- 误撤服务老师 / 双角色，破坏在职继承。
- 部门树拉取失败时仍执行撤人，把父部门下的子部门员工一起误撤。
- 预览或页面加载阶段就写库。
- 白名单存成空集合后语义退化为「谁都不许入池」。

## 3. 术语

| 术语 | 含义 |
|------|------|
| 白名单 | `pool_allowed_department` 表中的部门集合 |
| 允许部门集合 | 白名单部门 + 其全部子孙部门 |
| 部门命中 | 员工 `Employee.department` JSON 数组中**任意一个**部门 ∈ 允许部门集合 |
| 无关员工 | 白名单启用后，部门不命中的在职员工 |
| 启用态 | 白名单表中至少有一条记录；空表 = 未启用 = 不限制 |

## 4. 现状（相关代码位置）

| 位置 | 作用 | 本次影响 |
|------|------|----------|
| `EmployeeSyncService.syncToGlobalPool()` | 全量补池（手动按钮 + 巡检） | 追加部门过滤；移出已漂移的池记录 |
| `GlobalAgentPoolService.ensureInPool()` | 单点入池（恢复、自动解封） | 入池前拦截 |
| `GlobalAgentPoolService.takeStandby()` | 从池取人（轮换/预激活/补位） | 取人时做防御性判定 |
| `QrCodeService.addAgent()` | 手动向活码添加接待员 | 拒绝无关员工 |
| `QrCodeService.bindAgents()` | 创建活码时初始上码 | 手动指定者按 service 绑定，按策略留口子（见 7.3） |
| `AgentRotationService` | 日限扩容 / 预激活 / 补员 | 只从池取人；`takeStandby` 已防御 |
| `AgentController.restore()` | 恢复熔断/停用员工并重新激活 `removed` 绑定 | 按白名单过滤，不复活无关员工 |
| `DailyResetWorker.recoverFullAgents()` | 每日 00:00 把 `full` 复活为 `active` | 永久护栏：无关接待员不复活 |
| `PatrolWorker.checkGlobalPoolLow()` | 池余量巡检 + 告警 | 按「可入池上限」抑制误报 |
| `DepartmentService` | 企微部门 ID → 名称映射（Caffeine，30 分钟） | 新增部门树加载 |
| 企微「联系我」contact_way | 多成员模式，成员列表不能为空 | 撤人前必须保证至少补 1 人 |

## 5. 数据模型

新表 `pool_allowed_department`（在 `schema.sql` 中用幂等 `CREATE TABLE IF NOT EXISTS` 建表）：

```sql
CREATE TABLE IF NOT EXISTS pool_allowed_department (
    dept_id     BIGINT       NOT NULL PRIMARY KEY COMMENT '企微部门ID',
    dept_name   VARCHAR(100) NOT NULL COMMENT '部门名称快照（展示与审计用）',
    created_by  VARCHAR(100) COMMENT '创建人',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_allowed_dept_name (dept_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局员工池允许入池的企微部门';
```

- **空表 = 未启用**（不限制入池），避免上线即改变现有行为。
- 有记录 = 启用态，且 UI 不允许保存空白名单；停用走单独的「清空白名单并停用」操作。
- `dept_name` 只是快照，页面展示优先用企微实时部门名。

## 6. 判定规则

统一由新增的 `DepartmentWhitelistService` 提供判定，所有闸口调用同一套逻辑。

1. **空名单**：`isActive() == false`，所有员工允许，现有行为不变。
2. **白名单读取**：每次操作直接从 `pool_allowed_department` 表读取（表极小，不走跨节点本地缓存），
   保证 ALB 双节点实时一致；保存后立即对两台生效。
3. **允许集合展开**：调用 `WecomApiClient.listDepartments(null)` 一次拉取全量部门
   （响应含 `id` / `name` / `parentid`），在内存中按 `parentid` 递归展开白名单部门的子孙部门。
   部门树走现有 Caffeine 缓存（与 `departments` 同级，TTL 30 分钟）；允许集合本身按单次操作计算，
   不做实例级长缓存。
4. **部门树 API 失败时分模式处理**：
   - `preview` / `apply`（撤人）：**整体中止**并提示「部门树拉取失败，请稍后重试」，不执行任何变更。
   - 日常入池 / 取人 / 添加接待员：降级为「精确匹配白名单 ID」，记 WARN。降级只会少放人，不会多放人。
5. **员工匹配**：解析 `Employee.department`（JSON 数组，含员工全部部门），
   **任意一个**部门命中即允许。不看 `global_agent_pool.department_id`（那是主部门快照）。
6. **无员工记录 / 无部门数据**：启用态下一律不允许，并在预览中单列出来提醒。
7. **兼属多部门**：任一命中即允许（已与需求方确认）。
8. **`Employee.department` 陈旧窗口**：员工部门每 30 分钟从企微同步，判定使用最新落库值，
   最多可能滞后一个同步周期，属于可接受误差。

### 批量判定

`syncToGlobalPool`、`takeStandby` 等批量场景一次读取白名单 + 一次取员工部门，
避免逐人查询/调用企微 API。

## 7. 入池与取人闸口

### 7.1 入池

| 入口 | 处理 |
|------|------|
| `syncToGlobalPool()` | 在现有「企微状态 / 封禁熔断 / 纯服务老师」过滤之后追加部门过滤 |
| `ensureInPool(userid, dailyMax)` | 开头判定，不命中直接 return（幂等，不创建池记录） |
| 巡检自动补人 | 走 `syncToGlobalPool`，自动生效 |
| `QrCodeService.addAgent()` | 不命中 → 抛业务异常「该员工所属部门不在部门准入范围内」 |
| `QrCodeService.addBackup()` | 走 `ensureInPool`，自动生效 |
| `EmployeeSyncService.autoUnblockRecoveredAgents()` | 走 `ensureInPool`，自动生效 |

注意：`ensureInPool` 被拦截只影响「入池」，不影响 `Agent` 主数据的存在性——
`QrCodeService` 另有独立的 `ensureAgent()` 负责主数据，二者不要混淆。

### 7.2 取人防御

`takeStandby()`（含同部门分支 `selectFromDeptCandidates`）在遍历候选时追加判定：
启用态下候选员工不命中 → 从池中物理删除并继续（复用现有懒清理模式）。
这层防护覆盖存量漂移：员工调入无关部门后，即使池记录尚未清理，也不会再被取走。

### 7.3 创建活码时的已知口子（接受的策略缺口）

`bindAgents()` 中管理员手动指定的初始成员一律按 `service` 角色绑定，
本次策略是「服务老师不动」，因此**新建活码时手动指定的无关部门员工不会被拦截、也不会被清理**。
这是有意接受的口子：手动指定服务老师是管理员对单个学校的明确决定，不属于自动分配。
文档明确记录，不在本次加校验。

### 7.4 巡检告警口径

白名单会压缩可入池人群，`PatrolWorker.checkGlobalPoolLow()` 的固定阈值（<10 补人、=0 告警、<5 告警）
可能长期误报。调整：

- 计算「可入池上限」= 允许部门内、在职、企微可用、非纯服务、且不在池中的员工数。
- 补池后仍不足且「可入池上限 = 0」：只记 INFO，不告警（已经无法补充，告警只会制造噪音）。
- 仍需告警时，文案附带「受部门准入限制，可入池上限 N 人」，便于判断是配置问题还是容量问题。

## 8. 活码撤人（仅接待员）

**范围**：`QrAgent.role = receptionist` 且员工部门不命中的绑定。
`role = service / dual` 一律不动（已确认：只撤接待员、服务老师不动；dual 含服务职责，按服务老师处理）。

| 绑定状态 | 处理 |
|----------|------|
| `active` | 先保证活码至少剩 1 名 active 成员，再标 `removed`（流程见下） |
| `full` | 直接标 `removed`（不在 contact_way 的 active 列表里，不需要补员） |
| `blocked` | 不动（本来就不承接客户）；其重新激活只经 `restore`，已在 `restore` 加白名单过滤 |
| `removed` | 不动 |

`full` 必须处理的原因：`DailyResetWorker.recoverFullAgents` 每日 00:00 会把 `full → active`，
不改就会第二天自己回到码上。

### 8.1 撤 active 接待员

逐活码处理，持 Redis 锁 `ROTATE_LOCK_PREFIX + qrId + ":rotate"` 与日限轮换串行：

```
D = 该活码待撤的 active 接待员集合
activeTotal = 该活码当前 active 成员总数（含服务老师）
剩余 = activeTotal - |D|
若 剩余 >= 1：
    逐个把 D 标 removed
否则：
    补员数 = 1 - 剩余   （保住 1 名成员所需人数）
    从允许池调用 takeStandby(exclude=该码所有未 removed 的 userid,
                          preferred=qr.departmentId) 依次补足
    补不齐 → 整码放弃，保留 D 全部成员，结果标记「未能移除：全局池枯竭」
    补齐 → 把 D 标 removed
```

### 8.2 每日重置永久护栏

`DailyResetWorker.recoverFullAgents()` 的 `full → active` 循环追加判定：
- `receptionist` 且部门不命中 → 标 `removed`，不复活；
- 其余角色照旧。

这样即使 `apply` 当时因锁占用/池枯竭跳过了某个活码，次日也不会出现无关接待员复活。

### 8.3 事务与提交边界

| 步骤 | 事务 |
|------|------|
| 保存白名单 + 批量移出池记录 | 一个短事务（纯 DB，批量） |
| 逐活码补员 + 标 `removed` | 每个活码独立 `REQUIRES_NEW` 事务（调用独立的 bean 方法，避免自调用失效） |
| 企微同步 | 事务提交后异步执行（复用 `AgentRotationService.syncQrCodeToWechatAsync`），不阻塞页面 |

- 幂等：重复执行 `apply` 收敛；只在实际状态发生变化时写 `QrRotateLog`。
- 中途失败：白名单已保存、已处理的活码保持结果；未处理的活码可由下次 `apply` 继续收敛，
  结果页展示未处理清单。

### 8.4 失败与并发

| 情况 | 行为 |
|------|------|
| 全局锁 `lock:pool:dept-whitelist` 被占用 | 整体拒绝，提示稍后重试 |
| 部门树拉取失败（preview/apply） | 整体中止，不写库 |
| 某活码 rotate 锁被占用 | 跳过该活码，结果标记「轮换进行中，未处理」 |
| 允许池枯竭、保不住 1 名成员 | 保留原成员，结果标记「未能移除」 |
| 企微同步失败 | 本地已移除，结果标记「企微同步失败」，需人工重试 |

## 9. 预览与确认流程

页面：`GET /agents/departments`（员工管理页顶部视图切换加入口：池 / 全量 / 部门准入）。

- 展示企微部门树，多选。勾选父部门时展示「含 N 个子部门」。
- 展示当前白名单及启用状态。
- 按钮一：**预览影响** → `POST /agents/departments/preview`，只读，返回：
  - 将被移出池的人数 + 名单（姓名、主部门、池状态、日用量）
  - 将被撤下的活码接待员：按活码分组（活码名、待撤名单、撤后是否需补位、补位依赖池）
  - 无部门员工名单（启用态下不允许）
  - 服务老师 / 双角色不影响的提示
- 按钮二：**确认应用** → `POST /agents/departments/apply`，请求体为部门 ID 列表；
  服务端重新计算并执行：先保存白名单，再移出池，再逐活码撤人；返回汇总结果
  （保存的部门数、移出池数、撤码成功/失败、未处理原因、待异步同步的活码数）。
- **确认之前不产生任何变更**：`preview` 只读，`apply` 是唯一的写入入口。
- 部门树拉取失败时，前端禁用「确认应用」。
- 「清空白名单并停用」：单独按钮 + 二次确认，只删除白名单记录，不改动任何池/活码数据。
- 预览不追求 100% 精确（池状态实时变化），以 `apply` 时的实时判定为准；页面明示这一点。
- 配置页额外展示「漂移提醒」：当前仍在活码上、但部门已不在允许范围的接待员数量，
  引导再次预览/应用。日常同步不会自动从活码撤人。

## 10. 接口与代码结构

| 文件 | 变更 |
|------|------|
| `entity/PoolAllowedDepartment.java` | 新增实体 |
| `repository/PoolAllowedDepartmentRepository.java` | 新增仓储 |
| `service/DepartmentWhitelistService.java` | 新增：启用判定、允许集合展开、单/批量员工判定、白名单增删、严格/降级两种模式、可入池上限统计 |
| `service/DepartmentService.java` | 新增 `loadDeptTree()`（`id → name,parentid`，`@Cacheable`） |
| `service/EmployeeSyncService.java` | `syncToGlobalPool` 部门过滤 + 移出已漂移池记录 |
| `service/GlobalAgentPoolService.java` | `ensureInPool` 拦截；`takeStandby` / `selectFromDeptCandidates` 防御性判定 |
| `service/QrCodeService.java` | `addAgent` 拒绝；新增按活码撤接待员方法 |
| `service/DepartmentWhitelistApplyService.java` | 新增：apply 编排、逐码 `REQUIRES_NEW` 事务、结果汇总 |
| `worker/DailyResetWorker.java` | `recoverFullAgents` 白名单永久护栏 |
| `worker/PatrolWorker.java` | 按可入池上限抑制误报告警 |
| `controller/AgentController.java` | 新增 `/agents/departments`、`/preview`、`/apply`、`/disable`；`restore` 过滤 |
| `templates/agent/list.html` | 视图切换加入口 |
| `templates/agent/departments.html` | 新增配置页 |
| `resources/schema.sql` | 幂等建表 |
| 测试 | 见第 12 节 |

权限沿用现有后台安全配置（`/agents/**` 已有保护），不新增角色模型。

## 11. 审计

- `OperationLog`：一条汇总，记录操作人、部门数、移出池人数、撤码数、失败数。
- `QrRotateLog`：每个被撤的 active 接待员一条。
- 日志：白名单判定降级、撤人过程、企微同步失败记录 WARN/ERROR。

## 12. 测试

单元测试：

- 判定：空名单放行；子孙部门命中；兼属多部门任一命中；无部门不允许；部门树失败时日常闸口降级为精确 ID。
- `syncToGlobalPool`：无关员工不入池；已在池的无关员工被移出。
- `ensureInPool`：拦截无关员工，且不创建池记录。
- `takeStandby`：候选无关员工被懒清理且跳过。
- `QrCodeService.addAgent`：拒绝无关员工。

集成测试：

- `apply` 端到端：池记录移出、active 接待员撤下并补位、`full` 接待员 → `removed`、
  服务老师保留、池枯竭时保留原人、企微同步被调用、审计记录落库。
- 活码只剩 1 名无关接待员且池有可用人 → 先补后撤，最终 active 非空。
- 活码只剩 1 名无关接待员且池枯竭 → 不撤，结果标记失败。
- 部门树失败时 `preview/apply` 中止且不写库。
- `restore` 不复活无关员工的 `removed` 绑定。
- `DailyResetWorker`：无关接待员 `full` 不复活，其余正常复活。
- `PatrolWorker`：可入池上限为 0 时不再发池枯竭告警。

控制器测试：`preview` 返回结构与数量正确；`apply` 参数校验。

## 13. 上线与迁移

- 建表语句幂等，空表默认不限制，上线本身零行为变化。
- 管理员配置时必须先预览再确认，才执行清理。
- 建议上线后在测试环境确认：父部门勾选是否带子孙、兼属多部门员工判定正确、
  至少 1 名成员的保护生效、双节点白名单即时一致。

## 14. 实现取舍（builder 可直接按此执行）

1. `dual` 视为服务老师，不撤；`blocked` 绑定不动。
2. 无关接待员的 `full` 绑定直接标 `removed`，不需要补员；每日重置有永久护栏。
3. `addAgent` 拒绝无关员工；`restore` 过滤无关员工绑定。
4. 部门树失败：`preview/apply` 中止，日常闸口降级为精确白名单 ID（保守）。
5. 无部门员工在启用态下不允许。
6. 日常同步会移出「已漂移」的池记录，但**不会自动从活码撤人**；活码上的漂移由配置页提醒、人工再次 apply。
7. 预览允许与实际执行有偏差，以 `apply` 时实时判定为准。
8. 白名单以 DB 为准，不做跨节点本地强缓存；部门树沿用 30 分钟 Caffeine。
9. 企微同步异步执行，`apply` 同步返回 DB 处理结果与待同步活码数。
10. 新建活码时手动指定的初始成员（按 `service` 绑定）不在本次限制范围内，属已知策略缺口。
11. 巡检告警按「可入池上限」抑制误报。
