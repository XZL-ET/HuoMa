# 表单模板可视化编辑器

## 目标

将 `form-template-edit.html` 从 JSON 手写改为可视化字段编辑器，降低使用门槛。

## 不改动

- 后端 Controller、Service、Entity、Repository 均保持不变
- 提交仍然发送 `fields`（JSON）、`tagMapping`（JSON）、`remarkTemplate`（字符串）
- 列表页 `form-templates.html` 保持不变

## 页面结构

### ① 基本信息区（不变）
- 模板名称（必填）
- 说明（选填）

### ② 字段列表（新增可视化编辑器）
每个字段一张小卡片，包含：
- 字段 key（英文，如 `grade`）
- 标签（中文显示名，如 `孩子年级`）
- 类型：文字输入 / 下拉选择
- 必填：勾选框
- 映射方式：打标签 / 写备注 / 不映射
- 类型=下拉选择时，展开文本框输入选项（每行一个）
- 删除按钮

底部「+ 添加字段」按钮

### ③ 备注模板（自动生成）
- 将所有映射方式为"写备注"的字段按 `{{key}}` 拼接
- 用户可手动修改

## 数据流

```
字段卡片列表（JS 数组）
  → save 时：fields JSON + tagMapping JSON + remarkTemplate string
  → 设置到隐藏的 form input
  → POST 到后端（与现有接口完全兼容）

edit 时：
  ← 后端传来 fields JSON + tagMapping JSON + remarkTemplate string
  ← JS 解析 JSON 还原为字段卡片列表
```

## 验证

1. 新建模板 → 可视化添加 3 个字段 → 保存 → 列表页出现新模板
2. 编辑模板 → 卡片显示已有字段 → 修改 → 保存 → 字段更新
3. 下拉选择类型 → 填写选项 → 提交 → fields JSON 包含 options 数组
4. 备注模板自动生成正确
