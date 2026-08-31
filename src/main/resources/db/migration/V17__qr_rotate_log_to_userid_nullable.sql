-- V17: qr_rotate_log.to_userid 改为可空（纯下码无接替场景记录 null）
-- 服务老师日限下码时不补人，轮换日志只有 from_userid（下码者）而无 to_userid（接替者）。
-- 通过 INFORMATION_SCHEMA 守卫保证幂等。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_rotate_log'
       AND COLUMN_NAME = 'to_userid' AND IS_NULLABLE = 'NO') = 1,
    'ALTER TABLE qr_rotate_log MODIFY COLUMN to_userid VARCHAR(100) NULL COMMENT ''轮换后/新分配老师userid（纯下码无接替为空）''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
