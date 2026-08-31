-- V18: qr_agent 增加 is_temporary 标记（临时顶替接待员，次日释放）
-- 服务老师是活码唯一 active 成员时日限下码前，从同部门临时补入的接待员标记 is_temporary=1，
-- 次日服务老师恢复 active 后由每日重置释放（移除临时接待员）。
-- 通过 INFORMATION_SCHEMA 守卫保证幂等。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_agent'
       AND COLUMN_NAME = 'is_temporary') = 0,
    'ALTER TABLE qr_agent ADD COLUMN is_temporary TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否临时顶替接待员''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
