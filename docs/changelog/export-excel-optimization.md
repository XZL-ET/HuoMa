# 活码导出 Excel 优化记录

**日期**：2026-07-09
**分支**：feature/welcome-form-inheritance
**文件**：`QrCodeController.java`（`GET /qrcodes/export`）

---

## 变更概要

| 项目 | 优化前 | 优化后 |
|---|---|---|
| 列数 | 10 列 | 8 列 |
| 文件名 | `qr_codes_日期.xlsx` | `客户数据表_日期.xlsx` |
| 表头样式 | 无 | 加粗 + 浅蓝背景 + 居中 |
| 数字对齐 | 左对齐 | 居中 |
| 时间格式 | `2026-07-09T10:30:00` | `2026-07-09 10:30:00` |
| 首行冻结 | 无 | 冻结 |
| 列宽 | 默认 | 自动适配内容 |
| 异常处理 | 浏览器下载损坏文件 | `response.reset()` 兜底 |
| 日志 | 无 | 导出前后各一条 |
| 临时文件 | 仅 `close()` | `try-finally` 确保 `dispose()` |

---

## 列结构变更

### 移除（3 列）
- **学校ID** — 内部标识，对外无业务意义
- **状态** — 导出场景下冗余
- **轮换模式** — 导出场景下冗余

### 新增（1 列）
- **学生人数** — 学校学生总人数，`QrCode.studentCount`

### 最终列

| 序号 | 列名 | 数据来源 |
|---|---|---|
| 1 | 学校名称 | `QrCode.schoolName` |
| 2 | 城市 | `QrCode.regionCity` |
| 3 | 区县 | `QrCode.regionDistrict` |
| 4 | 分组 | `QrCodeGroup.name`（通过 groupId 映射） |
| 5 | 学生人数 | `QrCode.studentCount` |
| 6 | 今日新增客户 | `Customer` 表当日计数 |
| 7 | 累计客户数 | `Customer` 表历史计数 |
| 8 | 创建时间 | `QrCode.createdAt`（格式化后） |

---

## 日志输出

```
[导出开始] 导出活码列表: operator=admin, keyword=null, city=null, district=null, ...
[导出完成] 导出完成: 12 条活码记录已写入 客户数据表_2026-07-09.xlsx
```
