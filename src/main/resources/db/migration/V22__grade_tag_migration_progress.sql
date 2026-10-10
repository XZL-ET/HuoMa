-- V22: 年级标签归并进度/报告表
-- 记录 Phase A 逐客户对账的结果，供后台看板展示与人工复核；同时按
-- (customer_id, local_tag_id) 作为已处理标记，使迁移 Job 可断点续跑。
-- 注意：此表【不承载正确性】——所有写操作（markTag add、按 wid 重接线）本身幂等，
-- 重跑 = 续跑，最坏情况只是重复处理一遍。
-- 使用 CREATE TABLE IF NOT EXISTS 保证幂等。

CREATE TABLE IF NOT EXISTS grade_tag_migration_progress (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id          BIGINT       NOT NULL COMMENT '客户主键',
    external_userid      VARCHAR(100) COMMENT '客户企微 external_userid，便于人工核对',
    row_class            VARCHAR(4)   NOT NULL COMMENT '行级分类 R1/R2/R3',
    local_tag_id         BIGINT       NOT NULL COMMENT '迁移前客户绑定的非年级组 tag 行 id',
    local_tag_name       VARCHAR(100) COMMENT '迁移前本地行名（升学改名时可能与企微当前名不一致）',
    target_wecom_tag_id  VARCHAR(50)  COMMENT '归并后应指向的「年级」组企微标签 id',
    target_tag_id        BIGINT       COMMENT '归并后本地 canonical tag 行 id',
    action               VARCHAR(40)  NOT NULL COMMENT 'LOCAL_ONLY/WECOM_RELINK/WECOM_MARKED/WECOM_MARK_FAILED/SKIPPED_NO_AGENT/RELATION_GONE',
    requires_review      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=按本地名补打（升学可能差一级），需人工复核',
    note                 VARCHAR(255) COMMENT '备注（错误信息等）',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gm_progress_item (customer_id, local_tag_id),
    INDEX idx_gm_progress_class (row_class, requires_review)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级标签归并进度/报告表';
