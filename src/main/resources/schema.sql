-- 数据库初始化脚本
-- ============================================
-- XX书店 · 企业微信活码管理平台 数据库建表
-- ============================================

-- qr_code：活码
-- 活码表
CREATE TABLE IF NOT EXISTS qr_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_name VARCHAR(100) NOT NULL COMMENT '学校名称',
    school_id VARCHAR(50) NOT NULL UNIQUE COMMENT '学校唯一标识（写入企微state）',
    region_city VARCHAR(50) NOT NULL COMMENT '市',
    region_district VARCHAR(50) NOT NULL COMMENT '区',
    qr_config_id VARCHAR(100) COMMENT '企微联系我配置ID',
    qr_url VARCHAR(500) COMMENT '二维码链接',
    qr_image_path VARCHAR(500) COMMENT '二维码图片路径',
    style_config JSON COMMENT '样式配置 {logo,theme,text,font_size}',
    welcome_config JSON COMMENT '欢迎语配置',
    status ENUM('active','paused','full','no_agent') NOT NULL DEFAULT 'active',        -- active 正常 / paused 暂停 / full 已满 / no_agent 无可用员工
    rotate_mode ENUM('auto','manual') NOT NULL DEFAULT 'auto',                           -- auto 自动轮转 / manual 手动指定
    warn_ratio INT DEFAULT 80 COMMENT '预警阈值%',
    urgent_ratio INT DEFAULT 95 COMMENT '紧急阈值%',
    create_mode ENUM('manual','batch_import') NOT NULL DEFAULT 'manual',
    remark VARCHAR(500) COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_school_id (school_id),
    INDEX idx_region (region_city, region_district),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码表';

-- qr_code 新增字段（全局员工池重构）
-- 使用动态 SQL 检查 INFORMATION_SCHEMA 兼容旧版 MySQL（<8.0.29 不支持 ADD COLUMN IF NOT EXISTS）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'transfer_target_userid') = 0,
    'ALTER TABLE qr_code ADD COLUMN transfer_target_userid VARCHAR(100) COMMENT ''在职继承目标员工''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'initial_agent_count') = 0,
    'ALTER TABLE qr_code ADD COLUMN initial_agent_count INT DEFAULT 1 COMMENT ''活码创建时初始上码人数''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'custom_tags') = 0,
    'ALTER TABLE qr_code ADD COLUMN custom_tags VARCHAR(500) COMMENT ''客户扫码后自动打标的自定义标签''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'student_count') = 0,
    'ALTER TABLE qr_code ADD COLUMN student_count INT COMMENT ''学校学生人数，自动计算接待员数量''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent：员工
-- 员工表
CREATE TABLE IF NOT EXISTS agent (
    userid VARCHAR(100) PRIMARY KEY COMMENT '企微UserID',
    name VARCHAR(100) NOT NULL COMMENT '姓名',
    mobile VARCHAR(20) COMMENT '手机',
    department VARCHAR(200) COMMENT '部门',
    role ENUM('receptionist','service','dual') NOT NULL DEFAULT 'receptionist',          -- receptionist 接待员 / service 服务老师 / dual 双重角色
    daily_total_cap INT NOT NULL DEFAULT 500 COMMENT '全日总上限',
    daily_total_used INT NOT NULL DEFAULT 0 COMMENT '今日已添加（Redis同步）',
    overall_status ENUM('normal','warning','blocked','melted') NOT NULL DEFAULT 'normal',  -- normal 正常 / warning 预警 / blocked 已拦截 / melted 已熔断
    status_reason JSON COMMENT '当前异常原因汇总',
    total_added INT NOT NULL DEFAULT 0 COMMENT '历史总添加',
    total_deleted INT NOT NULL DEFAULT 0 COMMENT '历史被删',
    melted_count_24h INT NOT NULL DEFAULT 0 COMMENT '24h内熔断次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (overall_status),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

-- employee：企微员工通讯录
-- 定时从企微API全量同步，用于活码创建页员工选择器
CREATE TABLE IF NOT EXISTS employee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    userid VARCHAR(100) NOT NULL UNIQUE COMMENT '企微UserID',
    name VARCHAR(100) NOT NULL COMMENT '员工姓名',
    department VARCHAR(500) COMMENT '所属部门ID列表(JSON数组)',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在职',
    last_sync_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近同步时间',
    INDEX idx_active (active),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企微员工通讯录';

-- employee 新增字段（企微侧状态，Layer1 主动过滤不可用员工）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = 'wechat_status') = 0,
    'ALTER TABLE employee ADD COLUMN wechat_status INT COMMENT ''企微侧状态: 1=已激活 2=禁用 4=未激活 5=已离职''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_agent：活码-员工关联
