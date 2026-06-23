# 活码规模化管理改进方案

**日期**: 2026-06-23  
**背景**: 活码数量从 500 增长到 5000，现有管理功能在性能、批量操作、创建效率三方面出现瓶颈。

---

## 一、列表页性能改造

### 1.1 N+1 查询 → 聚合 SQL

**现状**: 列表页每行单独查今日新增数和客服数，20 行 = 40+ 次额外 SQL。

**方案**: 拿到当前页活码 ID 列表后，一次查询同时取今日新增和累计总数：

```sql
SELECT source_qr_id,
       COUNT(*) AS total,
       SUM(CASE WHEN add_time >= :todayStart THEN 1 ELSE 0 END) AS today
FROM customer
WHERE source_qr_id IN (:pageIds)
GROUP BY source_qr_id
```

`customer` 表已有 `idx_source_qr` 单列索引和 `idx_customer_add_time_qr` 复合索引，查询走索引，百万级数据毫秒返回。

客服数同理，用 `GROUP BY qr_code_id` 聚合查询替代循环单查。

Controller 层改为从 Map 读取：`todayCountMap.getOrDefault(qrId, 0L)`。

### 1.2 Scope 筛选下推到 SQL

**现状**: `scope=alliance/school` 时，`Pageable.unpaged()` 加载全表再 Java stream 过滤，5000 活码时每次筛选都扫全量。

**方案**: 新增两个 Repository 方法，用 JPQL 子查询在 DB 侧完成过滤：

```java
// alliance: id 在 QrCodeGroup 中有记录
@Query("SELECT q FROM QrCode q WHERE ... AND q.id IN (SELECT g.qrCodeId FROM QrCodeGroup g)")
Page<QrCode> searchAlliance(...);

// school: id 不在 QrCodeGroup 中
@Query("SELECT q FROM QrCode q WHERE ... AND q.id NOT IN (SELECT g.qrCodeId FROM QrCodeGroup g)")
Page<QrCode> searchSchool(...);
```

Controller 根据 `scope` 参数路由到不同方法，不再走 unpaged + Java filter。

**风险**: JPQL 子查询需重点测试，两个独立方法比一个动态拼接的查询更安全可控。

### 1.3 树接口 DTO 投影

**现状**: `GET /qrcodes/tree` 调用 `findAll()` 加载全部 25 列，但实际只用 5 个字段（id, schoolName, regionCity, regionDistrict, groupId）。

**方案**: 新增 JPQL 投影查询，只取 5 个字段，返回 DTO 列表。中间 Map 结构从 `List<QrCode>` 改为 `List<QrCodeTreeDto>`，改动局限在 `tree()` 方法内。

### 1.4 分页可选 50/100

列表页分页下拉增加 50/100 选项，Controller 已有 `size` 参数，改前端即可。

### 1.5 筛选与排序增强

**分组筛选**: 列表页筛选区增加分组下拉，Controller 已加载全部 group 到 Model，改为前端动态填充下拉选项，搜索时增加 `groupId` 参数，JPQL 追加 `AND (:groupId IS NULL OR q.groupId = :groupId)`。

**排序**: 默认按创建时间倒序（最新在前），支持切换为创建时间正序。排序字段 `createdAt` 已存在于 `qr_code` 表，加在 JPQL `ORDER BY` 即可。

---

## 二、列表导出

### 2.1 方案

列表页新增"导出"按钮，按照当前筛选条件（关键词、城市、区县、状态、分组、scope）查询全量结果，生成 Excel 文件下载。

**导出列**: 学校名称、学校ID、城市、区县、分组、状态、轮换模式、今日新增、累计客户、创建时间

### 2.2 性能

5000 条数据导出约 1-2 秒，POI `SXSSFWorkbook` 流式写入，内存控制在几十 MB。导出期间不阻塞其他请求。

### 2.3 改动

- `QrCodeController` 新增 `GET /qrcodes/export` 端点
- `QrCodeRepository` 新增不分页 `List<QrCode>` 查询方法
- 复用已有聚合查询获取客户数

---

## 三、批量操作台

### 3.1 入口

列表页增加"批量操作模式"切换按钮，进入后：
- 行变大，checkbox 更显眼
- 顶部出现批量操作工具栏
- 已选计数实时显示
- 支持跨页全选（记住已选 ID 集合）

### 3.2 批量操作能力

