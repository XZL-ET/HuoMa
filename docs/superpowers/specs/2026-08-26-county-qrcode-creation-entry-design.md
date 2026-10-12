# 县区码建码入口 — 设计文档

日期：2026-08-26
依赖：县区码选校功能（`docs/superpowers/specs/2026-08-25-district-qrcode-school-selection-design.md`，分支 `worktree-district-qrcode-school-selection`）

## 1. 背景与问题

县区码是上一阶段引入的活码类型：`QrCode.schoolId` 以 `county:` 前缀标识（`SchoolSelectionService.COUNTY_PREFIX = "county:"`），用于县区级「联系我」场景——家长扫码后通过三级级联选校，提交表单后由 `SchoolSelectionService.initiateCountyTransfer` 动态在职继承转接到对应学校的服务老师。

当前建码操作对操作员不直观，存在两个隐晦的「魔法约定」：

1. **手动改 schoolId**：常规建码页 `qrcode/create.html` 会预填自动生成的 `schoolId`（`SCH+时间戳`）。建县区码时，操作员必须手工把它改成 `county:区县名`，否则 `isCountyCode` 无法识别，县区码退化为普通学校活码。
2. **手动配 tag_mapping**：要让家长填写的「年级/班级」自动打企微标签，操作员必须手动在表单模板里把 `tag_mapping` 配成 `{"grade":"tag","class":"tag"}`（见 `TagService.applyFormTags`）。漏配则标签不落库，且无任何提示。

此外，县区码建好后在列表页与普通学校活码混排（`schoolName=县区名`），无视觉区分；建码走「手动创建」入口时 schoolId 可被误改。这些都削弱了「后台建码直观」的目标。

## 2. 目标

提供独立的「新建县区码」入口，操作员只填：

| 字段 | 必填 | 说明 |
|---|---|---|
| 市州 | 是 | autocomplete，来自企微标签组 cityOptions |
| 县区 | 是 | autocomplete，来自 districtOptions |
| 县区接待员 | 是 | EmployeePicker，对应 `receptionistUserid` |
| 表单模板 | 否 | 下拉，默认选中「县区码默认模板」，可改 |
| 学校人数 | 否 | 对应 `studentCount`，用于接待员数自动估算 |
| 场景 | 否 | daily_push / parent_meeting |

后端自动完成 schoolId 命名、默认模板 find-or-create、接待员绑定。**不暴露** 学校名 / 学校 ID / 服务老师 字段（自动处理或不适用）。列表页与编辑页对县区码做可辨识处理（徽标 / schoolId 只读）。

## 3. 入口与列表识别

`qrcode/list.html` 顶部按钮区（现「手动创建」「批量导入」旁）新增：

```html
<a href="/qrcodes/create-county" class="btn btn-outline-primary"><i class="bi bi-geo-alt"></i> 新建县区码</a>
```

列表页对 `schoolId` 以 `county:` 开头的活码行加「县区码」徽标（复用 `SchoolSelectionService.isCountyCode` 或 `startsWith(COUNTY_PREFIX)`），可选增加按类型筛选。实现点：`list.html` 行模板加 `th:if` 判断徽标；筛选在 `QrCodeController.list` 增加可选 `type=county` 查询参数（或仅展示徽标，不做筛选——本次以徽标为主，筛选列为可选项）。

## 4. 建码页 `qrcode/create-county.html`

- 独立模板，复用 `qrcode/create.html` 的样式与 EmployeePicker 组件。
- 字段见 §2；无「学校名称」「学校 ID」「服务老师」输入项。
- 表单模板下拉项来自 `formTemplates`（`FormTemplateService.listAll()`），默认选中由后端传 `defaultTemplateId` 决定（见 §5.1）。
- 提交到 `POST /qrcodes/create-county`，支持 AJAX（JSON 返回）与普通表单（redirect）两种，与 `POST /qrcodes/create` 一致。

## 5. 后端改动

### 5.1 `QrCodeController` — 新增 GET/POST `/create-county`

- `GET /qrcodes/create-county`：
  1. 复用 `createForm` 已有的标签缓存与降级逻辑（提取为私有方法，避免复制粘贴），注入 `cityOptions` / `districtOptions` / `userList` / `formTemplates`。
  2. 调用 `formTemplateService.ensureCountyTemplate()`（幂等），保证「县区码默认模板」已存在，并将其 ID 作为 `defaultTemplateId` 传入模型，供前端默认选中。
  3. 渲染 `qrcode/create-county`。