-- 活码-员工关联表
CREATE TABLE IF NOT EXISTS qr_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL COMMENT '活码ID',
    agent_userid VARCHAR(100) NOT NULL COMMENT '员工企微UserID',
    role ENUM('receptionist','service','dual') NOT NULL DEFAULT 'receptionist',
    daily_max INT NOT NULL DEFAULT 150 COMMENT '该活码下日添加上限',
    daily_current INT NOT NULL DEFAULT 0 COMMENT '今日已添加（Redis实时）',
    service_daily_max INT COMMENT '服务老师每日接手继承上限',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '分配优先级',
    status ENUM('active','full','removed','blocked') NOT NULL DEFAULT 'active',
    replaced_by VARCHAR(100) COMMENT '被谁替换',
    last_reset_at DATETIME COMMENT '上次清零时间',
    bind_target JSON COMMENT '服务老师继承目标配置',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (qr_code_id) REFERENCES qr_code(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid),
    INDEX idx_qr_agent (qr_code_id, agent_userid),
    INDEX idx_status (status),
    INDEX idx_agent_userid (agent_userid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码-员工关联表';

-- global_agent_pool：全局员工池
CREATE TABLE IF NOT EXISTS global_agent_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_userid VARCHAR(100) NOT NULL UNIQUE COMMENT '企微员工UserID',
    daily_max INT NOT NULL DEFAULT 150 COMMENT '全局日接待上限',
    daily_current INT NOT NULL DEFAULT 0 COMMENT '今日已接待（所有活码合计）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '分配优先级，越小越先被分配',
    status VARCHAR(20) NOT NULL DEFAULT 'standby' COMMENT 'standby/full/blocked',
    last_reset_at DATETIME COMMENT '上次日重置时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid),
    INDEX idx_status (status),
    INDEX idx_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局员工池';

-- customer：客户
-- 客户表
CREATE TABLE IF NOT EXISTS customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_userid VARCHAR(100) NOT NULL UNIQUE COMMENT '企微外部联系人ID',
    name VARCHAR(200) COMMENT '昵称',
    avatar VARCHAR(500) COMMENT '头像',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '1微信 2企业微信',                              -- 客户来源类型
    unionid VARCHAR(100) COMMENT '微信UnionID',
    added_agent VARCHAR(100) COMMENT '首次接待员UserID',
    current_agent VARCHAR(100) COMMENT '当前归属服务老师UserID',
    source_qr_id BIGINT COMMENT '来源活码ID',
    school_id VARCHAR(50) COMMENT '学校ID（冗余）',
    status ENUM('active','deleted') NOT NULL DEFAULT 'active',
    add_time DATETIME COMMENT '添加时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_external_userid (external_userid),
    INDEX idx_source_qr (source_qr_id),
    INDEX idx_current_agent (current_agent),
    INDEX idx_school (school_id),
    INDEX idx_add_time (add_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户表';

-- tag：标签
-- 标签表
CREATE TABLE IF NOT EXISTS tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '标签名',
    type ENUM('system','form','manual') NOT NULL DEFAULT 'manual',    -- system 系统自动 / form 表单收集 / manual 手动创建
    parent_id BIGINT COMMENT '父标签ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (parent_id) REFERENCES tag(id),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

-- tag 新增字段（企微标签同步）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND COLUMN_NAME = 'wecom_tag_id') = 0,
    'ALTER TABLE tag ADD COLUMN wecom_tag_id VARCHAR(50) COMMENT ''企业微信标签ID''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer_tag：客户标签关联
-- 客户-标签关联
CREATE TABLE IF NOT EXISTS customer_tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    source ENUM('system','form','manual') NOT NULL DEFAULT 'system',
    tagged_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id),
    UNIQUE KEY uk_customer_tag (customer_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户标签关联表';

-- agent_alert：异常记录
-- 异常记录表
CREATE TABLE IF NOT EXISTS agent_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_userid VARCHAR(100) NOT NULL COMMENT '员工ID',
    alert_type VARCHAR(50) NOT NULL COMMENT 'blocked/greeting_fail/low_approval/high_delete/traffic_spike/melt/empty_backup',  -- 异常类型枚举
    severity ENUM('low','medium','high') NOT NULL DEFAULT 'medium',
    detail JSON COMMENT '异常详情',
    auto_action ENUM('none','paused','removed','melted') DEFAULT 'none',
    status ENUM('open','resolved','auto_resolved') NOT NULL DEFAULT 'open',
    resolved_by VARCHAR(100) COMMENT '处理人',
    resolved_at DATETIME COMMENT '处理时间',
    qr_code_id BIGINT COMMENT '关联活码',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_userid) REFERENCES agent(userid),
    INDEX idx_agent_status (agent_userid, status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常记录表';

-- customer_transfer：继承记录
-- 继承记录表
CREATE TABLE IF NOT EXISTS customer_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    from_userid VARCHAR(100) NOT NULL COMMENT '转出（接待员）',
    to_userid VARCHAR(100) NOT NULL COMMENT '目标（服务老师）',
    qr_code_id BIGINT COMMENT '来源活码',
    transfer_time DATETIME COMMENT '发起时间',
    confirm_time DATETIME COMMENT '确认时间',
    status ENUM('pending_confirm','confirmed','rejected','timeout','api_failed','retry_limit') NOT NULL DEFAULT 'pending_confirm',  -- pending_confirm 待确认 / confirmed 已确认 / rejected 已拒绝 / timeout 超时 / api_failed 接口失败 / retry_limit 达重试上限
    retry_count INT NOT NULL DEFAULT 0,
    fail_reason VARCHAR(500) COMMENT '失败原因',
    form_filled_at_transfer BOOLEAN COMMENT '继承时是否已填写收集表单',
    note_sent BOOLEAN NOT NULL DEFAULT FALSE COMMENT '继承备注是否已写入',
    greeting_sent BOOLEAN NOT NULL DEFAULT FALSE COMMENT '交接欢迎语是否已发送',
    greeting_type ENUM('filled','unfilled') COMMENT '已填写版/未填写版',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    INDEX idx_customer_transfer (customer_id),
    INDEX idx_status (status),
    INDEX idx_transfer_time (transfer_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户继承记录表';

-- daily_report：日报表
-- 日报表
CREATE TABLE IF NOT EXISTS daily_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date DATE NOT NULL UNIQUE COMMENT '统计日期',
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
    detail_json JSON COMMENT '活码/员工明细快照',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日报表';

-- operation_log：操作日志
-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(100) COMMENT '操作人',
    action VARCHAR(100) NOT NULL COMMENT '操作类型',
    target_type VARCHAR(50) COMMENT '操作对象类型',
    target_id VARCHAR(100) COMMENT '操作对象ID',
    detail JSON COMMENT '操作详情',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at),
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- users：系统用户
-- 系统用户表，用于管理后台登录认证
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '登录用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码哈希',
    display_name VARCHAR(100) NOT NULL COMMENT '显示名称',
    role ENUM('admin','operator') NOT NULL DEFAULT 'operator' COMMENT '角色: admin/operator',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ============================================
-- 新增表：下载中心
-- ============================================

-- qr_download_log：活码下载日志
CREATE TABLE IF NOT EXISTS qr_download_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL COMMENT '活码ID',
    agent_userid VARCHAR(100) NOT NULL COMMENT '下载员工企微userid',
    downloaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时间',
    ip_address VARCHAR(50) COMMENT '下载来源IP',
    CONSTRAINT fk_download_qrcode FOREIGN KEY (qr_code_id) REFERENCES qr_code(id),
    INDEX idx_log_qrcode (qr_code_id),
    INDEX idx_log_userid (agent_userid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码下载日志';

-- district_manager：区县负责人配置
CREATE TABLE IF NOT EXISTS district_manager (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    region_city VARCHAR(50) NOT NULL COMMENT '城市',
    region_district VARCHAR(50) NOT NULL COMMENT '区/县',
    manager_userid VARCHAR(100) NOT NULL COMMENT '负责人企微userid',
    manager_name VARCHAR(100) NOT NULL COMMENT '负责人姓名',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_district (region_city, region_district),
    INDEX idx_manager_city (region_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区县负责人配置';

-- qr_rotate_log：活码轮换/扩容日志
CREATE TABLE IF NOT EXISTS qr_rotate_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id BIGINT NOT NULL COMMENT '关联活码ID',
    from_userid VARCHAR(100) COMMENT '轮换前负责老师userid（新增场景为空）',
    to_userid VARCHAR(100) NOT NULL COMMENT '轮换后/新分配老师userid',
    reason VARCHAR(500) COMMENT '轮换原因说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_rotate_qrcode (qr_code_id),
    INDEX idx_rotate_to_userid (to_userid),
    CONSTRAINT fk_rotate_qrcode FOREIGN KEY (qr_code_id) REFERENCES qr_code(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码轮换日志';

-- ============================================
-- 新增表：学校自助查询
-- ============================================

-- 学校主数据表（学校活码自助查询）
CREATE TABLE IF NOT EXISTS school (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '学校唯一标识',
    school_name     VARCHAR(128) NOT NULL COMMENT '学校名称',
    region_city     VARCHAR(64)  NOT NULL COMMENT '市州',
    region_district VARCHAR(64)  NOT NULL COMMENT '县区',
    has_qrcode      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已有活码（冗余字段）',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_school_city_district (region_city, region_district),
    INDEX idx_school_school_id (school_id),
    INDEX idx_school_deleted (deleted),
    INDEX idx_school_name (school_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学校主数据表';

-- 系统配置表（全局联系人等键值配置）
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(64) PRIMARY KEY COMMENT '配置键',
    config_value TEXT         COMMENT '配置值',
    updated_at   DATETIME     DEFAULT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 活码访问日志表（统一员工下载 + 学校自助查询审计）
CREATE TABLE IF NOT EXISTS qr_access_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    qr_code_id      BIGINT        COMMENT '活码ID（关联 qr_code.id）',
    action          ENUM('view','download') NOT NULL DEFAULT 'view' COMMENT '行为类型',
    channel         ENUM('employee','school') NOT NULL DEFAULT 'school' COMMENT '来源渠道',
    user_identity   VARCHAR(128)  COMMENT '身份标识（员工=企微userid，学校=IP摘要）',
    accessed_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    ip_address      VARCHAR(45)   COMMENT '客户端IP',
    user_agent      VARCHAR(512)  COMMENT '浏览器User-Agent',
    INDEX idx_qal_qr_code (qr_code_id),
    INDEX idx_qal_channel (channel),
    INDEX idx_qal_accessed (accessed_at),
    INDEX idx_qal_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码访问日志表';

-- district_manager 扩展：负责人活码
-- 使用动态 SQL 检查 INFORMATION_SCHEMA 兼容旧版 MySQL（<8.0.29 不支持 ADD COLUMN IF NOT EXISTS）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'district_manager' AND COLUMN_NAME = 'qr_config_id') = 0,
    'ALTER TABLE district_manager ADD COLUMN qr_config_id VARCHAR(64) DEFAULT NULL COMMENT ''企微联系我 config_id''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'district_manager' AND COLUMN_NAME = 'qr_url') = 0,
    'ALTER TABLE district_manager ADD COLUMN qr_url VARCHAR(512) DEFAULT NULL COMMENT ''负责人活码图片URL''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 初始全局联系人配置
INSERT IGNORE INTO system_config (config_key, config_value) VALUES
('global_contact_name', '火马客服'),
('global_contact_qr_config_id', ''),
('global_contact_qr_url', '');

-- 从已有活码中提取学校数据，使用 school_id 避免重复
INSERT IGNORE INTO school (school_id, school_name, region_city, region_district, has_qrcode)
SELECT DISTINCT school_id, school_name, region_city, region_district, 1
FROM qr_code
WHERE school_id IS NOT NULL AND school_name IS NOT NULL
  AND region_city IS NOT NULL AND region_district IS NOT NULL;

-- ============================================
-- 性能索引（2026-06-20 风险修复）
-- 使用动态 SQL 兼容不支持 CREATE INDEX IF NOT EXISTS 的 MySQL 版本
-- ============================================

-- customer: data_needs_repair 列（DataFill 降级标记）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND COLUMN_NAME = 'data_needs_repair') = 0,
    'ALTER TABLE customer ADD COLUMN data_needs_repair TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''数据填充降级标记''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 全局池: 按状态+优先级排序（替代 filesort）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_agent_pool' AND INDEX_NAME = 'idx_pool_status_sort') = 0,
    'CREATE INDEX idx_pool_status_sort ON global_agent_pool (status, sort_order)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_agent: 按活码+优先级排序
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_agent' AND INDEX_NAME = 'idx_qr_agent_sort') = 0,
    'CREATE INDEX idx_qr_agent_sort ON qr_agent (qr_code_id, sort_order)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_agent: 按活码+状态筛选
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_agent' AND INDEX_NAME = 'idx_qr_agent_status') = 0,
    'CREATE INDEX idx_qr_agent_status ON qr_agent (qr_code_id, status)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_agent: 按员工+状态筛选（员工管理页）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_agent' AND INDEX_NAME = 'idx_agent_qr_status') = 0,
    'CREATE INDEX idx_agent_qr_status ON qr_agent (agent_userid, status)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent_alert: 按状态+创建时间（告警列表页）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert' AND INDEX_NAME = 'idx_alert_status_created') = 0,
    'CREATE INDEX idx_alert_status_created ON agent_alert (status, created_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent_alert: 复合索引（员工+类型+状态+时间，高频查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert' AND INDEX_NAME = 'idx_alert_agent_type_status_created') = 0,
    'CREATE INDEX idx_alert_agent_type_status_created ON agent_alert (agent_userid, alert_type, status, created_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent_alert: 按严重级别+时间（巡检用）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert' AND INDEX_NAME = 'idx_alert_severity_created') = 0,
    'CREATE INDEX idx_alert_severity_created ON agent_alert (severity, created_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent_alert: 按活码ID（活码详情页告警列表）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_alert' AND INDEX_NAME = 'idx_alert_qr_code') = 0,
    'CREATE INDEX idx_alert_qr_code ON agent_alert (qr_code_id)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_rotate_log: 按活码+时间（轮换记录查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_rotate_log' AND INDEX_NAME = 'idx_rotate_qrcode_created') = 0,
    'CREATE INDEX idx_rotate_qrcode_created ON qr_rotate_log (qr_code_id, created_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_download_log: 按员工+下载时间（下载历史查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_download_log' AND INDEX_NAME = 'idx_log_userid_downloaded') = 0,
    'CREATE INDEX idx_log_userid_downloaded ON qr_download_log (agent_userid, downloaded_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- employee: 在职员工按姓名排序（员工选择器）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND INDEX_NAME = 'idx_employee_active_name') = 0,
    'CREATE INDEX idx_employee_active_name ON employee (active, name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer: 排行榜（按员工+添加时间）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_add_time_agent') = 0,
    'CREATE INDEX idx_customer_add_time_agent ON customer (add_time, added_agent)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer: 排行榜（按活码+添加时间）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_add_time_qr') = 0,
    'CREATE INDEX idx_customer_add_time_qr ON customer (add_time, source_qr_id)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer: 按添加时间+状态（日报统计）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_add_time_status') = 0,
    'CREATE INDEX idx_customer_add_time_status ON customer (add_time, status)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer_tag: 按标签ID查询关联客户
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_tag' AND INDEX_NAME = 'idx_customer_tag_tag_id') = 0,
    'CREATE INDEX idx_customer_tag_tag_id ON customer_tag (tag_id)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- operation_log: 按操作类型+时间（审计查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND INDEX_NAME = 'idx_operation_log_action_created') = 0,
    'CREATE INDEX idx_operation_log_action_created ON operation_log (action, created_at)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer_transfer: 按状态+重试次数（继承监控）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_transfer' AND INDEX_NAME = 'idx_transfer_status_retry') = 0,
    'CREATE INDEX idx_transfer_status_retry ON customer_transfer (status, retry_count)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_code: 按学校名称搜索（管理后台搜索）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND INDEX_NAME = 'idx_qr_code_school_name') = 0,
    'CREATE INDEX idx_qr_code_school_name ON qr_code (school_name)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================
