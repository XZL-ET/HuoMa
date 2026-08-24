# 基于场景的接待员分配优化

**日期**: 2026-06-25
**状态**: 待评审

---

## 1. 问题

当前活码创建时接待员分配公式为 `ceil(学生人数 / 100)`，隐含假设 100% 扫码率。实际情况：

- 日常推送（码发到家长群）：约 10% 扫码率
- 家长会（现场集中扫码）：约 60% 扫码率

大量接待员闲置在活码上，造成人力浪费。同时扩容取人时未考虑部门归属，可能给客户匹配到跨地域员工。

## 2. 方案概述

1. **场景预设**：创建活码时选择「日常推送」或「家长会」，不同场景对应不同预期扫码率
2. **新分配公式**：`ceil(学生人数 × 扫码率 / 日限)`，自动计算初始接待员数
3. **部门匹配**：活码绑定企微部门，扩容时优先同部门取人
4. **场景联动阈值**：家长会场景降低 `urgentRatio` 到 70%，提前预加载后备
5. **手动回收**：详情页批量回收闲置接待员回全局池

## 3. 详细设计

### 3.1 场景配置 (application.yml)

```yaml
app:
  scene:
    # 日常推送：二维码发到家长群，长期有效
    daily-push:
      scan-ratio: 0.10
      urgent-ratio: 95       # 预激活阈值（沿用默认）
    # 家长会：现场集中扫码，短期高并发
    parent-meeting:
      scan-ratio: 0.60
      urgent-ratio: 70       # 预激活阈值（更激进，提前拉人待命）
  agent:
    daily-max-default: ${AGENT_DAILY_MAX:100}
```

`scan-ratio` 和 `urgent-ratio` 均可在配置文件中调整，无需改代码。

### 3.2 数据库变更

**qr_code 表新增字段：**

```sql
ALTER TABLE qr_code ADD COLUMN scene ENUM('daily_push','parent_meeting')
    NOT NULL DEFAULT 'daily_push' COMMENT '场景：日常推送/家长会';
ALTER TABLE qr_code ADD COLUMN department_id BIGINT
    COMMENT '所属企微部门ID（用于扩容时同部门优先取人）';
```

**global_agent_pool 表新增字段：**

```sql
ALTER TABLE global_agent_pool ADD COLUMN department_id BIGINT
    COMMENT '员工所属企微部门ID（从Employee同步）';
CREATE INDEX idx_pool_dept ON global_agent_pool(department_id, status, sort_order);
```

### 3.3 实体变更

**QrCode 新增：**
```java
@Enumerated(EnumType.STRING)
private Scene scene = Scene.daily_push;  // 场景枚举

private Long departmentId;               // 所属企微部门ID
```

**Scene 枚举：**
```java
public enum Scene {
    daily_push,      // 日常推送
    parent_meeting   // 家长会
}
```

**GlobalAgentPool 新增：**
```java
private Long departmentId;  // null = 未分配部门，扩容时退化为全局取人
```

**多部门处理**：企微 API 返回的 `department` 是数组（如 `[1,2,3]`），一个员工可属多个部门。取主部门策略：
1. 优先用企微 `main_department` 字段（如有）
2. 否则取 `department` 数组的第一个元素
3. 写入 `GlobalAgentPool.departmentId` 时仅取该主部门

**QrCodeCreateRequest 新增：**
```java
private Scene scene;         // 创建时场景选择
private Long departmentId;   // 所属部门
```

### 3.4 新分配公式

```
接待员数 = ceil(学生人数 × scene.scanRatio / agentDailyMax)
         clamp 到 [1, 100]
```

**计算位置**：`QrCodeService.create()` 第 215-220 行，替换现有 `ceil(studentCount / 100)`。

**示例**（日限 = 100）：

| 学生人数 | 现状 | 日常(10%) | 家长会(60%) |
|---------|------|----------|------------|
| 500 | 5 | **1** (-80%) | 3 (-40%) |
| 1000 | 10 | **1** (-90%) | 6 (-40%) |
| 3000 | 30 | **3** (-90%) | 18 (-40%) |
| 5000 | 50 | **5** (-90%) | 30 (-40%) |

**用户可手动覆盖**：如果用户手动设置了 `initialAgentCount`，以手动值为准。

### 3.5 场景联动阈值

创建活码时，根据场景自动设置 `warnRatio` / `urgentRatio`：

| 场景 | warnRatio | urgentRatio | 效果 |
|------|-----------|-------------|------|
| 日常推送 | 80% | 95% | 接近日限才预激活 |
| 家长会 | 80% | 70% | 提前拉后备上码待命，应对并发高峰 |

预激活（`preActivateBackup`）不标记当前接待员满员，不浪费容量。当前接待员继续用到 100% 后才触发真正的扩容换人。

### 3.6 创建页 UI 改动

新增两个控件，位于学生人数输入框下方：

