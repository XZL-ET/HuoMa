-- ============================================================
-- 数据迁移：qr_backup_pool → global_agent_pool
-- ============================================================
-- 执行前提：global_agent_pool 表已创建（schema.sql 已执行）
-- 执行方式：mysql -u root -p bookstore_qrcode < docs/migration-global-pool.sql
-- 幂等性：使用 INSERT IGNORE / NOT EXISTS 防止重复执行报错
-- ============================================================

-- 1. 将 qr_backup_pool 中的 standby/activated 员工迁移到 global_agent_pool
--    同一员工只保留一条记录，按最低 sortOrder 优先
INSERT INTO global_agent_pool (agent_userid, daily_max, sort_order, status)
SELECT b.agent_userid,
       COALESCE(b.daily_max, 200),
       MIN(b.sort_order),                 -- 取最小排序号（排最前面）
       'standby'                          -- 统一重置为 standby
FROM qr_backup_pool b
WHERE b.status IN ('standby', 'activated')
  AND NOT EXISTS (
      SELECT 1 FROM global_agent_pool g WHERE g.agent_userid = b.agent_userid
  )
GROUP BY b.agent_userid, b.daily_max;

-- 2. 将每个活码的 active QrAgent 也加入全局池（如果还没在池中）
--    确保所有在岗员工都在全局池中有记录
INSERT IGNORE INTO global_agent_pool (agent_userid, daily_max, sort_order, status)
SELECT qa.agent_userid,
       COALESCE(qa.daily_max, 200),
       0,
       'standby'
FROM qr_agent qa
WHERE qa.status = 'active';

-- 3. 更新 qr_code 的 initial_agent_count
--    已有活码默认 1（保持原有行为）
UPDATE qr_code SET initial_agent_count = 1 WHERE initial_agent_count IS NULL;

-- 4. 验证迁移结果
SELECT '迁移前 qr_backup_pool 记录数' AS step, COUNT(*) AS count FROM qr_backup_pool
UNION ALL
SELECT '迁移后 global_agent_pool standby 数', COUNT(*) FROM global_agent_pool WHERE status = 'standby'
UNION ALL
SELECT 'global_agent_pool 总记录数', COUNT(*) FROM global_agent_pool;
