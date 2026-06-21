# 欢迎语 · 收集表单 · 在职继承 — 设计方案

> 日期：2026-06-21 | 状态：待评审

## 一、现有代码分析（关键约束）

### 1.1 回调处理链路

```
企微回调 → WecomCallbackController (验签+解密+XADD)
  → CallbackWorker (XREADGROUP 消费)
    → handleAddSuccess()
      ① RateLimiterService.recordAdd()        — 速率检测
      ② CustomerService.upsertFromCallback()  — 客户入库
      ③ XADD → TAG_STREAM                     — 发布打标事件（TagWorker 异步消费）
      ④ AgentRotationService.incrementDailyCount() — 日计数+1
```

**设计原则**：不阻塞回调主链路。耗时操作（打标、发消息、在职继承）全部通过独立 Stream + Worker 异步处理。

### 1.2 企微 API 频率限制

| API | 限制 | 影响 |
|-----|------|------|
| `sendMessage` | 每客户每天 1 条主动推送（48h 内有互动则放开） | 欢迎语+表单链接 = 2 条消息，需控制发送节奏 |
| `markTag` | 通用频率限制，超频返回 45009 | TagWorker 已有 50ms 间隔，继续遵循 |
| `transferCustomer` | 通用频率限制 | 批量在职继承必须分批+延迟 |
| `externalcontact/remark` | 通用频率限制 | 改备注需走异步 Worker |

### 1.3 现有实体模式

- 无 JPA 关联注解，全部显式 FK 字段（`Long customerId`, `String agentUserid`）
- JSON 字段用 `columnDefinition = "JSON"` 存储
- 所有实体有 `createdAt` / `updatedAt` + `@PrePersist` / `@PreUpdate`

---

## 二、数据库设计

### 2.1 新增表

#### `form_template` — 表单模板

```sql
CREATE TABLE form_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '模板名称',
    description VARCHAR(500) COMMENT '备注',
    fields JSON NOT NULL COMMENT '字段定义',
    tag_mapping JSON NOT NULL COMMENT '字段→打标/备注映射规则',
    remark_template VARCHAR(500) COMMENT '备注模板, 如 {{child_name}}-{{grade}}{{class}}',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

`fields` JSON：
```json
[
  {"name":"grade","label":"孩子年级","type":"select","required":true,
   "options":["一年级","二年级",...,"高三"]},
  {"name":"class","label":"孩子班级","type":"select","required":true,
   "options":["1班","2班",...,"20班"]},
  {"name":"child_name","label":"孩子姓名","type":"text","required":false},
  {"name":"school_name","label":"学校名称","type":"select","required":false,
   "options_from_tag":true,"tag_group":"学校"}
]
```

`tag_mapping` JSON：
```json
{"grade":"tag", "class":"tag", "school_name":"tag", "child_name":"remark"}
```

#### `form_submission` — 表单提交记录

```sql
CREATE TABLE form_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_template_id BIGINT NOT NULL COMMENT 'FK→form_template.id',
    customer_id BIGINT NOT NULL COMMENT 'FK→customer.id',
    qr_code_id BIGINT COMMENT '来源活码',
    field_data JSON NOT NULL COMMENT '提交数据',
    tags_applied VARCHAR(500) COMMENT '已打标签,逗号分隔',
    remark_updated VARCHAR(500) COMMENT '已设备注',
    submitted_at DATETIME NOT NULL
);
```

#### `qr_code_group` — 活码分组

```sql
CREATE TABLE qr_code_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '分组名称',
    region_city VARCHAR(50) COMMENT '市州',
    region_district VARCHAR(50) NOT NULL COMMENT '县区',
    group_type VARCHAR(20) NOT NULL DEFAULT 'alliance' COMMENT 'alliance=教育联盟',
    default_welcome_text VARCHAR(500) COMMENT '分组默认欢迎语',
    default_form_template_id BIGINT COMMENT 'FK→form_template.id',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

### 2.2 修改现有表

#### `qr_code` 新增字段

```sql
ALTER TABLE qr_code ADD COLUMN form_template_id BIGINT COMMENT 'FK→form_template, null=无表单';
ALTER TABLE qr_code ADD COLUMN welcome_text VARCHAR(500) COMMENT '欢迎语, null=继承分组/系统默认';
ALTER TABLE qr_code ADD COLUMN group_id BIGINT COMMENT 'FK→qr_code_group, null=未分组';
```

#### `system_config` 新增配置项

```sql
INSERT INTO system_config (config_key, config_value) VALUES
('default_welcome_text', '{{school_name}}家长您好～欢迎加入XX书店家校服务！');
```

### 2.3 实体设计（遵循项目现有模式）

