-- V20: 客户删除关系事件表
-- 记录企微回调中「客户删除员工」(del_follow_user) 与「员工删除客户」(del_external_contact)
-- 两类删除关系事件，供每日日报汇总推送（只报客户删员工）。
-- 每条记录保留被删的客户 external_userid、被删的员工 userid、删除方向与发生时间。
-- 使用 CREATE TABLE IF NOT EXISTS 保证幂等。

CREATE TABLE IF NOT EXISTS customer_deletion_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_userid VARCHAR(100) NOT NULL COMMENT '被删关系的客户 external_userid',
    userid          VARCHAR(100) NOT NULL COMMENT '被删关系的员工 userid',
    direction       VARCHAR(30)  NOT NULL COMMENT '删除方向: CUSTOMER_DELETED_AGENT / AGENT_DELETED_CUSTOMER',
    source          VARCHAR(50)  COMMENT '删除来源（仅员工删客户事件携带，如 DELETE_BY_TRANSFER），可为 null',
    deleted_at      DATETIME     NOT NULL COMMENT '删除发生时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    INDEX idx_deletion_direction_time (direction, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户删除关系事件表';
