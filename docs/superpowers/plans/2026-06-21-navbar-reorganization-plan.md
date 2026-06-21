# 导航栏整理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将平铺 13 个导航链接改为 4 个业务域下拉菜单

**Architecture:** 只改 `layout.html`，用 Bootstrap 5 原生 `dropdown` 组件分组导航，用 Thymeleaf `#request` 对象判断当前路径做高亮

**Tech Stack:** Thymeleaf, Bootstrap 5.3

## Global Constraints

- 只改 `src/main/resources/templates/layout.html` 一个文件
- 不新增 Controller 方法、不新增 Model 属性
- 移除所有 `sec:authorize` 权限控制
- 移动端 hamburger 菜单正常工作

---

### Task 1: Replace flat navbar with 4 dropdown menus

**Files:**
- Modify: `src/main/resources/templates/layout.html:42-56`

**Interfaces:**
- Consumes: Bootstrap 5 dropdown JS (already loaded via `bootstrap.bundle.min.js`)
- Produces: 4 dropdown menus, each with path-based active highlighting

- [ ] **Step 1: Replace the navbar-nav div**

Replace lines 42-56:

```html
                <div class="navbar-nav">
                    <a class="nav-link" href="/qrcodes">活码管理</a>
                    <a class="nav-link" href="/customers">客户管理</a>
                    <a class="nav-link" href="/agents">员工管理</a>
                    <a class="nav-link" href="/dashboard">数据看板</a>
                    <a class="nav-link" href="/alerts">异常告警</a>
                    <a class="nav-link" href="/admin/download-stats">下载统计</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/groups">联盟管理</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/schools">学校管理</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/district-managers">区县负责人</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/school-entry">入口二维码</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/system-config">系统配置</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/admin/form-templates">表单模板</a>
                    <a sec:authorize="hasRole('ADMIN')" class="nav-link" href="/users">系统设置</a>
                </div>
```

With:

```html
                <div class="navbar-nav">
                    <!-- 活码运营 -->
                    <div class="nav-item dropdown">
                        <a class="nav-link dropdown-toggle" href="#" role="button"
                           data-bs-toggle="dropdown" aria-expanded="false"
                           th:classappend="${#request.getServletPath().startsWith('/qrcodes')
                               or #request.getServletPath().startsWith('/agents')
                               or #request.getServletPath().startsWith('/admin/groups')
                               or #request.getServletPath().startsWith('/admin/school-entry')} ? 'active' : ''">活码运营</a>
                        <div class="dropdown-menu">
                            <a class="dropdown-item" href="/qrcodes">活码管理</a>
                            <a class="dropdown-item" href="/agents">员工管理</a>
                            <a class="dropdown-item" href="/admin/groups">联盟管理</a>
                            <a class="dropdown-item" href="/admin/school-entry">入口二维码</a>
                        </div>
                    </div>
                    <!-- 客户运营 -->
                    <div class="nav-item dropdown">
                        <a class="nav-link dropdown-toggle" href="#" role="button"
                           data-bs-toggle="dropdown" aria-expanded="false"
                           th:classappend="${#request.getServletPath().startsWith('/customers')
                               or #request.getServletPath().startsWith('/admin/form-templates')} ? 'active' : ''">客户运营</a>
                        <div class="dropdown-menu">
                            <a class="dropdown-item" href="/customers">客户管理</a>
                            <a class="dropdown-item" href="/admin/form-templates">表单模板</a>
                        </div>
                    </div>
                    <!-- 数据分析 -->
                    <div class="nav-item dropdown">
                        <a class="nav-link dropdown-toggle" href="#" role="button"
                           data-bs-toggle="dropdown" aria-expanded="false"
                           th:classappend="${#request.getServletPath().startsWith('/dashboard')
                               or #request.getServletPath().startsWith('/alerts')
                               or #request.getServletPath().startsWith('/admin/download-stats')} ? 'active' : ''">数据分析</a>
                        <div class="dropdown-menu">
                            <a class="dropdown-item" href="/dashboard">数据看板</a>
                            <a class="dropdown-item" href="/alerts">异常告警</a>
                            <a class="dropdown-item" href="/admin/download-stats">下载统计</a>
                        </div>
                    </div>
                    <!-- 系统管理 -->
                    <div class="nav-item dropdown">
                        <a class="nav-link dropdown-toggle" href="#" role="button"
                           data-bs-toggle="dropdown" aria-expanded="false"
                           th:classappend="${#request.getServletPath().startsWith('/admin/schools')
                               or #request.getServletPath().startsWith('/admin/district-managers')
                               or #request.getServletPath().startsWith('/admin/system-config')
                               or #request.getServletPath().startsWith('/users')} ? 'active' : ''">系统管理</a>
                        <div class="dropdown-menu">
                            <a class="dropdown-item" href="/admin/schools">学校管理</a>
                            <a class="dropdown-item" href="/admin/district-managers">区县负责人</a>
                            <a class="dropdown-item" href="/admin/system-config">系统配置</a>
                            <a class="dropdown-item" href="/users">系统设置</a>
                        </div>
                    </div>
                </div>
```

- [ ] **Step 2: Compile and verify template**

```bash
./mvnw compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Manual verification checklist**

Start the application and verify:
1. Homepage → 4 dropdowns visible, none highlighted (or 活码运营 highlighted if `/` maps there)
2. Click `/qrcodes` → 活码运营 dropdown highlighted
3. Click `/customers` → 客户运营 dropdown highlighted
4. Click `/dashboard` → 数据分析 dropdown highlighted
5. Click `/admin/schools` → 系统管理 dropdown highlighted
6. Click `/admin/system-config` → 系统管理 dropdown highlighted
7. Resize to mobile width → hamburger menu appears, dropdowns work inside it
8. Each dropdown item links to the correct page

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/layout.html
git commit -m "refactor: reorganize navbar into 4 business-domain dropdowns

Replace 13 flat nav links with 4 dropdown groups:
活码运营 / 客户运营 / 数据分析 / 系统管理

Remove sec:authorize conditions since all users are admins.
Use Thymeleaf #request for path-based active highlighting.

Co-Authored-By: Claude <noreply@anthropic.com>"
```
