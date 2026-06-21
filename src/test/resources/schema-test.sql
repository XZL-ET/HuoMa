-- ============================================
-- H2 测试数据库建表脚本
-- 与生产 MySQL schema.sql 表结构一致，语法适配 H2
-- ============================================

-- qr_code：活码
CREATE TABLE IF NOT EXISTS qr_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_name VARCHAR(100) NOT NULL,
    school_id VARCHAR(50) NOT NULL UNIQUE,
    region_city VARCHAR(50) NOT NULL,
    region_district VARCHAR(50) NOT NULL,
    qr_config_id VARCHAR(100),
    qr_url VARCHAR(500),
    qr_image_path VARCHAR(500),
    style_config JSON,
    welcome_config JSON,
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','paused','full','no_agent')),
    rotate_mode VARCHAR(10) NOT NULL DEFAULT 'auto'
        CHECK (rotate_mode IN ('auto','manual')),
    warn_ratio INT DEFAULT 80,
    urgent_ratio INT DEFAULT 95,
    create_mode VARCHAR(20) NOT NULL DEFAULT 'manual'
        CHECK (create_mode IN ('manual','batch_import')),
    remark VARCHAR(500),
    transfer_target_userid VARCHAR(100),
    initial_agent_count INT DEFAULT 1,
    custom_tags VARCHAR(500),
    student_count INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qr_school_id ON qr_code (school_id);
CREATE INDEX IF NOT EXISTS idx_qr_region ON qr_code (region_city, region_district);
CREATE INDEX IF NOT EXISTS idx_qr_status ON qr_code (status);
CREATE INDEX IF NOT EXISTS idx_qr_code_school_name ON qr_code (school_name);

-- agent：员工
CREATE TABLE IF NOT EXISTS agent (
    userid VARCHAR(100) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    mobile VARCHAR(20),
    department VARCHAR(200),
    role VARCHAR(20) NOT NULL DEFAULT 'receptionist'
        CHECK (role IN ('receptionist','service','dual')),
    daily_total_cap INT NOT NULL DEFAULT 500,
    daily_total_used INT NOT NULL DEFAULT 0,
    overall_status VARCHAR(20) NOT NULL DEFAULT 'normal'
        CHECK (overall_status IN ('normal','warning','blocked','melted')),
    status_reason JSON,
    total_added INT NOT NULL DEFAULT 0,
    total_deleted INT NOT NULL DEFAULT 0,
    melted_count_24h INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_status ON agent (overall_status);
CREATE INDEX IF NOT EXISTS idx_agent_role ON agent (role);

-- employee：企微员工通讯录
CREATE TABLE IF NOT EXISTS employee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    userid VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    wechat_status INT,
    last_sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_employee_active ON employee (active);
CREATE INDEX IF NOT EXISTS idx_employee_name ON employee (name);
CREATE INDEX IF NOT EXISTS idx_employee_active_name ON employee (active, name);

-- qr_agent：活码-员工关联
CREATE TABLE IF NOT EXISTS qr_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL,
    agent_userid VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'receptionist'
        CHECK (role IN ('receptionist','service','dual')),
    daily_max INT NOT NULL DEFAULT 100,
    daily_current INT NOT NULL DEFAULT 0,
    service_daily_max INT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','full','removed','blocked')),
    replaced_by VARCHAR(100),
    last_reset_at TIMESTAMP,
    bind_target JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (qr_code_id) REFERENCES qr_code(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid)
);
CREATE INDEX IF NOT EXISTS idx_qr_agent_pair ON qr_agent (qr_code_id, agent_userid);
CREATE INDEX IF NOT EXISTS idx_qr_agent_status ON qr_agent (status);
CREATE INDEX IF NOT EXISTS idx_qr_agent_userid ON qr_agent (agent_userid);
CREATE INDEX IF NOT EXISTS idx_qr_agent_sort ON qr_agent (qr_code_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_qr_agent_qr_status ON qr_agent (qr_code_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_qr_status ON qr_agent (agent_userid, status);

-- global_agent_pool：全局员工池
CREATE TABLE IF NOT EXISTS global_agent_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_userid VARCHAR(100) NOT NULL UNIQUE,
    daily_max INT NOT NULL DEFAULT 100,
    daily_current INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'standby'
        CHECK (status IN ('standby','full','blocked')),
    last_reset_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid)
);
CREATE INDEX IF NOT EXISTS idx_pool_status ON global_agent_pool (status);
CREATE INDEX IF NOT EXISTS idx_pool_sort ON global_agent_pool (sort_order);
CREATE INDEX IF NOT EXISTS idx_pool_status_sort ON global_agent_pool (status, sort_order);

