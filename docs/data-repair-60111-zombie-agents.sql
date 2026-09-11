-- ============================================================
-- 数据修复：60111 userid not found 僵尸清理 + 改名迁移
-- 生成时间: 2026-09-11
-- 背景：员工从企微通讯录离职/改名后，历史 30% 安全网跳过级联清理，
--       导致 agent 仍 normal / qr_agent 仍 active，这些失效 userid
--       仍被推送到企微 update_contact_way，报 60111。
--
-- 本次修复分两类（已对生产库摸底确认，新 userid 均已 active=1）：
--   A. 改名员工（3 名）—— userid 变更但人仍在职，须「迁移 userid」保留身份：
--      - 灵台县袁小花  LingTaiXianYuanXiaoHua → YuanXiaoHua  (14 所学校)
--      - 张瑞          13909378470            → ZhangRui     (3 所学校)
--      - 杨进花        18193736619            → YangJinHua   (2 所学校)
--      迁移其 active qr_agent 绑定到新 userid，旧 agent 封禁。
--      注：新 userid 已有少量独立绑定（YuanXiaoHua 1 所 / ZhangRui 3 所 /
--          YangJinHua 5 所），与旧绑定无 qr_code 重叠，迁移不会撞唯一约束。
--   B. 离职员工（2 名）—— 真实离职，下码 + 在职继承告警：
--      - 朱浩华  ZhuHaoHua  (22 所 service + 2 所 receptionist)
--      - sophie  (receptionist，1 所，无需在职继承，仅封禁 + 下码)
--   另：3 名纯 agent 僵尸（咚咚 DongDong / 李伟 18193168855 / 葛宏 13893709386）
--       无 active qr_agent 绑定，仅需封禁 agent（葛宏实为改名 13893709386→GeHong02，
--       但无绑定可迁，封禁即足够）。
--
-- 执行顺序（重要）：
--   本 SQL 须在部署新代码后、下一次员工同步（每 30 分钟）的僵尸对账触发之前执行。
--   否则新代码会把 3 名改名员工当作「离职」下码，丢失迁移机会（学校丢码）。
--   建议：先跑本 SQL → 再部署 → 对账看到僵尸已被本 SQL 清空，无需重复处理。
--
-- 执行方式：
--   备份表（DDL）会隐式提交，独立保留用于回滚；
--   真正事务从 START TRANSACTION 开始，覆盖「改名迁移 + 封禁 + 下码 + 告警」，
--   核对影响行数后 COMMIT，或 ROLLBACK 回滚。
-- ============================================================

-- 1. 备份受影响行（DDL，隐式提交，备份表独立保留用于回滚）
DROP TABLE IF EXISTS agent_backup_zombie_20260911;
CREATE TABLE agent_backup_zombie_20260911 AS
SELECT a.* FROM agent a
WHERE a.overall_status = 'normal'
  AND a.userid IN (SELECT e.userid FROM employee e WHERE e.active = 0);

DROP TABLE IF EXISTS qr_agent_backup_zombie_20260911;
CREATE TABLE qr_agent_backup_zombie_20260911 AS
SELECT qa.* FROM qr_agent qa
WHERE qa.status = 'active'
  AND qa.agent_userid IN (SELECT e.userid FROM employee e WHERE e.active = 0);

-- 2. 开启修复事务
START TRANSACTION;

-- 3. 改名员工：迁移 active qr_agent 绑定到新 userid（保留服务老师身份，避免丢码）
--    幂等：仅迁移 status='active'，迁移后旧 userid 不再有 active 绑定
UPDATE qr_agent qa
SET qa.agent_userid = 'YuanXiaoHua', qa.updated_at = CURRENT_TIMESTAMP
WHERE qa.agent_userid = 'LingTaiXianYuanXiaoHua' AND qa.status = 'active';

UPDATE qr_agent qa
SET qa.agent_userid = 'ZhangRui', qa.updated_at = CURRENT_TIMESTAMP
WHERE qa.agent_userid = '13909378470' AND qa.status = 'active';

UPDATE qr_agent qa
SET qa.agent_userid = 'YangJinHua', qa.updated_at = CURRENT_TIMESTAMP
WHERE qa.agent_userid = '18193736619' AND qa.status = 'active';

-- 4. 封禁改名员工的旧 agent（旧 userid 已失效，防 60111；人仍在职，角色由重算恢复）
--    幂等：仅命中 overall_status='normal'
UPDATE agent a
SET a.overall_status = 'blocked',
    a.status_reason = CONCAT('{"reason":"企微改名(userid迁移)","operator":"system","time":"', CURRENT_TIMESTAMP, '"}'),
    a.updated_at = CURRENT_TIMESTAMP
WHERE a.overall_status = 'normal'
  AND a.userid IN ('LingTaiXianYuanXiaoHua', '13909378470', '18193736619');

