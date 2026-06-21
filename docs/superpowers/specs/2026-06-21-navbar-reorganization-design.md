# 导航栏整理方案

## 背景

当前 `layout.html` 顶部导航栏 13 个链接平铺，无分组，显眼杂乱。

## 方案

采用 **Bootstrap 下拉菜单分组**（方案 A），按业务领域把 13 个链接收入 4 个下拉菜单，只改 `layout.html` 一个文件。

## 四大业务域

| 下拉菜单 | 包含页面 | 路由 |
|----------|---------|------|
| **活码运营** | 活码管理 | `/qrcodes` |
| | 员工管理 | `/agents` |
| | 联盟管理 | `/admin/groups` |
| | 入口二维码 | `/admin/school-entry` |
| **客户运营** | 客户管理 | `/customers` |
| | 表单模板 | `/admin/form-templates` |
| **数据分析** | 数据看板 | `/dashboard` |
| | 异常告警 | `/alerts` |
| | 下载统计 | `/admin/download-stats` |
| **系统管理** | 学校管理 | `/admin/schools` |
| | 区县负责人 | `/admin/district-managers` |
| | 系统配置 | `/admin/system-config` |
| | 系统设置 | `/users` |

## 实现

### 改动范围

- **只改 `src/main/resources/templates/layout.html`** 一个文件

### HTML 结构

把当前平铺 `<div class="navbar-nav">` 替换为 4 个 Bootstrap `dropdown`：

```html
<div class="navbar-nav">
    <div class="nav-item dropdown">
        <a class="nav-link dropdown-toggle" href="#" role="button"
           data-bs-toggle="dropdown">活码运营</a>
        <div class="dropdown-menu">
            <a class="dropdown-item" href="/qrcodes">活码管理</a>
            <a class="dropdown-item" href="/agents">员工管理</a>
            <a class="dropdown-item" href="/admin/groups">联盟管理</a>
            <a class="dropdown-item" href="/admin/school-entry">入口二维码</a>
        </div>
    </div>
    <!-- 同上结构 × 3 -->
</div>
```

### 当前页高亮

- 用 Thymeleaf 判断请求路径前缀，给对应下拉的 `nav-link` 加 `active` 类
- 各 Controller 向 Model 传入页面标识（如 `currentPage`），或直接用 `HttpServletRequest` 的 servletPath

### 移除权限控制

- 去掉所有 `sec:authorize="hasRole('ADMIN')"` 条件，所有导航项对所有登录用户可见

## 客户运营预留

「客户运营」下拉为后续「消息模板」功能预留位置（本次不实现）。

## 验证

1. 访问任意页面，顶部只显示 4 个下拉菜单 + 品牌 + 用户区
2. 点击每个下拉菜单，子项正确显示且链接可跳转
3. 当前页面所在的下拉菜单高亮
4. 页面在移动端（小屏幕）下 hamburger 菜单正常展开
