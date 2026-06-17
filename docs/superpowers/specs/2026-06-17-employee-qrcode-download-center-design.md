# 员工活码下载中心 设计文档

**日期**: 2026-06-17
**状态**: 待审批
**范围**: 火马平台（现有 Spring Boot 项目内集成）

---

## 一、概述

### 1.1 目标

在火马平台现有项目中新增「员工活码下载中心」模块，企微员工通过企微 OAuth 登录后：
- 浏览和搜索全部活码（默认展示自己绑定的活码）
- 下载活码二维码图片
- 查看个人下载历史
- 点击活码负责人姓名唤起企微 Profile 发起联系

管理员在后台可查看全局下载统计（含未下载标记），并配置各区县的负责人。

### 1.2 非目标

- 不做独立部署，全部集成在现有 Spring Boot 项目中
- 不修改现有活码实体结构
- 不改变现有管理后台的认证体系

---

## 二、数据库设计

### 2.1 新增表：`qr_download_log`（下载日志）

每次下载写入一条新记录（非 upsert），下载次数通过 COUNT 聚合计算。

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK AUTO_INCREMENT | 主键 |
| qr_code_id | BIGINT | NOT NULL, FK → qr_code.id | 被下载的活码 |
| agent_userid | VARCHAR(100) | NOT NULL | 下载员工的企微 userid |
| downloaded_at | DATETIME | NOT NULL | 下载时间 |
| ip_address | VARCHAR(50) | NULL | 下载来源 IP |

索引：
- `idx_log_qrcode (qr_code_id)` — 按活码统计下载
- `idx_log_userid (agent_userid)` — 按员工查询个人记录

查询"未下载"逻辑：取活码绑定员工（QrAgent）集合，LEFT JOIN 本表，IS NULL 即为未下载。
同一员工同一活码多次下载 → 多条记录，下载次数 = COUNT(*)。

### 2.2 新增表：`district_manager`（区县负责人）

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK AUTO_INCREMENT | 主键 |
| region_city | VARCHAR(50) | NOT NULL | 城市 |
| region_district | VARCHAR(50) | NOT NULL | 区/县 |
| manager_userid | VARCHAR(100) | NOT NULL | 负责人企微 userid |
| manager_name | VARCHAR(100) | NOT NULL | 负责人姓名（冗余展示） |
| created_at | DATETIME | NOT NULL | 创建时间 |
| updated_at | DATETIME | NOT NULL | 更新时间 |

唯一约束：`UNIQUE (region_city, region_district)`

负责人通过 region_city + region_district 与 QrCode 自动关联，无需在活码层面额外配置。

---

## 三、认证：企微 OAuth 登录

### 3.1 流程

```
1. 员工在企微内点击工作台应用入口
2. 后端构造 OAuth URL（corpid + redirect_uri → /download/oauth/callback）
3. 企微回调，附带 code 参数
4. 后端用 code 调企微 API 获取 userid
5. 查询 Employee 表校验员工在职
6. 写入 HttpSession，标记为已认证（角色：ROLE_EMPLOYEE）
7. 重定向到 /download 主页
```

### 3.2 Security 配置

在 Spring Security 中新增一条规则，不影响现有 admin/operator 的登录体系：

```java
.requestMatchers("/download/**").hasRole("EMPLOYEE")
.requestMatchers("/download/oauth/**").permitAll()
```

`/download/**` 走 Session 验证，`/admin/**` 保持现有 admin/operator 体系不变，两者共存同一 JVM 中。

### 3.3 复用

- 企微 corpid/secret 从现有 `WecomConfig` 读取
- 员工身份校验复用现有 `EmployeeRepository.findByUserid()`

---

## 四、前端页面

### 4.1 活码下载中心 `/download`

**布局**：独立精美风格，使用独立的轻量布局模板（不含管理后台导航栏），仅保留顶部品牌标识和用户信息。卡片网格为主视觉。

**顶部搜索区**：
- 居中搜索框（搜索学校名称/城市/区县）
- 切换开关：「我的活码」←→「全部活码」
- 负责人筛选下拉框（可选）

**卡片网格** (4 列 → 平板 2 列 → 手机 1 列)：
```
┌──────────────────────────┐
│ 🏫 XX实验中学             │
│ 📍 广州 · 天河            │
│ 👤 负责人：张三 (可点击)   │
│                          │
│   [活码预览缩略图]        │
│                          │
│   [⬇ 下载高清图]  ✓ 已下载 │
└──────────────────────────┘
```

**交互细节**：
- hover 上浮 4px + 阴影增强，过渡 0.2s
- 已下载卡片：左上角绿色 ✓ 角标
- 下载按钮点击：按钮变绿 + ✓ 弹出动画 + 全局 toast 提示"下载成功"
- 负责人名字：渲染为 `<a href="wxwork://openconversation?userid={manager_userid}">`，企微内点击唤起 Profile
- 该活码无负责人时负责人行不展示

