# 学校活码自助查询 — 设计文档

**日期**: 2026-06-19  
**状态**: 已确认  
**关联**: [[2026-06-17-employee-qrcode-download-center-design]]

---

## 1. 需求概述

### 1.1 背景

当前活码下发流程：员工通过「下载中心」下载活码 → 手动发送给学校。员工工作量大。

需要一个面向学校人员的自助查询页面：学校人员通过印制在文件上的固定二维码扫码 → 选择市州/县区/学校 → 自助获取活码或联系负责人。

### 1.2 核心目标

- 面向学校人员的公开页面，无需登录
- 通过三次点击（市州 → 县区 → 学校）定位学校
- 已创建活码的学校 → 展示活码 + 下载按钮
- 未创建活码的学校 → 展示区县负责人活码 + 操作指引
- 首页全局联系人入口（兜底）

### 1.3 与现有下载中心的区别

| | 下载中心 /download | 学校自助查询 /s |
|---|---|---|
| **用户** | 内部员工 | 外部学校人员 |
| **认证** | 企微 OAuth 登录 | 无登录（临时 Cookie + 限流） |
| **定位方式** | 搜索 + 卡片筛选 | 阶梯式地区选择 + 搜索 |
| **下载目标** | 待发用的活码 | 自己学校的活码 |
| **活码未创建** | 不显示 | 显示负责人兜底 |
| **日志渠道** | channel=employee | channel=school |

---

## 2. 页面设计

### 2.1 URL

```
https://域名/s
```

- 固定路径，永久不变，可印制在文件上
- 强制 HTTPS

### 2.2 交互流程

```
扫码 → /s 首页
         ├─ 搜索框（可选）→ 搜索结果 → 学校详情
         ├─ 选市州 → 选区县 → 选学校 → 学校详情
         └─ 全局联系人入口（底部）
```

### 2.3 四屏设计（移动端优先，375px 宽度）

#### 屏①：选择市州
```
┌─────────────────────┐
│ 火马 · 学校活码查询   │  蓝色顶栏
├─────────────────────┤
│ 🔍 搜索学校名称…     │  可选搜索
├─────────────────────┤
│   或按地区选择        │
│                     │
│ 📍 武汉    12区县  › │  白色卡片列表
│ 📍 黄石     6区县  › │
│ 📍 襄阳     9区县  › │
│                     │
│ ────── 或 ────────  │
│                     │
│ 🎓 找不到学校？       │  蓝色渐变卡片
│   火马客服           │  全局联系人入口
│   点击查看 →         │
└─────────────────────┘
```

#### 屏②：选择县区
```
┌─────────────────────┐
│ 火马 · 学校活码查询   │
├─────────────────────┤
│ ← 市州 | 武汉        │  面包屑返回
├─────────────────────┤
│ 江汉区    8所学校    │
│ 武昌区   12所学校    │
│ 江岸区    6所学校    │
└─────────────────────┘
```

#### 屏③：选择学校
```
┌─────────────────────┐
│ 火马 · 学校活码查询   │
├─────────────────────┤
│ ← 区县 | 武汉·江汉区  │
├─────────────────────┤
│ 武汉第一小学  已有活码 │  绿色标签
│ 江汉中学      已有活码 │
│ 新华小学      待创建  │  橙色标签
└─────────────────────┘
```

#### 屏④A：学校有活码（status=active）
```
┌─────────────────────┐
│ ← 返回学校列表       │
├─────────────────────┤
│   武汉市第一小学      │
│   ● 活码已就绪       │  绿色标签
│                     │
│  ┌─────────────┐   │
│  │             │   │
│  │  学校活码    │   │  180×180 QR 图
│  │             │   │
│  └─────────────┘   │
│  联系人：张老师      │
│                     │
│  [⬇ 保存活码到手机]  │  蓝色大按钮
│  或长按上方图片保存   │
└─────────────────────┘
```

