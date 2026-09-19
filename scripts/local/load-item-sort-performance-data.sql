-- Local/test only. Do not run this script against production.
-- Purpose: generate item, favorite, and item_favorite_meta data for sorting performance checks.
--
-- Usage:
--   mysql -u root -p givemeticon < scripts/local/load-item-sort-performance-data.sql
--
-- Change @dataset_size to one of: 'small', 'medium', 'large'
--   small  = item 1,000 / favorites 10,000
--   medium = item 10,000 / favorites 100,000
--   large  = item 100,000 / favorites 1,000,000

SET @dataset_size := 'large';
SET @reset_existing_load_data := FALSE;

SET @load_category_id := 900000001;
SET @load_brand_id := 900000001;
SET @load_item_id_base := 910000000;
SET @load_user_id_base := 920000000;
SET @load_cash_point_id_base := 930000000;
SET @load_favorite_id_base := 940000000;

SET @target_items := CASE @dataset_size
    WHEN 'small' THEN 1000
    WHEN 'medium' THEN 10000
    WHEN 'large' THEN 100000
    ELSE 1000
END;

SET @target_favorites := CASE @dataset_size
    WHEN 'small' THEN 10000
    WHEN 'medium' THEN 100000
    WHEN 'large' THEN 1000000
    ELSE 10000
END;

SET @target_users := CASE @dataset_size
    WHEN 'small' THEN 1000
    WHEN 'medium' THEN 5000
    WHEN 'large' THEN 10000
    ELSE 1000
END;

SET SESSION cte_max_recursion_depth = 1000001;

DELETE FROM item_favorite
WHERE @reset_existing_load_data
  AND id > @load_favorite_id_base
  AND id <= @load_favorite_id_base + 1000000;

DELETE FROM item_favorite_meta
WHERE @reset_existing_load_data
  AND item_id > @load_item_id_base
  AND item_id <= @load_item_id_base + 100000;

DELETE FROM item
WHERE @reset_existing_load_data
  AND id > @load_item_id_base
  AND id <= @load_item_id_base + 100000;

DELETE FROM user
WHERE @reset_existing_load_data
  AND id > @load_user_id_base
  AND id <= @load_user_id_base + 10000;

DELETE FROM cash_point
WHERE @reset_existing_load_data
  AND id > @load_cash_point_id_base
  AND id <= @load_cash_point_id_base + 10000;

DELETE FROM brand
WHERE @reset_existing_load_data
  AND id = @load_brand_id;

DELETE FROM category
WHERE @reset_existing_load_data
  AND id = @load_category_id;

INSERT INTO category (id, name)
VALUES (@load_category_id, 'LOAD_TEST_CATEGORY')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO brand (id, category_id, name, created_date, updated_date)
VALUES (@load_brand_id, @load_category_id, 'LOAD_TEST_BRAND', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    category_id = VALUES(category_id),
    name = VALUES(name),
    updated_date = NOW();

INSERT INTO cash_point (id, cash_point, created_date)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_users
)
SELECT
    @load_cash_point_id_base + n,
    0,
    NOW()
FROM seq
ON DUPLICATE KEY UPDATE cash_point = cash_point;

INSERT INTO user (
    id,
    account_id,
    cash_point_id,
    email,
    password,
    phone,
    user_role,
    is_active,
    provider,
    created_date,
    updated_date,
    deleted_date
)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_users
)
SELECT
    @load_user_id_base + n,
    NULL,
    @load_cash_point_id_base + n,
    CONCAT('load-user-', n, '@local.test'),
    '$2a$10$loadtestplaceholderpasswordhash',
    CONCAT('0109', LPAD(n, 7, '0')),
    'USER',
    TRUE,
    NULL,
    NOW(),
    NOW(),
    NULL
FROM seq
ON DUPLICATE KEY UPDATE
    cash_point_id = VALUES(cash_point_id),
    updated_date = NOW(),
    deleted_date = NULL;

INSERT INTO item (id, brand_id, name, price, created_date, updated_date)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_items
)
SELECT
    @load_item_id_base + n,
    @load_brand_id,
    CONCAT('LOAD_TEST_ITEM_', n),
    1000 + (n % 50000),
    NOW(),
    NOW()
FROM seq
ON DUPLICATE KEY UPDATE
    brand_id = VALUES(brand_id),
    name = VALUES(name),
    price = VALUES(price),
    updated_date = NOW();

INSERT INTO item_favorite_meta (item_id, like_count, view_count)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_items
)
SELECT
    @load_item_id_base + n,
    0,
    1000000 - n
FROM seq
LEFT JOIN item_favorite_meta existing_meta
    ON existing_meta.item_id = @load_item_id_base + n
WHERE existing_meta.item_id IS NULL;

UPDATE item_favorite_meta
SET like_count = 0,
    view_count = 1000000 - (item_id - @load_item_id_base)
WHERE item_id > @load_item_id_base
  AND item_id <= @load_item_id_base + @target_items;

INSERT INTO item_favorite (id, user_id, item_id, is_favorite)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_favorites
)
SELECT
    @load_favorite_id_base + n,
    @load_user_id_base + (((FLOOR((n - 1) / @target_items) * 997) + ((n - 1) % @target_items)) % @target_users) + 1,
    @load_item_id_base + ((n - 1) % @target_items) + 1,
    TRUE
FROM seq
ON DUPLICATE KEY UPDATE
    user_id = VALUES(user_id),
    item_id = VALUES(item_id),
    is_favorite = TRUE;

UPDATE item_favorite_meta m
JOIN (
    SELECT item_id, COUNT(*) AS like_count
    FROM item_favorite
    WHERE item_id > @load_item_id_base
      AND item_id <= @load_item_id_base + @target_items
      AND is_favorite = TRUE
    GROUP BY item_id
) f ON f.item_id = m.item_id
SET m.like_count = f.like_count
WHERE m.item_id > @load_item_id_base
  AND m.item_id <= @load_item_id_base + @target_items;

SELECT
    @dataset_size AS dataset_size,
    @target_items AS target_items,
    (SELECT COUNT(*) FROM item WHERE id > @load_item_id_base AND id <= @load_item_id_base + @target_items) AS inserted_items,
    @target_favorites AS target_favorites,
    (SELECT COUNT(*) FROM item_favorite WHERE id > @load_favorite_id_base AND id <= @load_favorite_id_base + @target_favorites) AS inserted_favorites,
    (SELECT COUNT(*) FROM item_favorite_meta WHERE item_id > @load_item_id_base AND item_id <= @load_item_id_base + @target_items) AS inserted_meta_rows;