-- customer：客户
CREATE TABLE IF NOT EXISTS customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_userid VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200),
    avatar VARCHAR(500),
    type INT NOT NULL DEFAULT 1,
    unionid VARCHAR(100),
    added_agent VARCHAR(100),
    current_agent VARCHAR(100),
    source_qr_id BIGINT,
    school_id VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','deleted')),
    add_time TIMESTAMP,
    data_needs_repair BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_customer_euid ON customer (external_userid);
CREATE INDEX IF NOT EXISTS idx_customer_source_qr ON customer (source_qr_id);
CREATE INDEX IF NOT EXISTS idx_customer_agent ON customer (current_agent);
CREATE INDEX IF NOT EXISTS idx_customer_school ON customer (school_id);
CREATE INDEX IF NOT EXISTS idx_customer_add_time ON customer (add_time);
CREATE INDEX IF NOT EXISTS idx_customer_add_time_agent ON customer (add_time, added_agent);
CREATE INDEX IF NOT EXISTS idx_customer_add_time_qr ON customer (add_time, source_qr_id);
CREATE INDEX IF NOT EXISTS idx_customer_add_time_status ON customer (add_time, status);

-- tag：标签
CREATE TABLE IF NOT EXISTS tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'manual'
        CHECK (type IN ('system','form','manual')),
    parent_id BIGINT,
    wecom_tag_id VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (parent_id) REFERENCES tag(id)
);
CREATE INDEX IF NOT EXISTS idx_tag_type ON tag (type);

-- customer_tag：客户标签关联
CREATE TABLE IF NOT EXISTS customer_tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'system'
        CHECK (source IN ('system','form','manual')),
    tagged_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id),
    CONSTRAINT uk_customer_tag UNIQUE (customer_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_customer_tag_tag_id ON customer_tag (tag_id);

-- agent_alert：异常记录
CREATE TABLE IF NOT EXISTS agent_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_userid VARCHAR(100) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'medium'
        CHECK (severity IN ('low','medium','high')),
    detail JSON,
    auto_action VARCHAR(20) DEFAULT 'none'
        CHECK (auto_action IN ('none','paused','removed','melted')),
    status VARCHAR(20) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open','resolved','auto_resolved')),
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP,
    qr_code_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid)
);
CREATE INDEX IF NOT EXISTS idx_alert_agent_status ON agent_alert (agent_userid, status);
CREATE INDEX IF NOT EXISTS idx_alert_created ON agent_alert (created_at);
CREATE INDEX IF NOT EXISTS idx_alert_status_created ON agent_alert (status, created_at);
CREATE INDEX IF NOT EXISTS idx_alert_agent_type_status_created ON agent_alert (agent_userid, alert_type, status, created_at);
CREATE INDEX IF NOT EXISTS idx_alert_severity_created ON agent_alert (severity, created_at);
CREATE INDEX IF NOT EXISTS idx_alert_qr_code ON agent_alert (qr_code_id);

