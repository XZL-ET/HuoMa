-- V23: 修复错配/为空的 qr_code.department_id + 种入严格同部门配置
UPDATE qr_code SET department_id = 12 WHERE id IN (315,317,318,319) AND region_district = '泾川县' AND department_id = 100;
UPDATE qr_code SET department_id = 8  WHERE id = 435  AND region_district = '平川区' AND department_id = 78;
UPDATE qr_code SET department_id = 52 WHERE id IN (3754,3755,3756) AND region_district = '高台县' AND department_id IS NULL;
UPDATE qr_code SET department_id = 80 WHERE id = 3925 AND region_district = '七里河区' AND department_id IS NULL;

INSERT INTO system_config (config_key, config_name, config_value)
SELECT 'strict_dept_mode', '严格同部门取人', 'false'
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'strict_dept_mode');

INSERT INTO system_config (config_key, config_name, config_value)
SELECT 'strict_dept_exempt_userids', '严格同部门豁免服务老师',
       '["18809427636","18709336236","15213919369","15209340344","WeiSiQi"]'
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'strict_dept_exempt_userids');
