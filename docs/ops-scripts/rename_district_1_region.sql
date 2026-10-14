-- ============================================================================
-- rename_district_1_region.sql   【第 1 步 · 最先跑】
-- 把 5 个错误县区名（源）改成对应"店名 = 市名"（目标），只动 region_district，
-- 不动 region_city（市州维度的「兰州市」标签是另一个，别混）。
--
--   兰州市/城关区 → 兰州市/兰州市      (9 活码 / 12 学校)
--   张掖市/甘州区 → 张掖市/张掖市      (0 活码 / 0 学校 · 纯为标签清理)
--   天水市/秦州区 → 天水市/天水市      (56 活码 / 56 学校)
--   陇南市/武都区 → 陇南市/陇南市      (2 活码 / 2 学校)
--   庆阳市/西峰区 → 庆阳市/庆阳市      (34 活码 / 34 学校)
--
-- 作用表：qr_code、school、qr_code_group（都有 region_city/region_district）
-- 幂等：改完再跑一次 WHERE 命中 0 行。
-- 库：bookstore_qrcode
--
-- 先跑「前置核对」，确认命中行数与下表一致，再跑 UPDATE；最后跑「后置核对」。
-- ============================================================================

-- ---------- 前置核对（记录改前行数，应与上面括号一致） ----------
SELECT 'qr_code' AS tbl, region_city, region_district, COUNT(*) AS n
FROM qr_code
WHERE (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'))
GROUP BY region_city, region_district;

SELECT 'school(未删)' AS tbl, region_city, region_district, COUNT(*) AS n
FROM school
WHERE deleted = 0 AND (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'))
GROUP BY region_city, region_district;

SELECT 'qr_code_group' AS tbl, region_city, region_district, COUNT(*) AS n
FROM qr_code_group
WHERE (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'))
GROUP BY region_city, region_district;

-- 安全网：万一有「城关区」挂在别的市下，会多出行 —— 正常应为空。
SELECT '异常·源县区名挂非预期市' AS warn, region_city, region_district, COUNT(*) AS n
FROM qr_code
WHERE region_district IN ('城关区','甘州区','秦州区','武都区','西峰区')
  AND (region_city, region_district) NOT IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'))
GROUP BY region_city, region_district;

-- ---------- 改（三张表同一套映射） ----------
START TRANSACTION;

UPDATE qr_code
SET region_district = CASE region_district
        WHEN '城关区' THEN '兰州市'
        WHEN '甘州区' THEN '张掖市'
        WHEN '秦州区' THEN '天水市'
        WHEN '武都区' THEN '陇南市'
        WHEN '西峰区' THEN '庆阳市'
      END
WHERE (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));

UPDATE school
SET region_district = CASE region_district
        WHEN '城关区' THEN '兰州市'
        WHEN '甘州区' THEN '张掖市'
        WHEN '秦州区' THEN '天水市'
        WHEN '武都区' THEN '陇南市'
        WHEN '西峰区' THEN '庆阳市'
      END
WHERE (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));

UPDATE qr_code_group
SET region_district = CASE region_district
        WHEN '城关区' THEN '兰州市'
        WHEN '甘州区' THEN '张掖市'
        WHEN '秦州区' THEN '天水市'
        WHEN '武都区' THEN '陇南市'
        WHEN '西峰区' THEN '庆阳市'
      END
WHERE (region_city, region_district) IN
      (('兰州市','城关区'),('张掖市','甘州区'),('天水市','秦州区'),('陇南市','武都区'),('庆阳市','西峰区'));

COMMIT;

-- ---------- 后置核对：源名应全部为 0；目标名下应含刚并入的行 ----------
SELECT '残留·源名' AS chk, 'qr_code' AS tbl, COUNT(*) AS n FROM qr_code
 WHERE region_district IN ('城关区','甘州区','秦州区','武都区','西峰区')
UNION ALL
SELECT '残留·源名', 'school', COUNT(*) FROM school
 WHERE deleted=0 AND region_district IN ('城关区','甘州区','秦州区','武都区','西峰区')
UNION ALL
SELECT '残留·源名', 'qr_code_group', COUNT(*) FROM qr_code_group
 WHERE region_district IN ('城关区','甘州区','秦州区','武都区','西峰区');

SELECT '归并后·目标店' AS chk, region_city, region_district, COUNT(*) AS n
FROM qr_code
WHERE (region_city, region_district) IN
      (('兰州市','兰州市'),('张掖市','张掖市'),('天水市','天水市'),('陇南市','陇南市'),('庆阳市','庆阳市'))
GROUP BY region_city, region_district;