#### 屏④B：学校无活码 / 非 active 状态
```
┌─────────────────────┐
│ ← 返回学校列表       │
├─────────────────────┤
│   武汉市新华小学      │
│   ● 活码尚未创建     │  橙色标签
│                     │
│  ┌─────────────┐   │
│  │  负责人      │   │  黄色边框卡片
│  │  企微二维码   │   │
│  └─────────────┘   │
│  李老师 · 江汉区负责人│
│                     │
│  📋 如何添加负责人？  │  步骤指引卡片
│  ① 长按保存到相册    │
│  ② 微信扫一扫选取    │
│  ③ 备注学校名        │
│                     │
│  添加时请备注：       │
│  武汉市新华小学       │
└─────────────────────┘
```

### 2.4 搜索交互

- 首页搜索框输入关键词 → 点击搜索 → 展示匹配学校卡片列表
- 搜索不到 → 「未找到匹配学校」+ 全局联系人入口
- 搜索结果每项显示学校名称 + 市州区县 + 活码状态标签

### 2.5 空状态

- 区县下暂无学校 → 「该地区暂无学校数据」+ 全局联系人入口

### 2.6 活码状态映射

| QrCode.status | 学校端标签 | 行为 |
|---|---|---|
| `active` | 🟢 活码已就绪 | 展示活码 + 下载按钮 |
| `paused` | 🟠 活码维护中 | 兜底：区县负责人 → 全局联系人 |
| `full` | 🟠 咨询人数较多 | 兜底 |
| `no_agent` | 🟠 暂未分配接待人员 | 兜底 |
| 无记录 | 🟠 活码尚未创建 | 兜底 |

### 2.7 微信内置浏览器适配

前端 JS 检测 UA `MicroMessenger`：
- **微信内**：指引「点击右上角 ··· → 在浏览器中打开」，提供按钮触发系统分享
- **普通浏览器**：直接展示下载按钮 + 长按保存提示

### 2.8 设计规范

| 属性 | 值 |
|---|---|
| 主色 | #2563EB（专业蓝） |
| 背景 | #F8FAFC（浅灰白） |
| 卡片 | #FFFFFF，圆角 12px，边框 #E2E8F0 |
| 文字 | #1E293B（深灰） |
| 成功标签 | #16A34A 绿底 #DCFCE7 |
| 警告标签 | #EA580C 橙底 #FFF7ED |
| 按钮 | 蓝色填充 + box-shadow: 0 4px 12px rgba(37,99,235,0.30) |
| 字体 | system-ui, -apple-system, sans-serif |

---

## 3. 安全设计

### 3.1 访问控制

```
正常用户 → 透明通行
    ↓
异常流量（同 IP 高频） → 滑动窗口限流
    ↓
超限 → 弹出图形验证码
    ↓
验证通过 → 继续访问
```

| 层级 | 措施 | 等保三级对应 |
|---|---|---|
| 传输 | 强制 HTTPS | 通信保密性 |
| 会话 | 首次访问签发临时 Cookie，30 分钟有效 | 身份鉴别 |
| 频控 | IP 滑动窗口限流（如 30次/分钟） | 入侵防范 |
| 验证码 | 超限后弹出图形验证码 | 访问控制 |
| 审计 | 记录每次查询：时间、IP、查询内容、UA | 安全审计 |
| 数据 | 不展示个人隐私，只展示活码和公开联系信息 | 数据安全 |

### 3.2 与核心业务隔离

| 组件 | 是否影响核心业务 |
|---|---|
| MySQL | 不影响 — 独立 school 表，简单索引查询 |
| Redis | 不影响 — 使用 Caffeine 本地内存缓存，不连 Redis |
| 企微 API | 不影响 — 仅在首次负责人活码创建时触发，极低频 |
| Undertow | 可忽略 — Caffeine 命中后接近静态返回 |

---

## 4. 数据设计

### 4.1 新建 school 表

```sql
CREATE TABLE school (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '学校唯一标识',
    school_name     VARCHAR(128) NOT NULL COMMENT '学校名称',
    region_city     VARCHAR(64)  NOT NULL COMMENT '市州',
    region_district VARCHAR(64)  NOT NULL COMMENT '县区',
    has_qrcode      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已有活码',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_city_district (region_city, region_district),
    INDEX idx_school_id (school_id),
    INDEX idx_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学校主数据表';
```

