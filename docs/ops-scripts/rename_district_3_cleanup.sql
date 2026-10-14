-- ============================================================================
-- rename_district_3_cleanup.sql   【第 3 步 · 最后跑】
-- 本地 tag / customer_tag 收尾：把 5 个源县区标签的绑定重接到目标店标签，再删源标签行。
--
-- 前置：第 2 步的 delete --go 已把企微里 5 个源标签删掉（wid 消失）。
--       必须在这之后跑（否则企微标签同步会按 wid 把删掉的本地行复活）。
--
-- 每个源名在本地有 2 行：一条 gk='县区'、一条 gk=''（老格式），共用同一 wecom_tag_id。
-- 两行都要处理。脚本按 (name, group_keyword IN ('县区',''), wecom_tag_id) 定位，与 id 无关。
--
-- 每对做 4 件事（顺序不能乱，因 tag.parent_id 自引用 FK + customer_tag.tag_id FK）：
--   1) 把「父标签 = 源县区行」的学校标签，改挂到目标县区行（否则删源行被 FK 挡）
--   2) 客户同时已有目标标签的重复绑定行先删（避免撞 uk_customer_tag(customer_id,tag_id)）
--   3) 其余源绑定 UPDATE 到目标行
--   4) 删源标签行（此时已无任何引用）
--
-- 库：bookstore_qrcode
-- ============================================================================

-- ---------- 门禁：第 1 步必须已跑干净（下面三行应全为 0）----------
-- 若 qr_code/school 仍有源名：可能是第 1 步后、districts 缓存过期(≤5min)前新建的活码，
-- 请先重跑 rename_district_1_region.sql，再回来跑本文件。
SELECT '门禁·qr_code 残留源名' AS gate, COUNT(*) AS n FROM qr_code
 WHERE (region_city,region_district) IN
       (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));
SELECT '门禁·school 残留源名' AS gate, COUNT(*) AS n FROM school
 WHERE deleted=0 AND (region_city,region_district) IN
       (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));
SELECT '门禁·qr_code_group 残留源名' AS gate, COUNT(*) AS n FROM qr_code_group
 WHERE (region_city,region_district) IN
       (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));

-- ---------- 前置核对（记录，便于前后对比；甘州区应全为 0） ----------
SELECT t.name, t.group_keyword, t.id AS tag_id,
       (SELECT COUNT(*) FROM customer_tag ct WHERE ct.tag_id=t.id) AS binds,
       (SELECT COUNT(*) FROM tag c WHERE c.parent_id=t.id)          AS children
FROM tag t
WHERE t.name IN ('城关区','甘州区','秦州区','武都区','西峰区')
  AND t.group_keyword IN ('县区','')
ORDER BY t.name, t.group_keyword;

START TRANSACTION;

