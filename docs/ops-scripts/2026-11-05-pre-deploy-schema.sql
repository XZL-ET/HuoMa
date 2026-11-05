-- ============================================================
-- 2026-11-05 上线前生产 SQL（用户手动执行，禁止代跑）
--
-- 背景：本批部署包含「告警降噪」（已在 master，实体已读写以下两列）。
--       agent_alert 表在生产已存在，启动时 schema.sql 里的
--       CREATE TABLE IF NOT EXISTS 不会补列，必须先执行本脚本。
--
-- 顺序：预检 → 备份 → ALTER → 回填 → 校验。建议低峰执行。
-- 回滚：列保留即可，旧 jar 不读这两列，无需删除。
-- ============================================================

-- 1) 预检：两列是否已存在（都查不到才需要执行第 3 步）
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'agent_alert'
  AND COLUMN_NAME IN ('last_seen_at', 'occurrence_count');

-- 2) 备份（在服务器 shell 执行，按实际连接参数替换；日期用当天）
-- mysqldump -h <RDS地址> -u <用户> -p bookstore_qrcode agent_alert \
--   > agent_alert_20261105.sql

-- 3) 加列 + 合并查询索引（仅当第 1 步查不到两列时执行）
ALTER TABLE agent_alert
  ADD COLUMN last_seen_at DATETIME NULL AFTER resolved_at,
  ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER last_seen_at,
  ADD INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id);

-- 4) 回填新列
UPDATE agent_alert SET last_seen_at = created_at WHERE last_seen_at IS NULL;

-- 5) 校验
--   5.1 两列应存在
SHOW COLUMNS FROM agent_alert LIKE 'last_seen_at';
SHOW COLUMNS FROM agent_alert LIKE 'occurrence_count';
--   5.2 应为 0
SELECT COUNT(*) AS null_last_seen FROM agent_alert WHERE last_seen_at IS NULL;
--   5.3 索引应存在
SHOW INDEX FROM agent_alert WHERE Key_name = 'idx_type_status_key';

-- ============================================================
-- 部署后核对（新 jar 启动并跑完首轮巡检后执行）
-- 1. 新表自动创建（部门准入白名单，空表 = 功能未启用）
SELECT COUNT(*) AS pool_allowed_department_rows FROM pool_allowed_department;
-- 2. 告警 open 数应明显下降，且同一 key 的重复告警不再堆积
SELECT alert_type, status, COUNT(*) AS cnt
FROM agent_alert
GROUP BY alert_type, status
ORDER BY cnt DESC
LIMIT 20;
-- 3. 日上限由代码自动抬升（部署后首次全量同步），无需手工跑
--    docs/data-repair-20260920-global-pool-daily-max.sql
SELECT CASE a.role
         WHEN 'receptionist' THEN 'receptionist'
         ELSE 'service/dual'
       END AS role_group,
       p.daily_max, COUNT(*) AS cnt
FROM global_agent_pool p
JOIN agent a ON a.userid = p.agent_userid
GROUP BY role_group, p.daily_max
ORDER BY role_group, p.daily_max;
