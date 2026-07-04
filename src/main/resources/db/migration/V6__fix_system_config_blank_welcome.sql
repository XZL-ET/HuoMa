-- V6: V4 遗漏 — system_config 表的空白欢迎语也需置 NULL，防止 Optional.orElse 陷阱
-- 在职继承（无 state）直接走 L4 系统默认，空字符串会触发企微 40063 "some parameters are empty"
UPDATE system_config SET config_value = NULL WHERE config_key = 'default_welcome_text' AND config_value = '';
