# 未打标签家长补发表单并自动打标 — 设计方案（送试题包装）

日期：2026-09-11
状态：待评审

## 1. 背景与问题

- 现状：全量客户约 11 万，其中约 **7.7 万（~70%）没有年级/科目表单标签**。
- 原因：欢迎语表单只能随 `send_welcome_msg` 的 `welcome_code` 发一次（约 20 秒有效、一次性），
  大量家长添加时收到表单后没有填写，后续无再次触达渠道。
- 目标：把这批家长**再触达一次表单**，让他们补填年级 + 科目，**自动打上标签**。
- 包装：用「填表送本年级、本科目试题」的钩子提升填写意愿，试题跳转顺带带动公众号访问量与自愿关注。

> 补发表单为**独立新表单**（复用 `FormTemplate` 模板体系，不改造既有 `FormController` / `/api/form/submit`），
> 字段为「年级（单选）+ 科目（多选）」，二者均打标签，映射细分为「年级 × 科目」。

## 2. 根因分析：为什么之前的方案都不行

| 方案 | 结论 |
|------|------|
| 直发 `externalcontact/message/send` | 返回 48002（api forbidden），转接欢迎语从未成功过，此路已死 |
| 家校沟通（学校通知） | 用 `to_parent_userid` 等，身份体系与客户联系不互通，够不到现有 11 万客户 |
| 群发 `add_msg_template` | **唯一能合规触达现有客户的通道**，但两个硬约束见下 |

群发的两个硬约束：

1. **半自动**：调用后需员工在企微端点「确认」才真正发出（非静默）。
2. **内容千人一面**：同一条群发里所有客户收到一模一样的链接，无法给每人拼上 `c=customerId`。

由此暴露的核心矛盾：

> 表单打标签依赖链接里的 `customerId` → 反查 `external_userid` → `mark_tag`。
> 群发链接带不了个人 `customerId`，所以家长填了表单，系统不知道是谁填的，无法自动打标签。

## 3. 唯一可 scale 的解：openid 反查

核心思路：用微信 `openid` 做桥梁，把「谁填了表单」对应回 `external_userid`。

数据流：

```
1. 批量建映射：external_userid --convert_to_openid--> openid
   落表 customer_openid_map(external_userid, openid, appid)

2. 群发：按员工分批生成 add_msg_template 任务（link 卡片 → 统一表单链接，不带 customerId）

3. 家长在微信点链接 → 公众号网页授权(snsapi_base，静默无感) → 拿到 openid

4. 反查：openid --查 customer_openid_map--> external_userid --> customerId --> sourceQrId(学校)

5. 渲染对应学校的表单 → 家长选年级（单选）+ 科目（多选）提交

6. 打标签：走现有 applyFormTags / markTag 链路（年级打「年级」标签，每个科目打「科目」组标签）

7. 返回试题：按「年级 × 科目」查 paper_link，返回一组试题链接，前端逐个展示，家长自愿关注公众号
```

## 4. 产品包装：填表送试题

- **表单文案**：副标题「填写年级和科目，免费领本年级试题」。字段为「年级（单选）+ 科目（多选）」。
- **试题呈现**：PDF → 图片 → 公众号图文推文（按「年级 × 科目」各一篇，微信内查看体验最佳）。
- **跳转**：提交成功后，按家长所选「年级 × 科目」返回**一组试题链接**（每个所选科目一条），前端在表单页内展示，家长逐条点开。
- **关注**：自愿、不强制。推文顶部天然露出公众号头像 + 关注按钮，家长觉得有用就顺手关注；不设「关注后才能领」门槛，规避诱导关注风险。

## 5. 前置条件（业务侧）

| 前提 | 状态 | 说明 |
|------|------|------|
| 公众号与企业微信同主体 | ✅ 已确认 | 否则 openid 对不上 |
| 公众号已认证 | ⚠️ 待确认 | 网页授权 `snsapi_base` 要求认证号 |
| 公众号绑定到「客户联系→微信开发者ID」 | ⏳ 待操作 | 后台几分钟 |
| 网页授权域名 `huoma.gsxhsd.com` | ⏳ 待配置 | 需在域名根放 `MP_verify_xxx.txt` 校验文件 |
| 试题图文内容 | ⏳ 运营侧 | PDF 转图片、按「年级 × 科目」各一篇推文、拿永久链接 |

