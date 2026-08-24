-- V7: 拆分 retryCount 语义 + 乐观锁（idempotent: INFORMATION_SCHEMA 守卫）
-- 将 CustomerTransfer.retryCount 的两种语义分离为 poll_count 和 retry_count
-- 同时添加 version 列用于乐观锁，防止并发更新覆盖

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

-- 历史数据迁移：将现有 retry_count 值复制到 poll_count（idempotent: 只影响 pending_confirm 行）
UPDATE customer_transfer SET poll_count = retry_count WHERE status = 'pending_confirm' AND poll_count = 0;
