-- ============================================
-- 集成测试数据清理脚本（FK 安全顺序）
-- ============================================
DELETE FROM customer_transfer;
DELETE FROM customer_tag;
DELETE FROM customer;
DELETE FROM qr_agent;
DELETE FROM qr_rotate_log;
DELETE FROM qr_access_log;
DELETE FROM qr_download_log;
DELETE FROM global_agent_pool;
DELETE FROM qr_code;
DELETE FROM agent_alert;
DELETE FROM agent;
DELETE FROM employee;
DELETE FROM tag;
DELETE FROM district_manager;
DELETE FROM school;
DELETE FROM daily_report;
DELETE FROM operation_log;
DELETE FROM users;
-- system_config 有初始种子数据（global_contact_name 等），不可删除
