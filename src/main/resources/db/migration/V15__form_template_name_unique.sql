-- V15: form_template.name 唯一约束 —— 同一模板名只能有一条记录（idempotent）
-- 并发 ensureCountyTemplate 的 find-or-create 竞态会插入重复 name，
-- 导致 findByName() 抛出 IncorrectResultSizeDataAccessException。
-- 本迁移：按 name 去重 → 重分配 qr_code / qr_code_group / school_category 的
-- 应用级外键引用到幸存行 → 删除重复 → 添加 UNIQUE INDEX。
--
-- 部署前诊断（在生产库检查重复规模）：
--   SELECT name, COUNT(*) FROM form_template GROUP BY name HAVING COUNT(*) > 1;

-- ============================================================
-- Step 0: 清理可能的残留临时表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS dup_ft_survivor;
DROP TEMPORARY TABLE IF EXISTS dup_ft_mapping;

-- ============================================================
-- Step 1: 对每个重复 name 组，确定幸存行（最低 id）
-- ============================================================
CREATE TEMPORARY TABLE dup_ft_survivor AS
SELECT name, MIN(id) AS survivor_id
FROM form_template
GROUP BY name
HAVING COUNT(*) > 1;

-- ============================================================
-- Step 2: 构建完整映射表（dup_id → survivor_id），后续步骤复用
-- ============================================================
CREATE TEMPORARY TABLE dup_ft_mapping AS
SELECT ft.id AS dup_id, ds.survivor_id
FROM form_template ft
JOIN dup_ft_survivor ds ON ft.name = ds.name AND ft.id != ds.survivor_id;

-- ============================================================
-- Step 3a: 将 qr_code.form_template_id 指向重复模板的引用重定向到幸存行
--         应用级 FK（无 DB 级约束），须在 DELETE 前显式重定向
-- ============================================================
UPDATE qr_code q
JOIN dup_ft_mapping dm ON q.form_template_id = dm.dup_id
SET q.form_template_id = dm.survivor_id;

-- ============================================================
-- Step 3b: 重定向 qr_code_group.default_form_template_id
-- ============================================================
UPDATE qr_code_group g
JOIN dup_ft_mapping dm ON g.default_form_template_id = dm.dup_id
SET g.default_form_template_id = dm.survivor_id;

-- ============================================================
-- Step 3c: 重定向 school_category.default_form_template_id
-- ============================================================
UPDATE school_category c
JOIN dup_ft_mapping dm ON c.default_form_template_id = dm.dup_id
SET c.default_form_template_id = dm.survivor_id;

-- ============================================================
-- Step 4: 删除重复模板（保留幸存行）
--         应用级 FK 引用已全部重定向，DELETE 不会残留悬空引用
-- ============================================================
DELETE ft FROM form_template ft
JOIN dup_ft_survivor ds ON ft.name = ds.name AND ft.id != ds.survivor_id;

-- ============================================================
-- Step 5: 创建唯一约束（idempotent: INFORMATION_SCHEMA 守卫）
-- ============================================================
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'form_template' AND INDEX_NAME = 'uk_form_template_name') = 0,
    'ALTER TABLE form_template ADD UNIQUE INDEX uk_form_template_name (name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 6: 清理临时表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS dup_ft_survivor;
DROP TEMPORARY TABLE IF EXISTS dup_ft_mapping;
