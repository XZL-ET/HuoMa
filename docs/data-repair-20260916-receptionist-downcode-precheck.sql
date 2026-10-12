-- ============================================================
-- 数据修复：接待员下码前置——待继承客户清单与 DB 修正
-- 日期：2026-09-16
-- ============================================================
-- 背景：
--   532 个旧码（qr_code_dept_backfill_backup）准备下掉多余接待员。
--   下码前必须先把挂在接待员名下的客户继承给服务老师，否则下码后
--   这些客户永久孤儿（继承定时任务与全量补转都过滤 status != removed）。
--
-- 本脚本只做「纯 DB 可修」的部分：
--   Step 0  备份（回滚依据）
--   Step 1  修正 confirmed_not_moved（已确认转移、但 current_agent 未更新）
--   Step 2  校验（按码列出剩余待处理客户）
--   Step 3  回滚（仅当需要时）
--
-- 不在本脚本范围（需应用侧调企微 API）：
--   * never_transferred / terminal_fail → 走「全量补转」重新入队 transfer_customer
--   * pending → 等应用侧轮询/24h 自动确认
--
-- 已确认口径（2026-09-16 快照）：
--   current_agent 仍挂接待员名下的客户 ~5353：
--     never_transferred 1603 / pending 2146(97% <1天) /
--     confirmed_not_moved 1024 / terminal_fail 583(382 timeout+117 retry_limit+84 rejected)
--   confirmed_not_moved 中 786 的 to_userid ≠ 当前服务老师（转给了前任服务老师），
--     故 Step 1 用「最新一条 confirmed 的 to_userid」回填，而非当前服务老师。

-- ---------- Step 0：备份 ----------
DROP TABLE IF EXISTS receptionist_downcode_backup;
CREATE TABLE receptionist_downcode_backup AS
SELECT c.id AS customer_id,
       c.source_qr_id,
       c.current_agent AS old_current_agent,
       c.added_agent,
       c.school_id,
       c.add_time,
       ra.agent_userid AS receptionist
FROM customer c
JOIN qr_agent ra ON ra.qr_code_id = c.source_qr_id
  AND ra.agent_userid = c.current_agent
  AND ra.status != 'removed'
  AND ra.role IN ('receptionist','dual')
WHERE c.status = 'active'
  AND c.source_qr_id IN (SELECT qr_code_id FROM qr_code_dept_backfill_backup);

-- ---------- Step 1：修正 confirmed_not_moved ----------
-- 已存在 confirmed 转移记录的客户，企微侧其实已在 to_userid 名下，
-- 只是 current_agent 没跟着更新（09-08 前历史 bug）。
-- 取该客户最新一条 confirmed 的 to_userid 回填，避免盲目指向当前服务老师。
UPDATE customer c
JOIN qr_agent ra ON ra.qr_code_id = c.source_qr_id
  AND ra.agent_userid = c.current_agent
  AND ra.status != 'removed'
  AND ra.role IN ('receptionist','dual')
JOIN (
  SELECT t.customer_id, t.to_userid
  FROM customer_transfer t
  JOIN (
    SELECT customer_id, MAX(id) AS max_id
    FROM customer_transfer
    WHERE status = 'confirmed'
    GROUP BY customer_id
  ) m ON m.max_id = t.id
) ct ON ct.customer_id = c.id
SET c.current_agent = ct.to_userid
WHERE c.status = 'active'
  AND c.source_qr_id IN (SELECT qr_code_id FROM qr_code_dept_backfill_backup);

-- ---------- Step 2：校验 ----------
-- 修正后仍挂在接待员名下的客户（预期只剩 never_transferred / pending / terminal_fail）
SELECT c.source_qr_id,
       q.school_name,
       COUNT(*) AS remain,
       SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM customer_transfer t WHERE t.customer_id = c.id) THEN 1 ELSE 0 END) AS never_transferred,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM customer_transfer t WHERE t.customer_id = c.id AND t.status = 'pending_confirm') THEN 1 ELSE 0 END) AS pending,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM customer_transfer t WHERE t.customer_id = c.id AND t.status IN ('rejected','timeout','api_failed','retry_limit')) THEN 1 ELSE 0 END) AS terminal_fail
FROM customer c
JOIN qr_code q ON q.id = c.source_qr_id
JOIN qr_agent ra ON ra.qr_code_id = c.source_qr_id
  AND ra.agent_userid = c.current_agent
  AND ra.status != 'removed'
  AND ra.role IN ('receptionist','dual')
WHERE c.status = 'active'
  AND c.source_qr_id IN (SELECT qr_code_id FROM qr_code_dept_backfill_backup)
GROUP BY c.source_qr_id, q.school_name
ORDER BY remain DESC;

-- ---------- Step 3：回滚（仅当需要时执行） ----------
-- UPDATE customer c
-- JOIN receptionist_downcode_backup b ON b.customer_id = c.id
-- SET c.current_agent = b.old_current_agent;
