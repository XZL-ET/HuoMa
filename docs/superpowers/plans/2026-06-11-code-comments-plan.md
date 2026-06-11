# 项目全面注释实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为全部 62 个文件添加详尽中文注释（完整 Javadoc + 字段注释 + 内联逻辑解释）

**Architecture:** 自底向上逐层执行，共 8 批：Entity → Repository → Service → Controller → WeCom/Worker → Config/DTO → HTML → 配置/SQL

**Tech Stack:** Java 17+ / Spring Boot 3 / JPA / Thymeleaf / Maven

---

## 注释规范速查

所有 Java 文件统一使用以下模板：

**类注释：**
```java
/**
 * 类的职责描述。
 *
 * <p>业务背景简述，与其他模块的关系。
 *
 * @author Bookstore Dev
 * @since 1.0
 */
```

**公共方法注释：**
```java
/**
 * 方法功能描述。
 *
 * <p>详细说明（涉及多步骤时补充）。
 *
 * @param xxx 参数含义
 * @return 返回值含义
 * @throws RuntimeException 异常触发条件
 */
```

**字段注释：** `/** 字段含义（枚举值/特殊值说明） */`

**内联注释：** `// 1. 步骤：做什么 + 为什么` 或 `// 注意：边界条件说明`

---

## 第 1 批：Entity 层（11 个文件）

### Task 1.1: Tag.java — 标签实体

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/Tag.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 Tag 实体添加：
- 类 Javadoc：说明标签体系结构（支持企微标签同步、表单自动打标、手动标签三种来源，可父子层级）
- 每个字段的 `/** */` 注释
- 枚举 TagType 各值的含义
- `prePersist` 方法说明

### Task 1.2: CustomerTag.java — 客户-标签关联

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/CustomerTag.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 CustomerTag 添加：
- 类 Javadoc：说明客户与标签的多对多关联表，含打标来源追踪
- 每个字段注释
- 枚举 TagSource 各值（system/form/manual）的业务含义
- uniqueConstraint 的注释说明

### Task 1.3: Agent.java — 员工实体

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/Agent.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 Agent 添加：
- 类 Javadoc：员工主数据，包含日配额、熔断/预警状态、累计服务统计
- 每个字段详细注释（dailyTotalCap、dailyTotalUsed、meltedCount24h 需解释业务逻辑）
- 枚举 AgentRole（receptionist/service/dual）和 OverallStatus（normal/warning/blocked/melted）含义
- statusReason JSON 字段的结构说明

### Task 1.4: QrCode.java — 活码实体

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrCode.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 QrCode 添加：
- 类 Javadoc：活码核心实体，每个学校一个活码，关联企微 config_id，支持自动/手动轮换
- 每个字段详细注释（welcomeConfig 的 JSON 结构、customTags 格式、rotateMode 含义）
- 三个枚举：QrCodeStatus（active/paused/full/no_agent）、RotateMode（auto/manual）、CreateMode（manual/batch_import）
- styleConfig 的 JSON 结构说明（logo_path、theme、guide_text、show_school_name）
- warnRatio/urgentRatio 的预警阈值业务逻辑

### Task 1.5: QrAgent.java — 活码-员工绑定

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrAgent.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 QrAgent 添加：
- 类 Javadoc：活码与员工的关联表，记录每个员工在某个活码上的角色、日配额、当前接待数、状态
- dailyMax（接待员日限）、serviceDailyMax（服务老师日限，独立于 dailyMax）的区别说明
- dailyCurrent 的计数器逻辑说明
- 枚举 AgentRole（receptionist/service/dual）和 AgentStatus（active/full/removed/blocked）
- bindTarget JSON 字段结构说明
- replacedBy / lastResetAt 的业务场景

### Task 1.6: QrBackupPool.java — 后备池

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrBackupPool.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 QrBackupPool 添加：
- 类 Javadoc：后备接待员池，当前端员工满员或不可用时按排序激活后备
- 每个字段注释（sortOrder 的优先级逻辑）
- 枚举 PoolRole（receptionist/service）和 PoolStatus（standby/activated/removed）