```
┌──────────────────────────────────────────┐
│  学校人数: [____]                         │
│  场景:     ○ 日常推送（默认）              │
│            ○ 家长会                       │
│  所属部门: [▼ 华中区-武汉分公司]           │
│  预估接待员: 3 人（自动计算，可手动覆盖）   │
└──────────────────────────────────────────┘
```

- **场景**：单选按钮，切换时「预估接待员」实时联动
- **所属部门**：下拉列表，数据来源为企微部门树 API。需在 `WecomApiClient` 中新增 `listDepartments()` 方法（调用企微 `GET /cgi-bin/department/list`）
- **预估接待员**：灰色只读数字，手动填入 `initialAgentCount` 后可覆盖
- 前端根据所选场景自动带出 `scanRatio` 和 `urgentRatio`（通过后端 `/api/config/scene` 接口获取）

### 3.7 部门匹配扩容

**取人优先级**（修改 `GlobalAgentPoolService.takeStandby`）：

```
① 同部门 standby 中按 sort_order 取 —— 命中
② 同部门没有 → 任意部门 standby 按 sort_order 取 —— 降级
③ 全局枯竭 → 告警（现有逻辑不变）
```

部门匹配支持**父子包容**：如活码绑定「华中区」，则华中区及其下所有子部门的员工都视为同部门。实现方式：查企微部门树取所有子孙部门 ID，构造 IN 条件。

**新方法签名**：
```java
public GlobalAgentPool takeStandby(Set<String> excludeUserids, Long preferredDepartmentId)
```
preferredDepartmentId 为 null 时退化为现有行为（全局取人）。

### 3.8 手动回收接待员

**入口**：活码详情页 → 接待员列表区域 →「回收闲置接待员」按钮

**列表展示**（新增今日接待量列）：

```
┌────┬────────┬──────────┬──────┬─────┐
│ ☑  │ 姓名   │ 今/日限   │ 角色 │     │
├────┼────────┼──────────┼──────┼─────┤
│ ☑  │ 张三   │ 3/100    │ 接待 │     │  ← 可回收
│ ☐  │ 李四   │ 85/100   │ 接待 │ 🔄  │  ← 正在用不回收
│ ☐  │ 王五   │ 12/30    │ 服务 │ 🔒  │  ← 服务老师不可回收
└────┴────────┴──────────┴──────┴─────┘
        [回收选中 (2)]
```

**后端 API**：
```
POST /api/qrcodes/{id}/agents/batch-recycle
Body: { "agentIds": [101, 102] }
```

**回收逻辑**：
1. 校验目标 agent 角色不是 `service`（服务老师不可移除）
2. 校验回收后活码上至少保留 1 个 active 接待员（防止全部回收）
3. 将选中的 `QrAgent.status` 更新为 `removed`
4. 将对应 `GlobalAgentPool.status` 恢复为 `standby`
5. 异步同步企微侧联系人列表

### 3.9 批量导入兼容

Excel 批量创建流程新增两列：

| 列 | 字段 | 说明 |
|----|------|------|
| L (11) | scene | daily_push / parent_meeting，默认 daily_push |
| M (12) | departmentId | 企微部门 ID，可选 |

解析代码 `QrCodeService.parseImportRow()` 需同步更新。

### 3.10 存量数据兼容

现有活码没有 `scene` 和 `departmentId`：
- `scene` → 默认 `daily_push`（DDL 已设 DEFAULT）
- `departmentId` → `null`，扩容时退化为全局取人（现有行为）
- 阈值保持现有值不变（warnRatio=80, urgentRatio=95）

## 4. 影响范围

| 文件 | 改动 |
|------|------|
| `application.yml` | 新增 `app.scene` 配置块 |
| `QrCode.java` | 新增 `scene`、`departmentId` 字段 |
| `QrCodeCreateRequest.java` | 新增 `scene`、`departmentId` 字段 |
| `QrCodeService.java` | 修改 `create()` 公式；场景→阈值联动 |
| `GlobalAgentPool.java` | 新增 `departmentId` 字段 |
| `GlobalAgentPoolService.java` | `takeStandby` 增加部门优先逻辑 |
| `QrCodeController.java` | 新增场景配置查询接口、批量回收接口 |
| `schema.sql` | DDL 新增字段 |
| `create.html` | 新增场景选择、部门选择、预估接待员预览 |
| `detail.html` | 接待员列表 + 回收按钮 |
| 批量导入模板/解析 | 新增 scene、departmentId 列 |
| `EmployeeSyncService` | 同步时写入 `departmentId` 到 `global_agent_pool` |
| `WecomApiClient` | 新增 `listDepartments()` 方法 |

## 5. 非目标（本次不做）

- 自动降级：家长会 N 天后自动切回日常 → 后续迭代
- 自动回收：连续低负载自动回收 → 后续迭代
- 动态自适应：基于历史数据自动调整预期扫码率 → 后续迭代
