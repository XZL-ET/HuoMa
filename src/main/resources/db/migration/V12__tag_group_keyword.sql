-- V12: tag 表添加 group_keyword 列，将唯一约束从 name 改为 (name, group_keyword)
-- 支持同名标签在不同企微标签组下独立存在（如"定西市"同时在"市州"组和"县区"组）
--
-- 背景：市和区同名时（如定西市/定西市），getOrCreateTag() 按名查找会命中同一条记录，
-- 导致三级地域标签退化为两级。加上 group_keyword 维度后，每个组独立维护标签。

-- ============================================================
-- Step 1: 添加 group_keyword 列（idempotent: INFORMATION_SCHEMA 守卫）
-- ============================================================
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND COLUMN_NAME = 'group_keyword') = 0,
    'ALTER TABLE tag ADD COLUMN group_keyword VARCHAR(100) NOT NULL DEFAULT ''''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 2: 删除旧的 UNIQUE(name) 索引
-- ============================================================
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND INDEX_NAME = 'uk_tag_name') > 0,
    'ALTER TABLE tag DROP INDEX uk_tag_name',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 3: 创建新的 UNIQUE(name, group_keyword) 索引
-- ============================================================
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND INDEX_NAME = 'uk_tag_name_group') = 0,
    'ALTER TABLE tag ADD UNIQUE INDEX uk_tag_name_group (name, group_keyword)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
