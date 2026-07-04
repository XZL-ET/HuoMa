-- V5: qr_code_group.qr_code_id 唯一约束
-- 确保一个活码只能属于一个联盟，防止并发创建导致的数据不一致
-- 修复前：若存在脏数据（同一活码关联多个联盟），需先手动清理
--   SELECT qr_code_id, COUNT(*) AS cnt FROM qr_code_group
--   WHERE qr_code_id IS NOT NULL GROUP BY qr_code_id HAVING cnt > 1;

-- MySQL UNIQUE 索引允许多个 NULL，无需 WHERE 子句
CREATE UNIQUE INDEX IF NOT EXISTS uk_qr_code_id ON qr_code_group (qr_code_id);
