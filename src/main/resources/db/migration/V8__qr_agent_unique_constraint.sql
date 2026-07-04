-- V4: qr_agent 唯一约束 —— 同一活码下同一员工只能有一条记录
-- 若存在 service + receptionist 双记录，合并为 dual；其余重复保留最小 id

-- Step 1: 找出同一活码下同时有 service 和 receptionist 角色的员工对
CREATE TEMPORARY TABLE dup_service_receptionist AS
SELECT qa1.id AS service_id, qa2.id AS receptionist_id
FROM qr_agent qa1
JOIN qr_agent qa2 ON qa1.qr_code_id = qa2.qr_code_id
  AND qa1.agent_userid = qa2.agent_userid
  AND qa1.id != qa2.id
WHERE qa1.role = 'service' AND qa2.role = 'receptionist';

-- Step 2: 将 service 记录升级为 dual（该员工既能接待又能服务）
UPDATE qr_agent qa
JOIN dup_service_receptionist dp ON qa.id = dp.service_id
SET qa.role = 'dual';

-- Step 3: 删除已被合并的 receptionist 记录
DELETE qa FROM qr_agent qa
JOIN dup_service_receptionist dp ON qa.id = dp.receptionist_id;

-- Step 4: 清理其余重复（同角色或其它组合），保留 id 最小的那条
DELETE qa FROM qr_agent qa
JOIN qr_agent qa2 ON qa.qr_code_id = qa2.qr_code_id
  AND qa.agent_userid = qa2.agent_userid
  AND qa.id > qa2.id;

-- Step 5: 删除旧索引，创建唯一约束
DROP INDEX idx_qr_agent ON qr_agent;
ALTER TABLE qr_agent ADD UNIQUE INDEX uq_qr_agent_unique (qr_code_id, agent_userid);

DROP TEMPORARY TABLE IF EXISTS dup_service_receptionist;
