-- ============================================================================
-- 2026-10-29-alert-dedup-verify.sql   告警降噪上线核对
--
-- 库：bookstore_qrcode（生产 RDS）
-- 说明：
--   * 本文件只有「ALTER / UPDATE」两段需要用户手工执行；其余均为只读查询。
--     注意：涉及 last_seen_at / occurrence_count 的查询只能在 ALTER 之后跑，
--     上线前请只执行「上线前：基线」与「字段是否已加过」两节。
--   * ddl-auto: none，应用不会自动建列/索引；新列与新索引必须由用户先手工加，
--     旧版本代码忽略新列，ALTER 可以先于部署执行。
--   * 首次维护任务（应用启动即跑一次 + 每小时 :20）完成后，
--     status=open 应从约 2,140 降到 < 50。
--   * 若 last_seen_at 已存在，ADD COLUMN 会报 Duplicate column name。
--     执行 ALTER 前先跑下面「字段是否已加过」的 SHOW COLUMNS 判断。
-- ============================================================================


-- ============================ 上线前：基线 ============================
-- 记录改动前的 open 分布，用于和上线后对比。
SELECT alert_type, COUNT(*) AS open_cnt
FROM agent_alert
WHERE status = 'open'
GROUP BY alert_type
ORDER BY open_cnt DESC;

-- 当前 open 总数（基线应约 2,140）
SELECT COUNT(*) AS total_open FROM agent_alert WHERE status = 'open';


-- ==================== 字段是否已加过（ALTER 前先判断） ====================
-- 返回 0 行 → 需要执行下面的 ALTER；
-- 返回 1 行 → 已加过，跳过 ADD COLUMN / ADD INDEX（重复会报错）。
SHOW COLUMNS FROM agent_alert LIKE 'last_seen_at';
SHOW COLUMNS FROM agent_alert LIKE 'occurrence_count';
SHOW INDEX FROM agent_alert WHERE Key_name = 'idx_type_status_key';


-- ======================= 用户执行：ALTER + 回填 =======================
-- 下面整段先注释，确认上面 SHOW 结果后再取消注释执行。

-- ALTER TABLE agent_alert
--   ADD COLUMN last_seen_at DATETIME NULL AFTER resolved_at,
--   ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER last_seen_at,
--   ADD INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id);

-- 历史行回填最近发生时间（新列新增后全为 NULL）
-- UPDATE agent_alert SET last_seen_at = created_at WHERE last_seen_at IS NULL;

-- ALTER 后确认：两列存在、索引存在
-- SHOW COLUMNS FROM agent_alert LIKE 'last_seen_at';
-- SHOW COLUMNS FROM agent_alert LIKE 'occurrence_count';
-- SHOW INDEX FROM agent_alert WHERE Key_name = 'idx_type_status_key';


-- ====================== 首轮维护后：降噪效果 ======================
-- 预期：open 总数 ≤ 50；全局类（inheritance_*、*_stream_backlog、
-- transfer_dlq_backlog、daily_reset_failure 等）每种 ≤ 1。
SELECT alert_type, COUNT(*) AS open_cnt
FROM agent_alert
WHERE status = 'open'
GROUP BY alert_type
ORDER BY open_cnt DESC;

SELECT COUNT(*) AS total_open_after
FROM agent_alert
WHERE status = 'open';

