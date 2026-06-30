-- V3: School category layer for welcome/form inheritance chain
-- Adds school_category table between QrCodeGroup and SystemConfig in the chain:
--   QrCode → QrCodeGroup → SchoolCategory → SystemConfig

-- 1. School category table
CREATE TABLE IF NOT EXISTS school_category (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                      VARCHAR(100) NOT NULL COMMENT '分类名称',
    sort_order                INT NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
    default_welcome_text      VARCHAR(500) COMMENT '分类默认欢迎语，null=继承系统默认',
    default_form_template_id  BIGINT COMMENT 'FK→form_template.id，分类默认表单模板',
    created_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学校分类表';

-- 2. Add category_id to school table
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'school' AND COLUMN_NAME = 'category_id') = 0,
    'ALTER TABLE school ADD COLUMN category_id BIGINT COMMENT ''FK→school_category.id，null=未分类''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Default category for backward compatibility
INSERT IGNORE INTO school_category (name) VALUES ('未分类');