| 操作 | 实现 |
|------|------|
| 批量改欢迎语 | `UPDATE qr_code SET welcome_text = ? WHERE id IN (?)` |
| 批量改表单模板 | `UPDATE qr_code SET form_template_id = ? WHERE id IN (?)` |
| 批量切换轮换模式 | `UPDATE qr_code SET rotate_mode = ? WHERE id IN (?)` |
| 批量改分组 | `UPDATE qr_code SET group_id = ? WHERE id IN (?)` |
| 批量改阈值 | `UPDATE qr_code SET warn_ratio = ?, urgent_ratio = ? WHERE id IN (?)` |
| 批量暂停/启用 | `UPDATE qr_code SET status = ? WHERE id IN (?)` |
| 批量下载 | 改为流式 ZIP 输出，避免 OOM |

所有操作走 Spring Data JPA `@Modifying` + `@Query`，一条 SQL 批量更新。

### 3.3 确认弹窗

执行前展示：影响 X 个活码、操作类型、参数预览。不做回滚快照。

---

## 四、批量导入增强

### 4.1 Excel 列扩展

`parseExcel` 当前读取 5 列，扩展为 11 列：

| 列 | 字段 | 必填 |
|----|------|------|
| 学校名称 | schoolName | ✅ |
| 学校ID | schoolId | ✅ |
| 市 | regionCity | ✅ |
| 区 | regionDistrict | ✅ |
| 服务老师 | serviceTeacherUserid | ✅ |
| 学校人数 | studentCount | ✅ |
| 初始上码员工数 | initialAgentCount | 选填（默认 1） |
| 接待员 | receptionistUserid | 选填（逗号分隔） |
| 服务老师日上限 | serviceDailyMax | 选填（默认 30） |
| 欢迎语 | welcomeText | 选填 |
| 备注 | remark | 选填 |

### 4.2 模板下载

批量导入页面提供"下载模板"按钮，返回预置表头 + 示例数据行的 `.xlsx` 文件。模板文件放在 `src/main/resources/templates/qrcode/` 下或由 Controller 动态生成。

### 4.3 改动范围

| 文件 | 改动 |
|------|------|
| `QrCodeService.parseExcel()` | 多读 6 列（索引 5-10） |
| `QrCodeService.executeBatchImport()` | 多 set 6 个字段到 DTO |
| `batch-import.html` | 更新格式说明 + 模板下载按钮 |
| `QrCodeController` | 新增模板下载端点 |

---

## 五、全局池 DB 分页

### 5.1 现状

`QrCodeService.getBackups()` 调用 `poolRepo.findAll()` 加载全局池全部员工到内存，然后 Java 排序 + `subList` 分页。详情页每打开一次就全量加载一次，池子膨胀到上千人后明显卡顿。

### 5.2 方案

改为真正的 DB 分页，同时保持现状排序规则不变。

**排序逻辑** — 状态优先（standby → full → blocked），同状态按 sortOrder 升序。新增 Repository 方法：

```java
@Query("SELECT p FROM GlobalAgentPool p ORDER BY "
     + "CASE p.status WHEN 'standby' THEN 0 WHEN 'full' THEN 1 ELSE 2 END, "
     + "p.sortOrder ASC")
Page<GlobalAgentPool> findAllWithStatusPriority(Pageable pageable);
```

**池状态统计** — 改为三条 `countByStatus()`（已有现成方法）：

```java
long standby = poolRepo.countByStatus(PoolStatus.standby);
long full = poolRepo.countByStatus(PoolStatus.full);
long blocked = poolRepo.countByStatus(PoolStatus.blocked);
```

**弹窗去重 userid** — 改为 `findAllAgentUserids()`（已有现成轻量投影，只查 userid 不加载整行）。

### 5.3 风险

低。排序用 JPQL `CASE` 与原 Java 排序完全等价。三条 countByStatus 多两次查询，但每条都是 SELECT COUNT 走索引，远快于全量加载。唯一需新增的代码是一条带 CASE 排序的 `@Query`。

---

## 六、改动清单总览

| 模块 | 后端改动 | 前端改动 | 风险 |
|------|---------|---------|------|
| 列表性能 | Repository + Controller | 分页下拉 + 筛选排序 | 低 |
| 列表导出 | Controller + Repository | 导出按钮 | 低 |
| 批量操作 | Repository `@Modifying` + Controller | 批量模式 UI | 低 |
| 批量导入 | `parseExcel` + `executeBatchImport` | 模板下载 + 格式说明 | 低 |
| 全局池分页 | Repository `@Query` + Service | 无 | 低 |

不涉及数据库结构变更，不新增表，纯优化 + 增强。
