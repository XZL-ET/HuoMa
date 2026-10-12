-- ============================================================
-- 数据修复：service_daily_max 字段不一致（已执行，结果见文末）
-- 生成/执行时间: 2026-09-11
-- 背景：2026-09-11 下午一次批量「更换服务老师」操作留下两类脏数据：
--   #1 老服务老师降为接待员后，service_daily_max=300 未清除
--   #2 新服务老师升为 service 后，service_daily_max 未填（NULL）
-- 根因：updateAgent 角色变更路径不维护 service_daily_max 字段。
-- ============================================================

DROP TABLE IF EXISTS qr_agent_backup_sdmax_20260911;
CREATE TABLE qr_agent_backup_sdmax_20260911 AS
SELECT * FROM qr_agent WHERE id IN (4904,4908,4910,4911,10513,10514,10515,10516,10517,10518,10519);

START TRANSACTION;

-- #1 接待员误留 service_daily_max=300 → NULL
UPDATE qr_agent SET service_daily_max=NULL, updated_at=CURRENT_TIMESTAMP
WHERE id IN (4904,4908,4910) AND role='receptionist' AND service_daily_max IS NOT NULL;

-- #2 service 缺失 service_daily_max → 300
UPDATE qr_agent SET service_daily_max=300, updated_at=CURRENT_TIMESTAMP
WHERE id IN (4911,10513,10514,10515,10516,10517,10518,10519) AND role='service' AND service_daily_max IS NULL;

COMMIT;

-- ============================================================
-- 执行结果（2026-09-11 校验确认）
--   #1 active 接待员带 service_daily_max：0 条 ✓
--   #2 service/dual 缺 service_daily_max：0 条 ✓
-- 备注：另有 4 条 removed 接待员（4899-4902）也带 service_daily_max=300，
--       属历史已移除记录，不影响轮换/同步，未处理。
-- ============================================================