实体全部使用显式 FK 字段，无 `@ManyToOne` / `@OneToMany`：

```java
// FormTemplate.java — 与现有 QrCode 等实体模式一致
@Entity
@Table(name = "form_template")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String description;
    @Column(columnDefinition = "JSON")
    private String fields;          // JSON 字符串存储
    @Column(columnDefinition = "JSON")
    private String tagMapping;
    private String remarkTemplate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @PrePersist void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
```

---

## 三、配置继承链

```
活码.welcome_text / form_template_id
  ↓ null 时
活码所属 QrCodeGroup.default_welcome_text / default_form_template_id
  ↓ null 时（或未分组）
SystemConfig.default_welcome_text（系统全局默认）
```

---

## 四、核心流程设计

### 4.1 欢迎语+表单发送（异步，不阻塞回调主链路）

```
CallbackWorker.handleAddSuccess()
  │ （现有步骤①~④不变）
  │
  └─ 新增⑤ XADD → OUTBOUND_STREAM
        event: {type:"welcome_and_form", external_userid, userid, state, customer_id}

OutboundMsgWorker (新增, XREADGROUP 消费)
  │
  ├─ ① 解析欢迎语：活码.welcome_text → 分组默认 → 系统默认
  │     模板变量替换：{{school_name}} {{teacher_name}}
  │
  ├─ ② 调 sendMessage(sender=employee, customer=externalUserid, text=欢迎语)
  │     等待 300ms（防企微频率限制）
  │
  ├─ ③ 如果活码绑定表单模板：
  │     构造 H5 URL: https://{domain}/form/{qrCodeId}?c={customerId}
  │     调 sendMessage(sender=employee, customer=externalUserid,
  │                     text="请填写孩子信息👇 {form_url}")
  │
  └─ ④ 如果 sendMessage 失败（客户当天已收过消息）：
        静默跳过，记录日志（不影响主流程）
```

**企微消息限制处理**：
- `sendMessage` 每客户每天 1 条主动推送。但客户刚扫码添加好友，处于 48h 活跃窗口内，可发送多条。
- 两条消息间隔 300ms，防止企微服务端队列丢弃。
- 如果第一条成功第二条失败，欢迎语已送达，表单链接丢失——可接受（客户可在聊天记录中找回，或由老师后续手动发送）。

### 4.2 H5 表单填写与提交

```
客户点击表单链接 → GET /form/{qrCodeId}?c={customerId}
  → 服务端校验：qrCodeId 有效、customerId 有效
  → 读活码 → form_template_id → FormTemplate.fields
  → 动态渲染表单 HTML（独立布局，无需登录）

客户填写提交 → POST /api/form/submit
  → ① 保存 FormSubmission 记录
  → ② XADD → TAG_STREAM（复用现有打标通道）
       event: {type:"form_submit", external_userid, userid, 
               form_template_id, field_data, tag_mapping, remark_template}
       → TagWorker 消费 → TagService 扩展支持：
         - 调用新增方法 applyFormTags()：
           a. 按 tag_mapping 逐字段打标签（企微 markTag API）
           b. 按 remark_template 拼接备注文本
           c. 调企微 externalcontact/remark API 改备注
         - 打 CustomerTag(source=form)
```

### 4.3 在职继承

**自动触发**：新增 `InheritanceJob` 定时任务（每天 02:00）：

```
遍历所有活跃活码：
  查该活码下 role=receptionist 的员工
  查这些员工名下当日新增客户（按 Customer.addedAgent + addTime 当天）
  查该活码的 role=service 员工
  → 逐条 XADD → TRANSFER_STREAM（每条一个事件）
     event: {customerId, fromUserid, toUserid, externalUserid, state}
```

**手动触发**：

```
活码详情页 → 点击「执行在职继承」
  → GET /api/qrcodes/{id}/transfer/preview 返回待转移客户数
  → 管理员确认 → POST /api/qrcodes/{id}/transfer/trigger
    → 同上逻辑，XADD 到 TRANSFER_STREAM，返回任务摘要
```

**TransferWorker**（改造现有 TransferMonitorWorker）：

```
XREADGROUP 消费 TRANSFER_STREAM
  → 调 TransferService.initiate() 发起单条继承
  → 每条间隔 200ms（防企微频率限制）
  → trackResults() 仍由现有 TransferMonitorWorker 定时轮询
```

---

## 五、新增/修改 Worker 清单

| Worker | Stream | 线程数 | 间隔 | 说明 |
|--------|--------|--------|------|------|
| **OutboundMsgWorker** (新增) | `outbound:stream` | 4 | 300ms 两条消息间 | 异步发送欢迎语+表单链接 |
| TagWorker (扩展) | `tag:stream` | 8 | 50ms | 新增表单提交打标+备注处理 |
| **TransferWorker** (改造) | `transfer:stream` | 2 | 200ms | 从 TransferMonitorWorker 改造成 Stream 消费者 |
| TransferMonitorWorker (保留) | — | 1 | 10min | 仅轮询 pending_confirm 结果 |