-- 4.5 修正改名员工的角色漂移：迁移后新 userid 同时拥有 service + receptionist
--     活跃绑定，agent.role 应为 dual。迁移前新 userid 可能仍是单角色（receptionist/service），
--     若不修正，receptionist 会被当作自由人借去别的学校接待，service 则失去接待能力。
--     （recomputeAgentRoles 会在下次 syncToGlobalPool 自动重算，此处立即修正避免窗口期。）
UPDATE agent
SET role = 'dual', updated_at = CURRENT_TIMESTAMP
WHERE userid IN ('YuanXiaoHua', 'ZhangRui', 'YangJinHua')
  AND role != 'dual';

-- 5. 封禁离职/纯 agent 僵尸员工 agent（真实离职 + 无绑定僵尸）
UPDATE agent a
SET a.overall_status = 'blocked',
    a.status_reason = CONCAT('{"reason":"企微通讯录已移除(同步级联)","operator":"system","time":"', CURRENT_TIMESTAMP, '"}'),
    a.updated_at = CURRENT_TIMESTAMP
WHERE a.overall_status = 'normal'
  AND a.userid IN ('ZhuHaoHua', 'sophie', 'DongDong', '18193168855', '13893709386');

-- 6. 下码离职员工 active qr_agent（朱浩华 24 条 + sophie 1 条）
--    幂等：仅命中 status='active'
UPDATE qr_agent qa
SET qa.status = 'removed', qa.updated_at = CURRENT_TIMESTAMP
WHERE qa.status = 'active'
  AND qa.agent_userid IN ('ZhuHaoHua', 'sophie');

-- 7. 服务老师离职告警（仅朱浩华：22 所 service 学校需人工在职继承）
--    幂等：NOT EXISTS 避免重复插入
INSERT INTO agent_alert (agent_userid, alert_type, severity, detail, auto_action, status, created_at)
SELECT 'ZhuHaoHua', 'employee_departed_service', 'high',
       JSON_QUOTE('服务老师 朱浩华 已从企微离职，已自动下码，请手动处理在职继承。关联学校: 庄浪县永宁镇中心小学, 庄浪县良邑镇良邑小学, 庄浪县良邑镇李咀小学, 庄浪县良邑镇杨王小学, 庄浪县良邑镇何川小学, 庄浪县韩店镇韩店小学, 庄浪县韩店镇石桥小学, 庄浪县韩店镇菜湾教学点, 庄浪县韩店镇王崖小学, 庄浪县韩店镇刘河教学点, 庄浪县郑河乡郑河小学, 庄浪县郑河乡卢洼小学, 庄浪县郑河乡庙川小学, 庄浪县郑河乡上寨小学, 庄浪县郑河乡阴洼小学, 庄浪县南坪中学, 庄浪县永宁中学, 庄浪县郑河中学, 庄浪县韩店中学, 永宁学区, 韩店学区, 郑河学区'),
       'none', 'open', NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM agent_alert x
  WHERE x.agent_userid = 'ZhuHaoHua' AND x.alert_type = 'employee_departed_service' AND x.status = 'open'
);

-- 8. 校验（预期：封禁 8、迁移 qr_agent 19、下码 qr_agent 25、告警 1）
SELECT '===== 封禁的 agent 数（预期 8）=====' AS info;
SELECT COUNT(*) AS blocked_agent_cnt FROM agent_backup_zombie_20260911;

SELECT '===== 迁移的 qr_agent 数（预期 19 = 14+3+2）=====' AS info;
SELECT COUNT(*) AS migrated_qr_cnt FROM qr_agent_backup_zombie_20260911
WHERE agent_userid IN ('LingTaiXianYuanXiaoHua', '13909378470', '18193736619');

SELECT '===== 下码的 qr_agent 数（预期 25 = 24+1）=====' AS info;
SELECT COUNT(*) AS removed_qr_cnt FROM qr_agent_backup_zombie_20260911
WHERE agent_userid IN ('ZhuHaoHua', 'sophie');

-- 9. 只读核对：改名后新 userid 的活跃绑定学校数（人工确认身份已保留）
SELECT '===== 改名后新 userid 活跃绑定（应含迁移 + 原有）=====' AS info;
SELECT qa.agent_userid, COUNT(*) AS active_rows, COUNT(DISTINCT qa.qr_code_id) AS schools
FROM qr_agent qa
WHERE qa.agent_userid IN ('YuanXiaoHua', 'ZhangRui', 'YangJinHua') AND qa.status = 'active'
GROUP BY qa.agent_userid;

-- 10. 只读核对：需在职继承的朱浩华学校清单（人工处理用）
SELECT '===== 朱浩华在职继承学校清单 ====' AS info;
SELECT qa.agent_userid, a.name, qc.school_name, qa.role, qc.id AS qr_code_id
FROM qr_agent_backup_zombie_20260911 qa
LEFT JOIN agent a ON a.userid = qa.agent_userid
LEFT JOIN qr_code qc ON qc.id = qa.qr_code_id
WHERE qa.agent_userid = 'ZhuHaoHua' AND qa.role = 'service'
ORDER BY qc.id;

-- 确认无误后执行：COMMIT;
-- 需要回滚时执行：ROLLBACK;