- 数据录入：从现有 `qr_code` 表迁移已有学校 + Excel 批量导入未录入学校
- `has_qrcode` 冗余字段，定期与 `qr_code` 表同步，方便查询

### 4.2 DistrictManager 表新增字段

```sql
ALTER TABLE district_manager
    ADD COLUMN qr_config_id VARCHAR(64) DEFAULT NULL COMMENT '企微联系我 config_id',
    ADD COLUMN qr_url       VARCHAR(512) DEFAULT NULL COMMENT '负责人活码图片URL';
```

### 4.3 新建 system_config 表

```sql
CREATE TABLE system_config (
    config_key   VARCHAR(64) PRIMARY KEY COMMENT '配置键',
    config_value TEXT         COMMENT '配置值',
    updated_at   DATETIME     DEFAULT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 初始数据
INSERT INTO system_config (config_key, config_value) VALUES
('global_contact_name', '火马客服'),
('global_contact_qr_config_id', ''),
('global_contact_qr_url', '');
```

全局负责人的活码在首次展示时自动调用企微 API 创建，写回 `qr_config_id` 和 `qr_url`。

### 4.4 日志表扩展

将现有 `qr_download_log` 扩展为更通用的 `qr_access_log`：

```sql
CREATE TABLE qr_access_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id      BIGINT       COMMENT '活码ID（查看/下载活码时关联）',
    action          ENUM('view','download') NOT NULL DEFAULT 'view' COMMENT '行为类型',
    channel         ENUM('employee','school') NOT NULL DEFAULT 'school' COMMENT '来源渠道',
    user_identity   VARCHAR(128) COMMENT '身份标识（员工=企微userid，学校=IP+UA摘要）',
    accessed_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    ip_address      VARCHAR(45)  COMMENT '客户端IP',
    user_agent      VARCHAR(512) COMMENT '浏览器UA',
    INDEX idx_qr_code_id (qr_code_id),
    INDEX idx_channel (channel),
    INDEX idx_accessed_at (accessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码访问日志表';
```

### 4.5 查询逻辑

```
查询学校详情：
  SELECT s.*, q.qr_url, q.qr_config_id, q.status, q.school_name as qr_school_name
  FROM school s
  LEFT JOIN qr_code q ON s.school_id = q.school_id
  WHERE s.school_id = ?

降级查询负责人：
  SELECT * FROM district_manager
  WHERE region_city = ? AND region_district = ?

  降级到：
  SELECT * FROM system_config
  WHERE config_key LIKE 'global_contact_%'
```

---

## 5. 接口设计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/s` | 首页：市州列表 + 全局联系人 |
| GET | `/s/cities` | HTMX 片段：返回市州列表 HTML |
| GET | `/s/districts?city=武汉` | HTMX 片段：返回县区列表 HTML |
| GET | `/s/schools?city=武汉&district=江汉` | HTMX 片段：返回学校列表 HTML |
| GET | `/s/school/{schoolId}` | HTMX 片段：返回学校详情 HTML |
| GET | `/s/search?keyword=第一小学` | 搜索匹配的学校列表 |
| GET | `/s/global-contact` | 展示全局联系人详情 |
| GET | `/s/school/{schoolId}/download` | 下载活码图片 + 记录日志 |
| GET | `/s/verify-code` | 获取图形验证码（限流超限时） |

所有接口返回 HTML 片段（Thymeleaf + HTMX），不返回 JSON。

---

## 6. 缓存设计

使用 Spring `@Cacheable` + Caffeine 本地内存缓存：

```java
// 不依赖 Redis，零影响核心打标业务

@Cacheable(value = "cities",     unless = "#result == null")
public List<CityDTO> getCities() { ... }     // TTL: 5 分钟

@Cacheable(value = "districts",  unless = "#result == null")
public List<DistrictDTO> getDistricts(String city) { ... }  // TTL: 5 分钟
```

- 缓存量：全省十几市、每市十几区县，约几 KB
- Caffeine 纳秒级返回，不经过网络
- TTL 5 分钟，新增学校后自动在下一个 5 分钟窗口生效

---

## 7. 负责人活码自动创建

### 7.1 触发时机

