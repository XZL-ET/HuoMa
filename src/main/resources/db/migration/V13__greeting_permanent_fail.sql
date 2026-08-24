-- V13: 欢迎语永久失败标记列（idempotent: INFORMATION_SCHEMA 守卫）
-- 与 schema.sql 内联列保持一致；通过存在性检查避免新库/重建时 Duplicate column

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND COLUMN_NAME = 'greeting_permanent_fail') = 0,
    'ALTER TABLE customer_transfer ADD COLUMN greeting_permanent_fail BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''欢迎语是否永久失败（区分真正成功和放弃重试）'' AFTER greeting_sent',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