-- 同一合并键不应再有多条 open（正常情况下应返回 0 行）。
-- 合并键必须按代码的归一化规则构造（AlertTypeMeta.normalizeAgent / normalizeQr）：
--   * 全局类 (type, NULL, NULL)：继承两类、各 *_stream_backlog、transfer_dlq_backlog、
--     dlq_replay_exhausted、daily_reset_failure、deletion_report_fail、daily_anomaly_heal、wechat_48002
--   * traffic_spike 丢弃 agent，只用 (type, NULL, qr_code_id)
--   * 其余条件类保留 (type, agent_userid, qr_code_id)
-- 事件类（每次发生独立记录）不参与查重；未知类型按事件类处理，若出现可能被列出，属预期。
SELECT merge_key, COUNT(*) AS dup_cnt
FROM (
    SELECT CONCAT(
        alert_type, '|',
        COALESCE(CASE
            WHEN alert_type = 'traffic_spike' THEN NULL
            WHEN alert_type IN (
                'inheritance_no_service','inheritance_ambiguous_service',
                'callback_stream_backlog','tag_stream_backlog','datafill_stream_backlog',
                'outbound_stream_backlog','transfer_stream_backlog','transfer_dlq_backlog',
                'dlq_replay_exhausted','daily_reset_failure','deletion_report_fail',
                'daily_anomaly_heal','wechat_48002'
            ) THEN NULL
            ELSE agent_userid
        END, ''), '|',
        COALESCE(CASE
            WHEN alert_type IN (
                'inheritance_no_service','inheritance_ambiguous_service',
                'callback_stream_backlog','tag_stream_backlog','datafill_stream_backlog',
                'outbound_stream_backlog','transfer_stream_backlog','transfer_dlq_backlog',
                'dlq_replay_exhausted','daily_reset_failure','deletion_report_fail',
                'daily_anomaly_heal','wechat_48002'
            ) THEN NULL
            ELSE qr_code_id
        END, '')
    ) AS merge_key
    FROM agent_alert
    WHERE status = 'open'
      AND alert_type NOT IN ('add_fail','melt','transfer_fail','transfer_retry_exhausted_svc')
) t
GROUP BY merge_key
HAVING COUNT(*) > 1
ORDER BY dup_cnt DESC;

-- 历史重复已被合并/归档（应为 auto_resolved，且对应 open 只剩 1 条）
SELECT status, COUNT(*)
FROM agent_alert
WHERE alert_type = 'qr_agent_anomaly'
GROUP BY status;


-- ============== 不应被自动关闭的真实待办仍在（人工确认） ==============
-- 这三类指向仍需人工处理的员工/活码，不得被维护任务归档。
SELECT id, alert_type, agent_userid, qr_code_id, severity, occurrence_count,
       created_at, last_seen_at
FROM agent_alert
WHERE status = 'open'
  AND alert_type IN ('service_teacher_anomaly','anomaly_replace_shortfall','anomaly_replace_blocked')
ORDER BY created_at;

-- 继承类也必须仍在（夜间全量扫描确认无待办时才由批次关闭）
SELECT id, alert_type, detail, created_at, last_seen_at
FROM agent_alert
WHERE status = 'open'
  AND alert_type IN ('inheritance_no_service','inheritance_ambiguous_service')
ORDER BY created_at;


-- ===================== 48002：每天一条、次数递增 =====================
-- 预期：最新一条 wechat_48002 为 open，detail 里的“今日 N 次”随命中增长，
-- occurrence_count 递增；次日旧行归档、当天最多新建一条。
SELECT id, status, detail, occurrence_count, created_at, last_seen_at
FROM agent_alert
WHERE alert_type = 'wechat_48002'
ORDER BY id DESC
LIMIT 3;

-- 历史 48002 污染（旧文案“无客户联系权限”）应已全部 auto_resolved
SELECT status, COUNT(*) AS cnt
FROM agent_alert
WHERE alert_type = 'wechat_unavailable'
  AND detail LIKE '%无客户联系权限%'
GROUP BY status;


-- ======================= 合并结果保真度抽查 =======================
-- 存活行应是组内最早创建的那条：created_at 不被重置、次数为组内求和、
-- 最近发生为组内最大。抽查 qr_agent_anomaly 的存活行是否仍保持较早的创建时间。
SELECT id, alert_type, agent_userid, qr_code_id, occurrence_count,
       created_at, last_seen_at
FROM agent_alert
WHERE status = 'open'
  AND alert_type = 'qr_agent_anomaly'
ORDER BY created_at
LIMIT 10;