### Task 1.7: QrRotateLog.java — 轮换日志

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/QrRotateLog.java`

- [ ] **步骤 1：完善类注释和字段注释**

QrRotateLog 已有类注释，需：
- 补充更详细的类 Javadoc（记录轮换触发原因、新老员工关系）
- 为每个字段添加注释
- fromUserid 的可空性说明（新增场景时为空）

### Task 1.8: Customer.java — 客户实体

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/Customer.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 Customer 添加：
- 类 Javadoc：企微外部联系人，记录添加来源（哪个活码、哪个员工）、当前归属员工、学校信息
- externalUserid 与企微的对应关系
- type 字段含义（1=微信用户，2=企业微信用户）
- sourceQrId / schoolId 的业务来源追踪说明
- 枚举 CustomerStatus（active/deleted）

### Task 1.9: CustomerTransfer.java — 客户转移记录

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/CustomerTransfer.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 CustomerTransfer 添加：
- 类 Javadoc：客户在员工间转移的完整生命周期记录，含重试、表单填写状态、欢迎语发送状态
- 枚举 TransferStatus 六个值（pending_confirm/confirmed/rejected/timeout/api_failed/retry_limit）详细说明
- 枚举 GreetingType（filled/unfilled）说明（根据客户是否填写表单发送不同欢迎语）
- noteSent/greetingSent 的发送标记说明
- formFilledAtTransfer 的作用
- retryCount / failReason 的重试机制

### Task 1.10: AgentAlert.java — 员工预警记录

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/AgentAlert.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 AgentAlert 添加：
- 类 Javadoc：员工状态预警/熔断记录，含自动动作执行结果
- 枚举 AlertSeverity（low/medium/high）和 AutoAction（none/paused/removed/melted）含义
- 枚举 AlertStatus（open/resolved/auto_resolved）的生命周期
- detail JSON 字段结构说明
- resolvedBy / resolvedAt 的解决追踪

### Task 1.11: DailyReport.java — 日报统计

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/entity/DailyReport.java`

- [ ] **步骤 1：添加类注释和字段注释**

为 DailyReport 添加：
- 类 Javadoc：每日运营数据汇总，按日期唯一
- 各统计字段含义（totalScan/totalAdd/totalTransfer/activeQr/fullQr/blockedAgent/meltedAgent）
- detailJson 的 JSON 结构说明

---

## 第 2 批：Repository 层（11 个文件）

对每个 Repository 接口：
- 接口 Javadoc：说明该 Repository 管理哪个实体
- 每个自定义查询方法的 Javadoc（方法名即功能描述，重点说明查询条件和返回含义）
- `@Query` 注解的方法需详细说明 JPQL 逻辑

## 第 3 批：Service 层（8 个文件）

- QrCodeService：核心服务，已有部分注释，需补全所有 public 方法的 @param/@return/@throws，关键内联逻辑详细注释
- 其他 Service：完整 Javadoc + 内联注释

## 第 4 批：Controller 层（6 个文件）

- 类 Javadoc：说明该 Controller 管理哪些页面
- 每个端点方法的 Javadoc：URL、HTTP 方法、参数、返回视图、Flash 属性

## 第 5 批：WeCom + Worker（8 个文件）

- WecomApiClient：企微 API 封装，说明每个方法对应哪个企微接口
- WecomCallbackController/Validator：回调处理流程说明
- Worker：定时任务触发条件和执行逻辑

## 第 6 批：Config / DTO / Application（5 个文件）

- Config 类：每个 @Bean 的用途说明
- DTO：字段含义
- Application 启动类：项目简介

## 第 7 批：HTML 模板（10 个文件）

- 每个模板文件开头添加 `<!-- 页面功能说明 -->`
- 关键区块用 `<!-- 区块名：功能 -->` 标注
- 复杂 Thymeleaf 表达式说明

## 第 8 批：配置 / SQL（3 个文件）

- pom.xml：关键依赖说明
- application.yml：配置项分组和含义
- schema.sql：表和字段注释