---

## 六、API 设计

### 6.1 表单模板管理（管理后台）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/form-templates` | 模板列表页 |
| POST | `/admin/form-templates/create` | 创建模板 |
| GET | `/admin/form-templates/{id}/edit` | 模板编辑页 |
| POST | `/admin/form-templates/{id}/update` | 更新模板 |
| POST | `/admin/form-templates/{id}/delete` | 删除模板 |

### 6.2 分组管理（管理后台）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/groups` | 分组列表页 |
| POST | `/admin/groups/create` | 新建联盟 |
| POST | `/admin/groups/{id}/update` | 编辑联盟 |
| POST | `/admin/groups/{id}/delete` | 删除联盟 |

### 6.3 H5 表单（客户侧，无需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/form/{qrCodeId}` | 表单填写页 |
| POST | `/api/form/submit` | 提交表单 JSON |
| GET | `/form/success` | 提交成功页 |

### 6.4 欢迎语配置

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/qrcodes/{id}/welcome` | 更新单个活码的欢迎语+表单模板 |
| POST | `/qrcodes/batch-config` | 批量设置（多选活码） |

### 6.5 在职继承

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/qrcodes/{id}/transfer/preview` | 预览待转移客户数 |
| POST | `/api/qrcodes/{id}/transfer/trigger` | 手动触发继承 |
| GET | `/qrcodes/{id}/transfers` | 继承记录列表页 |

### 6.6 活码列表增强

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/qrcodes` | 现有列表，增强：左侧分组树 + 分组列 + 批量配置 |
| GET | `/api/qrcodes/tree` | JSON，市州→县区→联盟→活码树结构 |

### 6.7 WecomApiClient 新增方法

```java
// 修改客户备注
public void updateRemark(String userId, String externalUserid, String remark)
// POST /cgi-bin/externalcontact/remark
// {"userid":"...", "external_userid":"...", "remark":"..."}
```

---

## 七、前端页面清单

| 页面 | 路径 | 说明 |
|------|------|------|
| **活码列表（增强）** | `qrcode/list.html` | 左侧分组树 + 右侧分组列 + 多选批量配置 |
| **活码详情（增强）** | `qrcode/detail.html` | 新增：欢迎语编辑 + 表单模板选择 + 在职继承入口 |
| **表单模板列表** | `admin/form-templates.html` | 模板 CRUD |
| **表单模板编辑** | `admin/form-template-edit.html` | 字段设计器（添加/删除/排序字段） |
| **分组管理** | `admin/groups.html` | 联盟列表，含默认欢迎语+表单模板设置 |
| **H5 表单填写** | `form/fill.html` | 客户侧，独立布局，无需登录 |
| **H5 提交成功** | `form/success.html` | 提交后展示 |
| **在职继承记录** | `qrcode/transfers.html` | 继承历史，含状态/时间/结果 |

---

## 八、企微 API 限流保护策略

| 场景 | 保护措施 |
|------|------|
| 欢迎语+表单发送 | 两条消息间隔 300ms；失败静默跳过不重试（避免骚扰客户） |
| 表单提提交打标 | 复用 TagWorker 现有 50ms 间隔 + 8 线程并行 |
| 改备注 API | 打标和备注合并为一个 Worker 处理，共用间隔 |
| 批量在职继承 | 每条 transfer 间隔 200ms，2 线程消费 |
| 全局限流 | 所有 Worker 遇 45009 走 WAIT_AND_RETRY 分支（已有逻辑） |

---

## 九、定时任务清单

| 任务 | 频率 | 说明 |
|------|------|------|
| `InheritanceJob` | 每天 02:00 | 自动执行在职继承（XADD 到 TRANSFER_STREAM） |
| TransferMonitorWorker | 每 10 分钟 | 轮询 pending_confirm 的继承结果（保留现有） |

---

## 十、实现顺序

| 阶段 | 内容 |
|------|------|
| Phase 1 | DB migration + 实体/Repository + WecomApiClient.remark API |
| Phase 2 | FormTemplate CRUD 后端 + H5 表单渲染/提交 + 打标备注链路 |
| Phase 3 | OutboundMsgWorker + 欢迎语配置（单活码+批量）+ 分组管理 |
| Phase 4 | 在职继承（TransferWorker + InheritanceJob + 手动触发 + 记录查看） |
| Phase 5 | 活码列表页增强（分组树 + 批量配置）+ 详情页增强 |