**分页**：htmx 驱动，底部加载更多或传统分页

### 4.2 我的下载记录 `/download/history`

展示当前员工每次下载的明细记录（一条下载 = 一行）：

| 学校 | 城市 | 区县 | 负责人 | 下载时间 |
|------|------|------|--------|---------|

按下载时间倒序排列。页面顶部显示汇总：「共下载 N 所学校的活码，累计 M 次」。

### 4.3 管理后台：下载统计 `/admin/download-stats`

挂载在现有管理后台导航中（仅 admin/operator 可见）。

**顶部汇总卡片**：
- 活码总数 · 已下载员工 · 未下载员工 · 总下载次数

**筛选栏**：
- 按城市/区县/负责人筛选
- 按下载状态筛选（已下载/未下载）
- 按时间范围筛选

**数据表格**：

| 活码-学校 | 城市·区县 | 负责人 | 绑定员工 | 下载状态 | 次数 | 最近下载 |
|-----------|----------|--------|---------|---------|------|---------|
| XX中学 | 广州·天河 | 张三 | 李四 | ✓ 已下载 | 3次 | 2026-06-17 14:30 |
| XX中学 | 广州·天河 | 张三 | 王五 | ✗ 未下载 | — | — |

同一活码的行按员工展开，可折叠。

### 4.4 管理后台：区县负责人配置 `/admin/district-managers`

挂载在系统设置菜单下，仅 admin 可见。

**功能**：
- 表格展示已配置的区县负责人
- 新增/编辑：城市 → 区县（从现有活码数据去重下拉） → 负责人（从员工列表选择）
- 删除（确认弹窗）
- Excel 批量导入/导出

---

## 五、后端模块

### 5.1 Controller

| Controller | 路径 | 受众 |
|------------|------|------|
| `DownloadCenterController` | `/download` | 企微员工 |
| `DownloadStatsController` | `/admin/download-stats` | 管理员/运营 |
| `DistrictManagerController` | `/admin/district-managers` | 管理员 |

### 5.2 Service

| Service | 职责 |
|---------|------|
| `DownloadLogService` | 下载日志写入、统计查询、个人历史 |
| `DistrictManagerService` | 负责人 CRUD、按区县查询 |
| `WecomOAuthService` | 企微 OAuth 授权 URL 构造、code 换 userid |

### 5.3 Repository

| Repository | 对应表 |
|------------|--------|
| `QrDownloadLogRepository` | qr_download_log |
| `DistrictManagerRepository` | district_manager |

### 5.4 实体

| Entity | 对应表 |
|--------|--------|
| `QrDownloadLog` | qr_download_log |
| `DistrictManager` | district_manager |

---

## 六、性能考量

| 关注点 | 应对 |
|--------|------|
| 活码图片下载并发 | 复用现有下载逻辑；对同一活码图片在 Redis 缓存 5 分钟（key: `qr:image:cache:{qrCodeId}`） |
| 下载日志表增长 | 单表，平均 1 条/人/天，预计年增量 < 10 万行，MySQL 轻松承载 |
| 未下载员工查询 | 使用 LEFT JOIN + IS NULL，QrAgent 表本身不大（每活码 ≤ 100 人） |
| 区县负责人缓存 | 全量加载后缓存到 Redis，5 分钟过期，避免每次页面渲染查 DB |
| 活码浏览搜索 | 复用现有 `QrCodeRepository.search`，无额外性能开销 |

---

## 七、测试要点

- 企微 OAuth 回调：正常获取 userid、code 过期、企微 API 异常
- 下载日志：首次下载写入、重复下载累加计数、未下载员工正确展示
- 下载中心页面：卡片渲染、搜索筛选、分页、空状态（无绑定活码时展示引导）
- 负责人展示：正常匹配、无负责人时不展示该行
- 管理后台统计：数据正确性、筛选组合、分页
- 与现有管理后台的隔离：admin 登录后访问 /download 应被拒绝，employee session 访问 /admin 应被拒绝

---

## 八、文件清单

### 新增

```
src/main/java/com/bookstore/qrcode/
  entity/QrDownloadLog.java
  entity/DistrictManager.java
  repository/QrDownloadLogRepository.java
  repository/DistrictManagerRepository.java
  service/DownloadLogService.java
  service/DistrictManagerService.java
  service/WecomOAuthService.java
  controller/DownloadCenterController.java
  controller/DownloadStatsController.java
  controller/DistrictManagerController.java

src/main/resources/templates/
  download/index.html
  download/history.html
  admin/download-stats.html
  admin/district-managers.html

src/main/resources/static/
  css/download-center.css
```

### 修改

```
src/main/java/com/bookstore/qrcode/config/SecurityConfig.java  — 新增 /download/** 规则
src/main/resources/templates/layout.html                       — 导航栏新增入口
```