- `POST /qrcodes/create-county`：
  1. 校验 `city` / `district` / `receptionist` 非空，否则返回友好错误。
  2. 计算 `schoolId = "county:" + city + ":" + district`。
  3. 若未选表单模板，调用 `formTemplateService.ensureCountyTemplate()` 取得默认模板 ID；否则用所选模板 ID。
  4. 组装 `QrCodeCreateRequest`：`schoolId`、`schoolName = district`、`regionCity = city`、`regionDistrict = district`、`receptionistUserid`、`formTemplateId`、`studentCount`、`scene`（`serviceTeacherUserid` 留空）。
  5. 调用 `qrCodeService.create(req)`（复用现有流程，无需服务老师）。
  6. 记录操作日志 `operationLogService.log(getOperator(), "create", "qrcode", qr.getId().toString(), "创建县区码：" + qr.getSchoolName())`，与 `POST /create` 一致。
  7. 成功 / 失败按 AJAX / redirect 分流，与 `POST /create` 一致。

### 5.2 `FormTemplateService.ensureCountyTemplate()`

find-or-create「县区码默认模板」，**并发安全**：

```java
@Transactional
public FormTemplate ensureCountyTemplate() {
    return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
        .orElseGet(() -> {
            try {
                return create(COUNTY_TEMPLATE_NAME, "县区码默认收集模板",
                    null, null, null, null,
                    "[]", "{\"grade\":\"tag\",\"class\":\"tag\"}", null);
            } catch (DataIntegrityViolationException e) {
                // 并发下两个线程都 miss，唯一索引兜底后重查
                return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
                    .orElseThrow(() -> new RuntimeException("县区码默认模板创建失败", e));
            }
        });
}
```

常量 `COUNTY_TEMPLATE_NAME = "县区码默认模板"`。并发兜底依赖 §5.5 的唯一索引。

### 5.3 `FormTemplateRepository`

新增派生查询：

```java
Optional<FormTemplate> findByName(String name);
```

### 5.4 `QrCodeService.create` — 跳过县区码的 school 同步

现有 `create` 在保存活码后同步 `school` 表（约 297-315 行）：

```java
if (req.getSchoolId() != null) { ... schoolRepo.save(school); ... }
```

县区码的 `schoolId` 是 `county:市:区县` 合成 ID，不应落入 `school` 表——否则会污染家长「未分类」学校列表（`listSchools` 的「未分类」桶会查 `school` 表）。加 guard：

```java
if (req.getSchoolId() != null
    && !req.getSchoolId().startsWith(SchoolSelectionService.COUNTY_PREFIX)) {
    ...
}
```

复用 `SchoolSelectionService.COUNTY_PREFIX` 常量（同包，无需新 import 跨模块）。

### 5.5 数据库迁移 `V15__form_template_name_unique.sql`

给 `form_template.name` 加唯一索引，作为 `ensureCountyTemplate` 并发安全的兜底。参照 V11 模式：

1. 诊断重复：`SELECT name, COUNT(*) FROM form_template GROUP BY name HAVING COUNT(*) > 1;`
2. 若有重复：去重——保留最小 `id` 幸存行，将 `qr_code.form_template_id` / `qr_code_group.default_form_template_id` / `school_category.default_form_template_id` 引用重定向到幸存行，再删除重复行。
3. 建唯一索引（idempotent，INFORMATION_SCHEMA 守卫）：

```sql
ALTER TABLE form_template ADD UNIQUE INDEX uk_form_template_name (name);
```

（`form_template.name` 为 `nullable=false`，无需处理 NULL；实际 SQL 与去重细节在实施阶段按 V11 模板展开。）

## 6. 县区码默认模板

| 属性 | 值 |
|---|---|
| name | 县区码默认模板 |
| fields | `[]`（无额外收集字段，年级/班级由前端县区级联注入 `fieldData`） |
| tagMapping | `{"grade":"tag","class":"tag"}` |

年级/班级打标链路不变：前端 `fill.html` 提交 `fieldData.grade` / `fieldData.class` → `FormController.submit` 存 `fieldData` 并发布 TAG 流 → `TagService.applyFormTags` 按 `tagMapping` 打企微标签。因此本特性无需改动表单提交链路，只需保证县区码的 `formTemplateId` 指向该默认模板。

## 7. 边界处理

