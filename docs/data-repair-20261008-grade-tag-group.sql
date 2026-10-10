-- 2026-10-08 年级标签组修复 ① 生产库 form_template.tag_mapping
--
-- 背景：commit 6ce23b7 (2026-07-15) 起 applyFormTags 默认组由「学校」改为「学校-{市}」，
-- 模板映射里的 "tag"（不带组名）随之把年级标签打进「学校-{市}」组，
-- 而非 2026-07-15 建好的专用「年级」组。改映射为 "tag:年级"（显式指定组）修根因。
--
-- 影响：仅改表单模板默认映射，立即生效（模板在提交时从库读，不需发版）。
-- 历史 customer_tag 绑定不受影响（本文件不含数据迁移）。

-- ===== 回滚（备份，执行 UPDATE 前保存的原值）=====
-- 11 行原值均为 {"grade": "tag"}
-- UPDATE form_template SET tag_mapping = JSON_SET(tag_mapping, '$.grade', 'tag') WHERE id IN (4,5,6,7,8,9,10,11,12,14,15);

-- ===== 正向 =====
UPDATE form_template SET tag_mapping = JSON_SET(tag_mapping, '$.grade', 'tag:年级')
WHERE id IN (4,5,6,7,8,9,10,11,12,14,15);

-- ===== 校验 =====
-- SELECT id, name, tag_mapping FROM form_template ORDER BY id;
