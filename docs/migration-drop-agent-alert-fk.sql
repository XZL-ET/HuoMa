-- ============================================================
-- 迁移脚本：删除 agent_alert.agent_userid 的外键约束
--
-- 背景：
--   告警是「弱关联」——关联对象可能是员工(agent)，也可能是管理员等
--   非 agent 账号，甚至是系统级告警(agent_userid = NULL)。外键约束与
--   该语义冲突（此前只能靠传 NULL 绕开），并且插入 agent_alert 时
--   InnoDB 会对 agent 父行加共享锁(S)，与熔断等场景的
--   SELECT ... FOR UPDATE 悲观锁(X) 产生死锁，导致熔断事务被回滚。
--
-- 执行前请备份数据库！
-- 外键名以实际库为准（MySQL 默认自动命名为 agent_alert_ibfk_1），
-- 执行前用 Step 0 的查询确认后，再用正确名字替换 Step 1。
-- ============================================================

-- Step 0: 确认外键名（核对后替换 Step 1 中的外键名）
SELECT CONSTRAINT_NAME
FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND TABLE_NAME = 'agent_alert'
  AND REFERENCED_TABLE_NAME = 'agent';

-- Step 1: 删除外键（经 DBA 审批后执行）
ALTER TABLE agent_alert DROP FOREIGN KEY agent_alert_ibfk_1;