## 6. 关键组件改动

### 6.1 `WecomApiClient` / 新增 `MpApiClient`

- `addMsgTemplate(chatType, externalUserids, sender, text, attachments)` → `externalcontact/add_msg_template`
- `convertToOpenid(externalUserid)` → `externalcontact/convert_to_openid`
- `MpApiClient.getOpenidByCode(code)` → 公众号 `sns/oauth2/access_token`（公众号 appid/secret，`api.weixin.qq.com` 基址）

新增配置：`wecom.mp-appid` / `wecom.mp-secret`。

### 6.2 新表

- `customer_openid_map`：`external_userid`（唯一）、`openid`、`appid`、`created_at`
- `form_resend_task`：群发任务 + 进度 + 结果（msgid、状态、覆盖人数）
- `paper_link`：`grade`（年级）、`subject`（科目）、`title`、`paper_url`（试题推文永久链接）；`(grade, subject)` 唯一

### 6.3 补发表单（独立，不动既有表单）

新增 `FormResendController`，**零耦合**于既有 `FormController`，复用 `FormTemplate` 模板体系：

- 入口 `GET /form/resend`：群发链接不带个人 `customerId`，正式流程靠公众号网页授权（`snsapi_base`）拿 openid 反查身份（延后接入）。当前支持：
  - `?c={customerId}` 直接渲染（用于测试/调试）；
  - 无 `c` 时渲染「待接入公众号授权」兜底页。
- 提交 `POST /api/form/resend-submit`：复用 `form_submit` 打标链路，按「年级 × 科目」返回试题链接列表。
- 表单字段：`grade`（年级，单选）+ `subject`（科目，多选），标签映射 `{"grade":"tag","subject":"tag:科目"}`。
- 模板锚点：`ensureResendTemplate()` 幂等 find-or-create「补发表单默认模板」，文案/字段/映射均可由后台模板编辑器自定义。

### 6.4 管理后台「补发表单」页

- 按员工/学校筛选未打标签家长（`currentAgent` 非空、`sourceQrId` 非空、无 `source=form` 的 `CustomerTag`）。
- 预览数量，一键发起群发（按员工分批，每批 ≤ 1 万 external_userid，压低员工确认次数）。
- 展示群发任务进度与结果。
- 「年级 × 科目 → 试题链接」映射的增删改查。

## 7. openid 反查验证（Spike，先做）

整套方案唯一的技术不确定性，必须先验证再投入完整开发：

1. 绑好公众号后，取一个测试家长的 `external_userid`，调 `convert_to_openid` 拿 openid。
2. 同一家长用微信打开一个挂在该公众号下的测试页，走网页授权拿 openid。
3. 比对两个 openid 是否一致。

**一致** → 方案成立，按第 6 节实施。
**不一致** → 确认 `convert_to_openid` 返回的 openid 挂在哪个 appid 下，可能需要传 `appid` 参数或改用 `unionid` 桥接（公众号需绑定微信开放平台拿统一 unionid）。

## 8. 分阶段实施

1. **Spike（半天）**：验证 openid 反查可行性。
2. **最小可用（4~5 天）**：`add_msg_template` + `convert_to_openid` 映射表 + 表单 OAuth 反查 + 管理后台发起群发 + 提交跳转试题图文。
3. **扩展**：群发进度回执、自动重试、漏发补发、按学校/年级细分统计。

## 9. 风险与边界

- `convert_to_openid` 仅支持 `wm` 开头的微信外部联系人，不支持 `wo`（企业微信联系人）。家校场景家长均为微信用户，符合。
- 群发频率限制：每客户每月接收条数上限约为当月天数，补发一次不会触顶。
- 群发需员工确认，存在「员工不点确认」的运营风险，需配合通知/催办机制。
- 试题图文依赖公众号运营侧发布，且永久链接需稳定（推文删除则链接失效，需重新映射）。
- 表单 OAuth 测试有摩擦：网页授权需真微信点链接，单测只能 mock。