-- 新增功能：欢迎语·收集表单·在职继承
-- ============================================

-- form_template：表单模板
CREATE TABLE IF NOT EXISTS form_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '模板名称',
    description VARCHAR(500) COMMENT '备注',
    fields JSON NOT NULL COMMENT '字段定义',
    tag_mapping JSON NOT NULL COMMENT '字段→打标/备注映射规则',
    remark_template VARCHAR(500) COMMENT '备注模板, 如 {{child_name}}-{{grade}}{{class}}',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单模板';

-- form_submission：表单提交记录
CREATE TABLE IF NOT EXISTS form_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_template_id BIGINT NOT NULL COMMENT 'FK→form_template.id',
    customer_id BIGINT NOT NULL COMMENT 'FK→customer.id',
    qr_code_id BIGINT COMMENT '来源活码',
    field_data JSON NOT NULL COMMENT '提交数据',
    tags_applied VARCHAR(500) COMMENT '已打标签,逗号分隔',
    remark_updated VARCHAR(500) COMMENT '已设备注',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fs_customer (customer_id),
    INDEX idx_fs_template (form_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单提交记录';

-- qr_code_group：活码分组（教育联盟）
CREATE TABLE IF NOT EXISTS qr_code_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '分组名称',
    region_city VARCHAR(50) COMMENT '市州',
    region_district VARCHAR(50) NOT NULL COMMENT '县区',
    group_type VARCHAR(20) NOT NULL DEFAULT 'alliance' COMMENT 'alliance=教育联盟',
    default_welcome_text VARCHAR(500) COMMENT '分组默认欢迎语',
    default_form_template_id BIGINT COMMENT 'FK→form_template.id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活码分组（教育联盟）';

-- qr_code_group 新增字段（联盟活码 + 学校列表）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code_group' AND COLUMN_NAME = 'qr_code_id') = 0,
    'ALTER TABLE qr_code_group ADD COLUMN qr_code_id BIGINT COMMENT ''联盟关联的单一活码ID''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code_group' AND COLUMN_NAME = 'school_list') = 0,
    'ALTER TABLE qr_code_group ADD COLUMN school_list TEXT COMMENT ''联盟包含的学校列表，一行一个''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_code 新增字段（欢迎语+表单+分组）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'form_template_id') = 0,
    'ALTER TABLE qr_code ADD COLUMN form_template_id BIGINT COMMENT ''FK→form_template, null=无表单''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'welcome_text') = 0,
    'ALTER TABLE qr_code ADD COLUMN welcome_text VARCHAR(500) COMMENT ''欢迎语, null=继承分组/系统默认''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'group_id') = 0,
    'ALTER TABLE qr_code ADD COLUMN group_id BIGINT COMMENT ''FK→qr_code_group, null=未分组''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 系统配置：默认欢迎语
