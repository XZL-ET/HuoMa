-- V19: qr_agent 增加 is_fallback 标记（失联替补提拔的 dual）
-- 服务老师企微失联时，自愈逻辑提拔最资深接待员为 dual 作替补转接目标，
-- 标记 is_fallback=1；原服务老师恢复后由同步逻辑降回 receptionist。
-- 与 is_temporary（临时顶替接待员，日重置释放）语义不同，故独立字段。
-- 通过 INFORMATION_SCHEMA 守卫保证幂等。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_agent'
       AND COLUMN_NAME = 'is_fallback') = 0,
    'ALTER TABLE qr_agent ADD COLUMN is_fallback TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否失联替补提拔的 dual''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