-- ========================= 城关区 → 兰州市 =========================
SET @src_wid := 'etZUH8SgAAYY6fofVzxOGZ39uKn38V_w';
SET @dst := (SELECT id FROM tag WHERE name='兰州市' AND group_keyword='县区' AND wecom_tag_id='etZUH8SgAAFGIN0g4s6cmNXJjfLcq9Fg');
UPDATE tag SET parent_id=@dst WHERE parent_id IN
  (SELECT id FROM (SELECT id FROM tag WHERE name='城关区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid) x);
DELETE ct FROM customer_tag ct JOIN tag s ON ct.tag_id=s.id
 WHERE s.name='城关区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid
   AND EXISTS (SELECT 1 FROM (SELECT customer_id, tag_id FROM customer_tag) z
               WHERE z.customer_id=ct.customer_id AND z.tag_id=@dst);
UPDATE customer_tag ct JOIN tag s ON ct.tag_id=s.id SET ct.tag_id=@dst
 WHERE s.name='城关区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid;
DELETE FROM tag WHERE name='城关区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid;

-- ========================= 甘州区 → 张掖市 =========================
SET @src_wid := 'etZUH8SgAAjDFdJ0l7QaXcqTimCre9rQ';
SET @dst := (SELECT id FROM tag WHERE name='张掖市' AND group_keyword='县区' AND wecom_tag_id='etZUH8SgAAb7XArI5R_WJpGIZKBzooNg');
UPDATE tag SET parent_id=@dst WHERE parent_id IN
  (SELECT id FROM (SELECT id FROM tag WHERE name='甘州区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid) x);
DELETE ct FROM customer_tag ct JOIN tag s ON ct.tag_id=s.id
 WHERE s.name='甘州区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid
   AND EXISTS (SELECT 1 FROM (SELECT customer_id, tag_id FROM customer_tag) z
               WHERE z.customer_id=ct.customer_id AND z.tag_id=@dst);
UPDATE customer_tag ct JOIN tag s ON ct.tag_id=s.id SET ct.tag_id=@dst
 WHERE s.name='甘州区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid;
DELETE FROM tag WHERE name='甘州区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid;

-- ========================= 秦州区 → 天水市 =========================
SET @src_wid := 'etZUH8SgAAmcAjtn0hFxBELzk1CrDHew';
SET @dst := (SELECT id FROM tag WHERE name='天水市' AND group_keyword='县区' AND wecom_tag_id='etZUH8SgAA32AwppUdR-h-qBTKBfJ40g');
UPDATE tag SET parent_id=@dst WHERE parent_id IN
  (SELECT id FROM (SELECT id FROM tag WHERE name='秦州区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid) x);
DELETE ct FROM customer_tag ct JOIN tag s ON ct.tag_id=s.id
 WHERE s.name='秦州区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid
   AND EXISTS (SELECT 1 FROM (SELECT customer_id, tag_id FROM customer_tag) z
               WHERE z.customer_id=ct.customer_id AND z.tag_id=@dst);
UPDATE customer_tag ct JOIN tag s ON ct.tag_id=s.id SET ct.tag_id=@dst
 WHERE s.name='秦州区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid;
DELETE FROM tag WHERE name='秦州区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid;

-- ========================= 武都区 → 陇南市 =========================
SET @src_wid := 'etZUH8SgAAwNLHhxbGHR7JXOF2bHNcQg';
SET @dst := (SELECT id FROM tag WHERE name='陇南市' AND group_keyword='县区' AND wecom_tag_id='etZUH8SgAA9n2ejSPPrrNg9QBGsinlMw');
UPDATE tag SET parent_id=@dst WHERE parent_id IN
  (SELECT id FROM (SELECT id FROM tag WHERE name='武都区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid) x);
DELETE ct FROM customer_tag ct JOIN tag s ON ct.tag_id=s.id
 WHERE s.name='武都区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid
   AND EXISTS (SELECT 1 FROM (SELECT customer_id, tag_id FROM customer_tag) z
               WHERE z.customer_id=ct.customer_id AND z.tag_id=@dst);
UPDATE customer_tag ct JOIN tag s ON ct.tag_id=s.id SET ct.tag_id=@dst
 WHERE s.name='武都区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid;
DELETE FROM tag WHERE name='武都区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid;

-- ========================= 西峰区 → 庆阳市 =========================
SET @src_wid := 'etZUH8SgAA1F38tY4CPuVOS26_3-kk3A';
SET @dst := (SELECT id FROM tag WHERE name='庆阳市' AND group_keyword='县区' AND wecom_tag_id='etZUH8SgAA4pNFhsNWkmJQzHMcifAHKA');
UPDATE tag SET parent_id=@dst WHERE parent_id IN
  (SELECT id FROM (SELECT id FROM tag WHERE name='西峰区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid) x);
DELETE ct FROM customer_tag ct JOIN tag s ON ct.tag_id=s.id
 WHERE s.name='西峰区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid
   AND EXISTS (SELECT 1 FROM (SELECT customer_id, tag_id FROM customer_tag) z
               WHERE z.customer_id=ct.customer_id AND z.tag_id=@dst);
UPDATE customer_tag ct JOIN tag s ON ct.tag_id=s.id SET ct.tag_id=@dst
 WHERE s.name='西峰区' AND s.group_keyword IN ('县区','') AND s.wecom_tag_id=@src_wid;
DELETE FROM tag WHERE name='西峰区' AND group_keyword IN ('县区','') AND wecom_tag_id=@src_wid;

COMMIT;

-- ---------- 后置核对 ----------
-- (a) 5 个源名在本地应彻底消失
SELECT '残留·源标签行' AS chk, name, group_keyword, id
FROM tag WHERE name IN ('城关区','甘州区','秦州区','武都区','西峰区');
-- (b) 目标县区行的绑定数应等于「原源绑定之和」（含原有）—— 对照前置计数
SELECT t.name, t.group_keyword, t.id AS tag_id,
       (SELECT COUNT(*) FROM customer_tag ct WHERE ct.tag_id=t.id) AS binds
FROM tag t
WHERE t.name IN ('兰州市','张掖市','天水市','陇南市','庆阳市') AND t.group_keyword='县区';
