CREATE TABLE IF NOT EXISTS form_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    fields JSON NOT NULL,
    tag_mapping JSON NOT NULL,
    remark_template VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS form_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_template_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    qr_code_id BIGINT,
    field_data JSON NOT NULL,
    tags_applied VARCHAR(500),
    remark_updated VARCHAR(500),
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fs_customer (customer_id),
    INDEX idx_fs_template (form_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qr_code_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    region_city VARCHAR(50),
    region_district VARCHAR(50) NOT NULL,
    group_type VARCHAR(20) NOT NULL DEFAULT 'alliance',
    default_welcome_text VARCHAR(500),
    default_form_template_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE qr_code
    ADD COLUMN form_template_id BIGINT NULL,
    ADD COLUMN welcome_text VARCHAR(500) NULL,
    ADD COLUMN group_id BIGINT NULL;

INSERT IGNORE INTO system_config (config_key, config_value) VALUES
('default_welcome_text', '{{school_name}}家长您好～欢迎加入XX书店家校服务！');
