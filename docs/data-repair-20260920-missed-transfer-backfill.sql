-- ============================================================
-- 数据修复：历史漏转客户补偿 —— 定位需「全量补转」的活码清单
-- 日期：2026-09-20
-- ============================================================
-- 背景：
--   排查发现「接待员已接待、但从未成功转移到服务老师」的客户漏转问题，
--   根因有三：
--     1. 服务老师空档期（~41%）：活码在无服务老师时被扫码添加，之后补上服务老师也未补转；
--     2. 夜间批次增量扫描 bug（~43%）：20:45–23:59 添加的客户被 DIRTY_SCHOOLS 过滤漏掉；
--     3. 白天零散漏转（~16%）。
--   （第 1、2 项已在应用代码侧修复：夜间批次改为全量扫描、移除服务老师时告警。）
--
-- 补偿范围（已与业务确认，2026-09-20）：
--   最近 30 天（add_time >= 2026-08-21）、客户 active、added_agent 仍为该活码
--   active/full 的接待员(role=receptionist|dual)，且无 pending_confirm/confirmed
--   转移记录（即「从未成功转移」，含 timeout/rejected/api_failed/retry_limit 终端失败）。
--   实测约 5,372 人（其中 891 为从未转移，其余为转移过但失败——多为已修复的 40205 票据过期）。
--
-- 本脚本只做「定位」，不做 DML：真正的补偿通过应用侧接口
--   POST /qrcodes/{id}/transfer/backfill   （页面「🔄 全量补转」按钮）
-- 完成，该接口把接待员名下全部客户 XADD 进转移流，由 TransferService.initiate
-- 去重：已有 pending_confirm/confirmed 的跳过、terminal 在 7 天冷却期内跳过，幂等安全。

-- ---------- Step 1：按活码列出需补转的客户数（= 补偿范围） ----------
SELECT q.id                                        AS qr_code_id,
       q.school_id,
       q.school_name,
       COUNT(DISTINCT c.id)                        AS missed_count
FROM qr_code q
JOIN customer c ON c.school_id = q.school_id
JOIN qr_agent a ON a.qr_code_id = q.id
  AND a.agent_userid = c.added_agent
  AND a.role IN ('receptionist','dual')
  AND a.status <> 'removed'
WHERE q.status = 'active'
  AND c.status = 'active'
  AND c.add_time >= '2026-08-21 00:00:00'          -- 最近 30 天窗口下界（2026-09-20 诊断基准）
  AND c.add_time < CURDATE()
  AND NOT EXISTS (
      SELECT 1 FROM customer_transfer t
      WHERE t.customer_id = c.id
        AND t.status IN ('pending_confirm','confirmed')
  )
GROUP BY q.id, q.school_id, q.school_name
ORDER BY missed_count DESC;

-- 预期：missed_count 合计约 5,372。逐码核对无误后，对每个 qr_code_id
-- 执行一次「全量补转」。若单个活码 missed_count 超过 500，需多次触发
-- （接口单次上限 TRANSFER_TRIGGER_MAX_BATCH=500，未完成的会返回 truncated）。

-- ---------- Step 2（可选）：抽查某活码的漏转客户明细 ----------
-- 将下方 {QR_CODE_ID} 替换为 Step 1 中的 qr_code_id 后执行：
-- SELECT c.id, c.external_userid, c.name, c.add_time, c.added_agent
-- FROM customer c
-- JOIN qr_agent a ON a.qr_code_id = {QR_CODE_ID}
--   AND a.agent_userid = c.added_agent
--   AND a.role IN ('receptionist','dual')
--   AND a.status <> 'removed'
-- WHERE c.school_id = (SELECT school_id FROM qr_code WHERE id = {QR_CODE_ID})
--   AND c.status = 'active'
--   AND c.add_time >= '2026-08-21 00:00:00'
--   AND c.add_time < CURDATE()
--   AND NOT EXISTS (
--       SELECT 1 FROM customer_transfer t
--       WHERE t.customer_id = c.id
--         AND t.status IN ('pending_confirm','confirmed')
--   )
-- ORDER BY c.add_time;

-- ---------- Step 3：补转后校验（漏转数应归零） ----------
-- SELECT COUNT(DISTINCT c.id) AS remain_missed
-- FROM qr_code q
-- JOIN customer c ON c.school_id = q.school_id
-- JOIN qr_agent a ON a.qr_code_id = q.id
--   AND a.agent_userid = c.added_agent
--   AND a.role IN ('receptionist','dual')
--   AND a.status <> 'removed'
-- WHERE q.status = 'active'
--   AND c.status = 'active'
--   AND c.add_time >= '2026-08-21 00:00:00'
--   AND c.add_time < CURDATE()
--   AND NOT EXISTS (
--       SELECT 1 FROM customer_transfer t
--       WHERE t.customer_id = c.id
--         AND t.status IN ('pending_confirm','confirmed')
--   );
