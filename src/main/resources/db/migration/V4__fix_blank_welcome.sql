-- V4: 修复空字符串欢迎语阻断继承（与 3 个 Service 的 isBlank→null 逻辑配套）
-- 将所有层级的空白欢迎语字段统一置为 NULL，恢复继承链正常工作

UPDATE school_category SET default_welcome_text = NULL WHERE default_welcome_text = '';
UPDATE qr_code_group   SET default_welcome_text = NULL WHERE default_welcome_text = '';
UPDATE qr_code         SET welcome_text          = NULL WHERE welcome_text = '';