-- customer_transfer：继承记录
CREATE TABLE IF NOT EXISTS customer_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    from_userid VARCHAR(100) NOT NULL,
    to_userid VARCHAR(100) NOT NULL,
    qr_code_id BIGINT,
    transfer_time TIMESTAMP,
    confirm_time TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'pending_confirm'
        CHECK (status IN ('pending_confirm','confirmed','rejected','timeout','api_failed','retry_limit')),
    retry_count INT NOT NULL DEFAULT 0,
    fail_reason VARCHAR(500),
    form_filled_at_transfer BOOLEAN,
    note_sent BOOLEAN NOT NULL DEFAULT FALSE,
    greeting_sent BOOLEAN NOT NULL DEFAULT FALSE,
    greeting_type VARCHAR(20)
        CHECK (greeting_type IN ('filled','unfilled')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_transfer_customer ON customer_transfer (customer_id);
CREATE INDEX IF NOT EXISTS idx_transfer_status ON customer_transfer (status);
CREATE INDEX IF NOT EXISTS idx_transfer_time ON customer_transfer (transfer_time);
CREATE INDEX IF NOT EXISTS idx_transfer_status_retry ON customer_transfer (status, retry_count);

-- daily_report：日报表
CREATE TABLE IF NOT EXISTS daily_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    total_scan INT NOT NULL DEFAULT 0,
    total_add INT NOT NULL DEFAULT 0,
    total_add_fail INT NOT NULL DEFAULT 0,
    total_transfer INT NOT NULL DEFAULT 0,
    total_transfer_ok INT NOT NULL DEFAULT 0,
    total_rotate INT NOT NULL DEFAULT 0,
    total_alert INT NOT NULL DEFAULT 0,
    active_qr INT NOT NULL DEFAULT 0,
    full_qr INT NOT NULL DEFAULT 0,
    blocked_agent INT NOT NULL DEFAULT 0,
    melted_agent INT NOT NULL DEFAULT 0,
    detail_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- operation_log：操作日志
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id VARCHAR(100),
    detail JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oplog_created ON operation_log (created_at);
CREATE INDEX IF NOT EXISTS idx_oplog_target ON operation_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_operation_log_action_created ON operation_log (action, created_at);

-- users：系统用户
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'operator'
        CHECK (role IN ('admin','operator')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_enabled ON users (enabled);

-- qr_download_log：活码下载日志
CREATE TABLE IF NOT EXISTS qr_download_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL,
    agent_userid VARCHAR(100) NOT NULL,
    downloaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(50),
    FOREIGN KEY (qr_code_id) REFERENCES qr_code(id)
);
CREATE INDEX IF NOT EXISTS idx_dl_qrcode ON qr_download_log (qr_code_id);
CREATE INDEX IF NOT EXISTS idx_dl_userid ON qr_download_log (agent_userid);
CREATE INDEX IF NOT EXISTS idx_log_userid_downloaded ON qr_download_log (agent_userid, downloaded_at);

-- district_manager：区县负责人配置
CREATE TABLE IF NOT EXISTS district_manager (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    region_city VARCHAR(50) NOT NULL,
    region_district VARCHAR(50) NOT NULL,
    manager_userid VARCHAR(100) NOT NULL,
    manager_name VARCHAR(100) NOT NULL,
    qr_config_id VARCHAR(64),
    qr_url VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_district UNIQUE (region_city, region_district)
);
CREATE INDEX IF NOT EXISTS idx_dm_city ON district_manager (region_city);

-- qr_rotate_log：活码轮换/扩容日志
CREATE TABLE IF NOT EXISTS qr_rotate_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL,
    from_userid VARCHAR(100),
    to_userid VARCHAR(100) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (qr_code_id) REFERENCES qr_code(id)
);
CREATE INDEX IF NOT EXISTS idx_rotate_qrcode ON qr_rotate_log (qr_code_id);
CREATE INDEX IF NOT EXISTS idx_rotate_to_userid ON qr_rotate_log (to_userid);
CREATE INDEX IF NOT EXISTS idx_rotate_qrcode_created ON qr_rotate_log (qr_code_id, created_at);

-- school：学校主数据表
CREATE TABLE IF NOT EXISTS school (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id VARCHAR(64) NOT NULL UNIQUE,
    school_name VARCHAR(128) NOT NULL,
    region_city VARCHAR(64) NOT NULL,
    region_district VARCHAR(64) NOT NULL,
    has_qrcode BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_school_city_district ON school (region_city, region_district);
CREATE INDEX IF NOT EXISTS idx_school_school_id ON school (school_id);
CREATE INDEX IF NOT EXISTS idx_school_deleted ON school (deleted);
CREATE INDEX IF NOT EXISTS idx_school_name ON school (school_name);

-- system_config：系统配置表
CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    updated_at TIMESTAMP DEFAULT NULL
);

-- qr_access_log：活码访问日志表
CREATE TABLE IF NOT EXISTS qr_access_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT,
    action VARCHAR(10) NOT NULL DEFAULT 'view'
        CHECK (action IN ('view','download')),
    channel VARCHAR(10) NOT NULL DEFAULT 'school'
        CHECK (channel IN ('employee','school')),
    user_identity VARCHAR(128),
    accessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512)
);
CREATE INDEX IF NOT EXISTS idx_qal_qr_code ON qr_access_log (qr_code_id);
CREATE INDEX IF NOT EXISTS idx_qal_channel ON qr_access_log (channel);
CREATE INDEX IF NOT EXISTS idx_qal_accessed ON qr_access_log (accessed_at);
CREATE INDEX IF NOT EXISTS idx_qal_action ON qr_access_log (action);

-- 初始数据（测试用）
MERGE INTO system_config (config_key, config_value) KEY(config_key)
VALUES ('global_contact_name', '火马客服'),
       ('global_contact_qr_config_id', ''),
       ('global_contact_qr_url', '');
