-- V21: 移除 agent_alert.agent_userid 外键
-- 告警是「弱关联」：关联对象可能是员工(agent)、管理员等非 agent 账号，甚至系统级告警(NULL)。
-- 外键约束与弱关联语义冲突（此前只能靠传 NULL 绕开），且插入 agent_alert 时 InnoDB 会对
-- agent 父行加共享锁(S)，与熔断等场景的 SELECT ... FOR UPDATE 悲观锁(X) 产生死锁。
-- 通过 INFORMATION_SCHEMA 守卫保证幂等。

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert'
       AND REFERENCED_TABLE_NAME = 'agent') > 0,
    CONCAT('ALTER TABLE agent_alert DROP FOREIGN KEY `',
           (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert'
              AND REFERENCED_TABLE_NAME = 'agent' LIMIT 1), '`'),
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
