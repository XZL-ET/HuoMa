-- ============================================================
-- 数据修复：global_agent_pool.daily_max 存量 100 → 150（待执行）
-- 生成时间: 2026-09-20
-- 背景：员工管理页「日上限」读 global_agent_pool.daily_max，
--       存量员工在"默认值还是 100"的年代入池，字段落库为 100。
--       运营已在活码(qr_agent.daily_max)改过，但接待员日限判定
--       实际走全局池 daily_max（AgentRotationService.checkAndRotate），
--       两者互不同步，导致 888 名接待员每天仍被 100 卡住。
-- 根因：syncToGlobalPool / ensureInPool 只写新增记录，从不回填存量。
-- 前提：企微单成员日被动添加上限 > 150（已与运营确认）。
-- 目标值按角色：接待员=150，服务老师/双角色=300（与活码侧一致）。
-- ============================================================

-- 0. 备份受影响行（daily_max = 100 的 1051 条）
DROP TABLE IF EXISTS global_agent_pool_backup_dmax_20260920;
CREATE TABLE global_agent_pool_backup_dmax_20260920 AS
SELECT * FROM global_agent_pool WHERE daily_max = 100;

-- 1. 更新（事务内，按 agent.role 映射目标值）
START TRANSACTION;

UPDATE global_agent_pool p
JOIN agent a ON a.userid = p.agent_userid
SET p.daily_max = CASE a.role
        WHEN 'receptionist' THEN 150
        WHEN 'service'      THEN 300
        WHEN 'dual'         THEN 300
        ELSE p.daily_max
    END,
    p.updated_at = CURRENT_TIMESTAMP
WHERE p.daily_max = 100;

COMMIT;

-- ============================================================
-- 执行后校验（手动跑以下查询确认）
--   SELECT a.role, p.daily_max, COUNT(*)
--   FROM global_agent_pool p JOIN agent a ON a.userid = p.agent_userid
--   WHERE p.agent_userid IN (SELECT agent_userid FROM global_agent_pool_backup_dmax_20260920)
--   GROUP BY a.role, p.daily_max;
--   预期：receptionist→150（888 人），service→300（160 人），dual→300（3 人）。
-- 全局分布复核：
--   SELECT daily_max, COUNT(*) FROM global_agent_pool GROUP BY daily_max;
--   预期：daily_max=100 归零，150 约 1038 条，300 约 163 条，200 仍 1 条。
-- 回滚（如误操作）：
--   START TRANSACTION;
--   DELETE FROM global_agent_pool WHERE daily_max = 150 AND agent_userid IN
--     (SELECT agent_userid FROM global_agent_pool_backup_dmax_20260920);
--   INSERT INTO global_agent_pool
--     SELECT * FROM global_agent_pool_backup_dmax_20260920;
--   COMMIT;
-- ============================================================