INSERT IGNORE INTO system_config (config_key, config_value) VALUES
('default_welcome_text', '{{school_name}}家长您好～欢迎加入XX书店家校服务！');

-- ============================================
-- 场景分配优化：scene + department_id 字段
-- ============================================

-- qr_code: scene 列（场景枚举）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'scene') = 0,
    'ALTER TABLE qr_code ADD COLUMN scene ENUM(''daily_push'',''parent_meeting'')
        NOT NULL DEFAULT ''daily_push'' COMMENT ''场景：日常推送/家长会''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qr_code: department_id 列（所属企微部门）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qr_code' AND COLUMN_NAME = 'department_id') = 0,
    'ALTER TABLE qr_code ADD COLUMN department_id BIGINT
        COMMENT ''所属企微部门ID（扩容时同部门优先取人）''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- global_agent_pool: department_id 列（员工所属部门）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_agent_pool' AND COLUMN_NAME = 'department_id') = 0,
    'ALTER TABLE global_agent_pool ADD COLUMN department_id BIGINT
        COMMENT ''员工所属企微部门ID（从Employee同步，取主部门）''',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 部门索引（加速同部门 standby 查询）
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_agent_pool' AND INDEX_NAME = 'idx_pool_dept') = 0,
    'CREATE INDEX idx_pool_dept ON global_agent_pool(department_id, status, sort_order)',
    'SELECT 1'));
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