```
学校详情查询 → 学校无活码 / 状态非 active
  → 查 district_manager WHERE region_city=? AND region_district=?
    → 有负责人
      → qr_config_id 为空？→ 调企微 API 创建活码 → 存回 district_manager
      → qr_config_id 存在？→ 直接展示
    → 无负责人
      → 查 system_config global_contact_
        → qr_config_id 为空？→ 调企微 API 创建 → 存回 system_config
        → 存在？→ 直接展示
```

### 7.2 创建逻辑

复用现有 `QrCodeService` 的企微 `add_contact_way` API 调用：
- `type=1`（单人）
- `scene=2`（联系我）
- `user=[managerUserid]`
- state 固定为 `school_fallback`

创建时需要处理企微 5 QPS 并发限制（项目已有 `WecomApiClient` 的重试/排队逻辑）。

---

## 8. 管理后台

### 8.1 学校管理 `/admin/schools`

- 列表页：分页、搜索、按市州区县筛选
- 新增/编辑单所学校
- Excel 批量导入
- 软删除
- 从 `qr_code` 同步 `has_qrcode` 状态

### 8.2 区县负责人 `/admin/district-managers`（已有，扩展）

- 新增字段：`qr_config_id`、`qr_url`
- 变更负责人时，允许手动重置活码（删旧建新）

### 8.3 全局联系人配置 `/admin/system-config`

- 编辑 `global_contact_name`
- 重置全局联系人活码

### 8.4 入口二维码 `/admin/school-entry`

- 展示 `/s` 完整 URL
- 用 ZXing 生成二维码图片
- 下载二维码按钮
- 显示累计访问统计

### 8.5 访问统计 `/admin/download-stats`（已有，扩展）

- 增加 `channel` 维度筛选（员工下载 / 学校自助）
- 学校渠道显示：活码、查看次数、下载次数、时间、IP

---

## 9. 技术实现

### 9.1 新增文件

| 文件 | 说明 |
|---|---|
| `controller/SchoolEntryController.java` | `/s` 路径所有接口 |
| `service/SchoolService.java` | 学校查询、缓存、负责人生成 |
| `service/SchoolAccessLogService.java` | 访问日志记录 |
| `entity/School.java` | school 表 JPA 实体 |
| `entity/SystemConfig.java` | system_config 表 JPA 实体 |
| `repository/SchoolRepository.java` | 学校 Repository |
| `repository/SystemConfigRepository.java` | 配置 Repository |
| `templates/school/layout.html` | 学校端布局模板 |
| `templates/school/cities.html` | 市州选择页 |
| `templates/school/districts.html` | 县区选择页 |
| `templates/school/schools.html` | 学校列表页 |
| `templates/school/detail.html` | 学校详情页（有活码/无活码） |
| `templates/school/search-results.html` | 搜索结果页 |
| `templates/school/global-contact.html` | 全局联系人详情页 |
| `static/css/school-entry.css` | 学校端样式 |

### 9.2 修改文件

| 文件 | 修改内容 |
|---|---|
| `DistrictManager.java` | 新增 qrConfigId、qrUrl 字段 |
| `schema.sql` | 新建 school、system_config、qr_access_log 表 |
| `SecurityConfig.java` | `/s/**` 放行，添加 `RateLimitFilter` |
| `application.yml` | 添加 IP 限流参数配置 |
| `admin/download-stats.html` | 增加渠道筛选维度 |

### 9.3 技术栈

- Spring Boot 3.2.5（已有）
- Thymeleaf + HTMX（已有）
- Bootstrap 5.3（已有）
- Spring Data JPA（已有）
- Caffeine Cache（`spring-boot-starter-cache` 内置）
- ZXing（已有，入口二维码生成）
- Bootstrap Icons（已有）

**不引入新依赖。**

---

## 10. 后续规划

| 优先级 | 内容 |
|---|---|
| P0（本次） | 核心页面 + 学校表 + 负责人自动创建 + 日志 + 后台学校管理 |
| P1（后续） | 后台访问统计扩展、图形验证码 |
| P2（后续） | 负责人换人自动清理旧活码、学校数据去重/合并 |
