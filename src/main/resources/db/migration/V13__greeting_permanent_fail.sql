ALTER TABLE customer_transfer
    ADD COLUMN greeting_permanent_fail BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '欢迎语是否永久失败（区分真正成功和放弃重试）'
    AFTER greeting_sent;
