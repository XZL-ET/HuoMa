-- V15: api_failed 退避重试时间列（idempotent: INFORMATION_SCHEMA 守卫）
-- 为在职继承失败重试引入指数退避：首次失败 30min → 2h → 8h → 24h 封顶。
-- retryFailedTransfers 仅扫描 next_retry_at 已到期（或为 null）的记录，
-- 避免固定 30 分钟周期内无脑重试冲击企微 API。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND COLUMN_NAME = 'next_retry_at') = 0,
    'ALTER TABLE customer_transfer ADD COLUMN next_retry_at DATETIME COMMENT ''下次重试时间 (api_failed退避重试)'' AFTER poll_count',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 索引：加速退避重试扫描（status + next_retry_at）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND INDEX_NAME = 'idx_transfer_status_next_retry') = 0,
    'CREATE INDEX idx_transfer_status_next_retry ON customer_transfer (status, next_retry_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
