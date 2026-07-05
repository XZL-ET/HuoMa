-- V9: 确保 customer_transfer 的 poll_count 和 version 列存在（idempotent）
-- V7 的非幂等 ALTER TABLE 在某些环境下可能未正确执行，
-- 此迁移作为安全网，使用 INFORMATION_SCHEMA 守卫确保两列一定存在。
-- 同时迁移可能遗漏的历史数据。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND COLUMN_NAME = 'poll_count') = 0,
    'ALTER TABLE customer_transfer ADD COLUMN poll_count INT NOT NULL DEFAULT 0 COMMENT ''轮询追踪次数 (pending_confirm状态)'' AFTER retry_count',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND COLUMN_NAME = 'version') = 0,
    'ALTER TABLE customer_transfer ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号'' AFTER updated_at',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据迁移：将 retry_count 复制到 poll_count（仅影响 pending_confirm 且尚未迁移的行）
UPDATE customer_transfer SET poll_count = retry_count WHERE status = 'pending_confirm' AND poll_count = 0;
