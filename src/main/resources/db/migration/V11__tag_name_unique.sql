-- V11: tag.name 唯一约束 —— 同一标签名只能有一条记录（idempotent）
-- 并发 getOrCreateTag() 在不带唯一约束的列上产生重复行，
-- 导致 findByName() 抛出 IncorrectResultSizeDataAccessException。
-- 本迁移：按 name 去重 → 重分配 customer_tag / parent_id 外键 → 删除重复 → 添加 UNIQUE INDEX。
--
-- 部署前诊断（在生产库检查重复规模）：
--   SELECT name, COUNT(*) AS cnt,
--          GROUP_CONCAT(id ORDER BY id) AS ids,
--          GROUP_CONCAT(wecom_tag_id ORDER BY id) AS wecom_ids
--   FROM tag GROUP BY name HAVING cnt > 1;

-- ============================================================
-- Step 0: 清理可能的残留临时表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS dup_tag_mapping;
DROP TEMPORARY TABLE IF EXISTS dup_tag_survivor;
DROP TEMPORARY TABLE IF EXISTS dup_tag_best_wecom;

-- ============================================================
-- Step 1: 对每个重复 name 组，确定幸存行（最低 id）并建立映射
-- ============================================================
CREATE TEMPORARY TABLE dup_tag_survivor AS
SELECT name, MIN(id) AS survivor_id
FROM tag
GROUP BY name
HAVING COUNT(*) > 1;

-- 构建完整映射表（dup_id → survivor_id），后续步骤复用
CREATE TEMPORARY TABLE dup_tag_mapping AS
SELECT t.id AS dup_id, ds.survivor_id, ds.name
FROM tag t
JOIN dup_tag_survivor ds ON t.name = ds.name AND t.id != ds.survivor_id;

-- ============================================================
-- Step 2a: 将 customer_tag 中指向重复 tag 的 FK 重定向到幸存 tag
--         使用派生表 JOIN，兼容 MySQL 5.7
-- ============================================================
UPDATE customer_tag ct
JOIN dup_tag_mapping dm ON ct.tag_id = dm.dup_id
SET ct.tag_id = dm.survivor_id;

-- ============================================================
-- Step 2b: 删除因 FK 重定向产生的 customer_tag 重复行
--         （同一个 customer_id + tag_id 可能出现两次，保留 MIN(id)）
-- ============================================================
DELETE ct FROM customer_tag ct
JOIN customer_tag ct2 ON ct.customer_id = ct2.customer_id
  AND ct.tag_id = ct2.tag_id
  AND ct.id > ct2.id;

-- ============================================================
-- Step 3: 将重复行中非空的 wecom_tag_id 继承到幸存行
--         分两步避免 MySQL "can't reopen table" 限制
-- ============================================================
-- Step 3a: 为每个重复组找到最佳 wecom_tag_id（优先取 id 最小的非空值）
CREATE TEMPORARY TABLE dup_tag_best_wecom AS
SELECT ds.survivor_id,
       (SELECT t_dup.wecom_tag_id FROM tag t_dup
        WHERE t_dup.name = ds.name
          AND t_dup.id != ds.survivor_id
          AND t_dup.wecom_tag_id IS NOT NULL
          AND t_dup.wecom_tag_id != ''
        ORDER BY t_dup.id ASC
        LIMIT 1) AS best_wecom_id
FROM dup_tag_survivor ds;

-- Step 3b: 将最佳 wecomTagId 应用到幸存行（仅当幸存行本身缺失时）
UPDATE tag t_survivor
JOIN dup_tag_best_wecom dbw ON t_survivor.id = dbw.survivor_id
SET t_survivor.wecom_tag_id = dbw.best_wecom_id
WHERE (t_survivor.wecom_tag_id IS NULL OR t_survivor.wecom_tag_id = '')
  AND dbw.best_wecom_id IS NOT NULL;

-- ============================================================
-- Step 4: 将 parent_id 指向重复 tag 的行重定向到幸存 tag
--         使用派生表 JOIN，兼容 MySQL 5.7
-- ============================================================
UPDATE tag t_child
JOIN dup_tag_mapping dm ON t_child.parent_id = dm.dup_id
SET t_child.parent_id = dm.survivor_id;

-- ============================================================
-- Step 5: 删除重复 tag 行（保留幸存行）
--         FK 引用已全部重定向，DELETE 不会违反外键约束
-- ============================================================
DELETE t FROM tag t
JOIN dup_tag_survivor ds ON t.name = ds.name AND t.id != ds.survivor_id;

-- ============================================================
-- Step 6: 创建唯一约束（idempotent: INFORMATION_SCHEMA 守卫）
-- ============================================================
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND INDEX_NAME = 'uk_tag_name') = 0,
    'ALTER TABLE tag ADD UNIQUE INDEX uk_tag_name (name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 7: 清理临时表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS dup_tag_mapping;
DROP TEMPORARY TABLE IF EXISTS dup_tag_survivor;
DROP TEMPORARY TABLE IF EXISTS dup_tag_best_wecom;
