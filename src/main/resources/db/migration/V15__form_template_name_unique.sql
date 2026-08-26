-- V15: form_template.name 唯一约束
-- 并发 ensureCountyTemplate 的 find-or-create 竞态会插入重复 name，
-- 导致 findByName 抛 IncorrectResultSizeDataAccessException。
-- 部署前诊断：
--   SELECT name, COUNT(*) FROM form_template GROUP BY name HAVING COUNT(*) > 1;

-- Step 1: 删除重复 name（保留最小 id 幸存行）
-- form_template 的引用（qr_code.form_template_id / qr_code_group.default_form_template_id /
-- school_category.default_form_template_id）无数据库级 FK 约束，无需重定向。
DELETE ft FROM form_template ft
JOIN (SELECT name, MIN(id) AS survivor_id FROM form_template GROUP BY name HAVING COUNT(*) > 1) ds
  ON ft.name = ds.name AND ft.id != ds.survivor_id;

-- Step 2: 建唯一索引（idempotent）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'form_template' AND INDEX_NAME = 'uk_form_template_name') = 0,
    'ALTER TABLE form_template ADD UNIQUE INDEX uk_form_template_name (name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
