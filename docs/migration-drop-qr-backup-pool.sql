-- ============================================================
-- 迁移脚本：删除 qr_backup_pool 表，迁移激活代理到全局池
-- 执行前请备份数据库！
-- 执行顺序：1. 先执行迁移 INSERT，2. 验证全局池数据，3. DROP TABLE
-- ============================================================

-- Step 1: 迁移仍激活的后备代理到全局池
INSERT INTO global_agent_pool (agent_userid, daily_max, daily_current,
    sort_order, status, created_at, updated_at)
SELECT qbp.agent_userid, COALESCE(qbp.daily_max, 100), 0,
       (SELECT COALESCE(MAX(sort_order), 0) FROM global_agent_pool gap2)
           + ROW_NUMBER() OVER (ORDER BY qbp.sort_order),
       'standby', NOW(), NOW()
FROM qr_backup_pool qbp
WHERE qbp.status = 'activated'
  AND NOT EXISTS (
      SELECT 1 FROM global_agent_pool gap WHERE gap.agent_userid = qbp.agent_userid
  );

-- Step 2: 验证迁移结果（确认迁移数量后执行删除）
-- SELECT COUNT(*) FROM qr_backup_pool WHERE status = 'activated';

-- Step 3: 删除旧表（经 DBA 审批后执行）
-- DROP TABLE IF EXISTS qr_backup_pool;
