-- ============================================================
-- 数据修复：旧活码回填 department_id（按 active 服务老师主部门）
-- 日期：2026-09-16
-- ============================================================
-- 背景：
--   qr_code.department_id 于 2026-06-25 引入（部门匹配扩容：扩容时同部门优先取人）。
--   此前创建的活码该字段为 NULL，扩容时退化为全局取人（非同部门优先）。
--   本次按 active 服务老师主部门一次性回填。
--
-- 口径：
--   active 服务老师 = qr_agent.role='service' AND qr_agent.status='active'
--   主部门         = employee.department JSON 数组第一个元素（企微主部门）
--   只回填「恰好 1 个 active 服务老师」的活码；多 active / 0 active 的保持 NULL 不动。
--   「甘快学客服」(id=3534) 为 4 个 active 服务老师跨部门，人工忽略，不在本次回填范围。
--
-- 已确认：
--   1) 单活码测试通过：id=5 回填 79（= 张芳云主部门），符合预期。
--   2) 数据稳定：连续两次快照一致，null_dept=533、will_backfill=532。
--   3) 影响行数 532（533 null − 3534 排除）。

-- ---------- Step 1：备份（回滚依据） ----------
DROP TABLE IF EXISTS qr_code_dept_backfill_backup;
CREATE TABLE qr_code_dept_backfill_backup AS
SELECT q.id AS qr_code_id,
       q.department_id AS old_dept,
       d.dept_id AS new_dept
FROM qr_code q
JOIN (
  SELECT qa.qr_code_id,
         CAST(JSON_EXTRACT(MAX(e.department), '$[0]') AS UNSIGNED) AS dept_id
  FROM qr_agent qa
  JOIN employee e ON e.userid = qa.agent_userid
  WHERE qa.role = 'service'
    AND qa.status = 'active'
    AND e.department IS NOT NULL
    AND e.department <> ''
  GROUP BY qa.qr_code_id
  HAVING COUNT(*) = 1
) d ON d.qr_code_id = q.id
WHERE q.department_id IS NULL;

-- ---------- Step 2：全量回填 ----------
UPDATE qr_code q
JOIN (
  SELECT qa.qr_code_id,
         CAST(JSON_EXTRACT(MAX(e.department), '$[0]') AS UNSIGNED) AS dept_id
  FROM qr_agent qa
  JOIN employee e ON e.userid = qa.agent_userid
  WHERE qa.role = 'service'
    AND qa.status = 'active'
    AND e.department IS NOT NULL
    AND e.department <> ''
  GROUP BY qa.qr_code_id
  HAVING COUNT(*) = 1
) d ON d.qr_code_id = q.id
SET q.department_id = d.dept_id
WHERE q.department_id IS NULL;

-- ---------- Step 3：校验 ----------
-- 回填后仍为 NULL 的活码（预期只剩 3534 甘快学客服）
SELECT id, school_name, school_id, region_city, region_district
FROM qr_code
WHERE department_id IS NULL;

-- ---------- 回滚（仅当需要时执行） ----------
-- UPDATE qr_code q
-- JOIN qr_code_dept_backfill_backup b ON b.qr_code_id = q.id
-- SET q.department_id = b.old_dept;