- **命名与 Phase 1 的关系**：Phase 1 测试/种子用 `county:区县名`（两段），本特性新建码用 `county:市:区县`（三段，含市名以避免跨市同名区县冲突，见 Ruling 6）。两者都命中 `startsWith("county:")`，`isCountyCode` 检测不变；已存在的两段县区码仍有效，无需迁移。
- **同市+区县唯一**：`schoolId = county:市:区县` 天然唯一，`existsBySchoolId` 兜底；重复创建返回友好错误（复用现有「学校ID已存在」异常，可改为「该县区码已存在」）。
- **跨市同名区县不冲突**：`城关区` 在兰州/天水都存在，但 `county:兰州市:城关区` 与 `county:天水市:城关区` 不同，`schoolName=城关区` + `regionCity` 组合也受 `existsBySchoolNameAndRegionCity` 约束（city 不同则不冲突）。
- **接待员必填**：县区码无服务老师，`buildContactWayJson` 会回退到接待员（`QrCodeService.java:1354-1368`）；若接待员也空则抛「请填写服务老师或接待员企微账号」——POST handler 提前校验更友好。
- **模板可改**：操作员在下拉切换后，该县区码 `formTemplateId` 指向所选模板；不覆盖默认模板本身。
- **企微 `state` 长度上限**：`state = schoolId`（`buildContactWayJson` 第 1379 行），回调侧用 `qrCodeRepo.findBySchoolId(state)` 精确反查。企微 state 上限约 30 字符，`county:甘南藏族自治州:碌曲县` ≈ 18 字符，甘肃地名安全；实施时在 POST 校验 `schoolId.length() <= 30`，超长报错（不截断，避免破坏回调反查）。
- **`schoolName=县区名` 潜在冲突**：`existsBySchoolNameAndRegionCity(县区名, 市)` 可能与手动建的「县区名」活码冲突（低概率）。若命中，报「该名称已存在」即可，操作员自行排查。
- **`regionDistrict` 写法一致性**：选校查询 `listSchools` 用 `qr.getRegionDistrict()` 查 `school` 表。操作员选的县区名必须与 school 表 `regionDistrict` 写法一致（同一数据源：districtOptions 企微标签组），否则选校查空。此为前提约定，建码页 autocomplete 直接复用 districtOptions 以降低错配概率。
- **默认模板改名漂移**：操作员若把「县区码默认模板」改名，`findByName` 下次找不到会重建，新老县区码指向不同模板。已知行为，不在本次处理（默认模板本质是「首次找不到就建」的约定；若需稳定锚点，后续可加 `is_county_default` 标记字段）。
- **编辑保护**：县区码沿用现有 `/qrcodes/{id}/edit` 编辑页，但 `schoolId` 字段对 `county:` 前缀活码设为只读（`disabled`）并加提示「县区码 schoolId 由系统生成，不可修改」，防止操作员误改前缀导致 `isCountyCode` 失效。
- **`welcomeConfig.collect_form` 冗余（非阻塞）**：`buildWelcomeConfig` 会为县区码生成默认 collect_form（年级/班级/孩子姓名）+ `form_callback_tag=true`。该 collect_form 仅被 `TransferService` 转接问候消费（`{{grade}}{{class}}{{child_name}}`），而县区码不直接转接（转接发生在目标学校活码），故为死配置。可选优化：县区码建码时令 collect_form 留空；不影响功能，实施时可顺带处理或忽略。

## 8. 测试

沿用现有集成测试基类（H2 + embedded-redis 1.4.3），新增/扩展：

1. **`FormTemplateService`**：`ensureCountyTemplate` 幂等——连续两次调用返回同一条记录（按 name 查找）；**预置一条同名模板后调用不新建**（覆盖 find 命中分支）；`tagMapping` = `{"grade":"tag","class":"tag"}`、`fields` = `[]`。
2. **`QrCodeService` 建县区码**：通过 `create` 传入 `schoolId = county:市:区县` + 接待员 + 默认模板，断言生成的 `QrCode.schoolId` / `schoolName` / `formTemplateId` / 接待员绑定正确，且 `school` 表无 `county:` 前缀记录（school 同步被跳过）。
3. **`QrCodeController` POST /create-county**（轻量）：断言返回 success、schoolId 命名正确；重复创建同市+区县返回错误；`schoolId.length() > 30` 时被拒。
4. **编辑页 schoolId 只读**（若实施 §7 编辑保护）：渲染 `/qrcodes/{id}/edit` 对 county 前缀活码的 schoolId 字段为只读。
5. **回归**：现有 196 测试全绿。

## 9. 非目标

- 不改动表单提交 / 在职继承 / 转接链路（Phase 1 已完成）。
- 不做县区码的批量导入入口（如未来有需求，另开 spec）。
- 不改 `SchoolSelectionService` 的选校/解析逻辑。
- 默认模板改名漂移（§7）不在本次处理。

## 10. 依赖与落点

- 依赖 Phase 1 分支 `worktree-district-qrcode-school-selection`（提供 `SchoolSelectionService.COUNTY_PREFIX`、`isCountyCode` 等）。
- 本特性在同一分支继续开发（或先合并 Phase 1 分支，视收尾决策而定——见 SDD ledger 待决项）。
- 新增数据库迁移 `V15__form_template_name_unique.sql`（见 §5.5）。
