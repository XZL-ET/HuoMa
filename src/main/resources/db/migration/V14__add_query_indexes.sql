-- V14: 补高频查询索引（idempotent: INFORMATION_SCHEMA.STATISTICS 守卫）
-- idx_customer_transfer_to_userid_status: 加速继承追踪按目标员工+状态查询
-- idx_customer_added_agent_school_addtime: 加速客户列表按接待员+学校+时间筛选

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND INDEX_NAME = 'idx_customer_transfer_to_userid_status') = 0,
    'CREATE INDEX idx_customer_transfer_to_userid_status ON customer_transfer (to_userid, status)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_added_agent_school_addtime') = 0,
    'CREATE INDEX idx_customer_added_agent_school_addtime ON customer (added_agent, school_id, add_time)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
