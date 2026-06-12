-- 企微标签手动同步到本地 DB 的参考 SQL
-- 前提：已在企微管理后台创建了标签组"家校服务"及其下的所有标签
--
-- 使用方法：
-- 1. 启动应用后，调用 GET /api/wecom/callback 先不触发
-- 2. 运行 WecomApiClient.getCorpTagList() 拿到企微标签列表
-- 3. 按以下格式手工写入本地 DB

-- 示例：假设企微上有以下标签结构
-- 标签组：家校服务 (group_id: etg_xxx)
--   ├─ 广州市 (tag_id: et_city_gz)
--   ├─ 天河区 (tag_id: et_dist_th, parent: 广州市)
--   ├─ 广州市第一中学 (tag_id: et_sch_001, parent: 天河区)
--   └─ ...

-- 写本地 tag 表（id 自增，只需指定 name + type + wecom_tag_id + parent_id）
INSERT INTO tag (name, type, wecom_tag_id, parent_id) VALUES
('广州市', 'system', 'et_city_gz', NULL),
('天河区', 'system', 'et_dist_th', 1),     -- parent_id 指向广州市的本地 id
('广州市第一中学', 'system', 'et_sch_001', 2); -- parent_id 指向天河区的本地 id

-- ⚠️ 实际执行时需要先通过 API 拿到真实 tag_id，
-- 然后按层级关系依次 INSERT（先插父标签、后插子标签，拿到自增 id 后填 parent_id）
