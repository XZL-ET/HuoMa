-- V7: 拆分 retryCount → retryCount (API重试) + pollCount (轮询追踪)
-- 将 CustomerTransfer.retryCount 的两种语义分离为两个独立字段
-- 同时添加 version 列用于乐观锁，防止并发更新覆盖

ALTER TABLE customer_transfer
    ADD COLUMN poll_count INT NOT NULL DEFAULT 0 AFTER retry_count,
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER updated_at;

-- 历史数据迁移：将现有 retry_count 值复制到 poll_count
-- (历史数据中 retryCount 的具体用途无法区分，保守复制确保轮询计数不丢失)
UPDATE customer_transfer SET poll_count = retry_count WHERE status = 'pending_confirm';
