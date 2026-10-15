-- Issue #184. Maintenance only: stop ALL favorite/item/meta writers, back up, and review
-- these preflight result sets BEFORE executing the transaction section below.
-- Do not pipe this entire file blindly into a production client.
-- Resolve duplicate/null favorite keys and missing/duplicate meta rows first.
-- No automatic favorite deletion/merge: the intended active state needs an owner decision.
SELECT user_id, item_id, COUNT(*) AS rows_per_member_item
FROM item_favorite GROUP BY user_id, item_id
HAVING COUNT(*) > 1 OR user_id IS NULL OR item_id IS NULL;

SELECT item_id, COUNT(*) AS meta_rows
FROM item_favorite_meta GROUP BY item_id HAVING COUNT(*) <> 1 OR item_id IS NULL;

SELECT i.id AS item_without_meta
FROM item i LEFT JOIN item_favorite_meta m ON m.item_id = i.id
WHERE m.item_id IS NULL;

SELECT f.item_id AS favorite_without_item
FROM item_favorite f LEFT JOIN item i ON i.id = f.item_id
WHERE i.id IS NULL;

-- Preview differences (including stale positive counts for items with no active favorite).
SELECT m.item_id, m.like_count AS stored_count, COALESCE(f.active_count, 0) AS expected_count
FROM item_favorite_meta m
LEFT JOIN (
    SELECT item_id, COUNT(*) AS active_count FROM item_favorite
    WHERE is_favorite = TRUE GROUP BY item_id
) f ON f.item_id = m.item_id
WHERE m.like_count <> COALESCE(f.active_count, 0) OR m.like_count IS NULL;

-- Execute only after preflight is clean, affected counts reviewed, and writers are stopped.
-- These locks follow the application lock order. They do NOT replace the maintenance window:
-- an old application or an external writer may bypass the protocol.
START TRANSACTION;
SELECT id FROM item ORDER BY id FOR UPDATE;
UPDATE item_favorite_meta m
LEFT JOIN (
    SELECT item_id, COUNT(*) AS active_count FROM item_favorite
    WHERE is_favorite = TRUE GROUP BY item_id
) f ON f.item_id = m.item_id
SET m.like_count = COALESCE(f.active_count, 0);

-- Must return zero rows. Review before COMMIT; ROLLBACK on unexpected differences.
SELECT m.item_id, m.like_count, COALESCE(f.active_count, 0) AS expected_count
FROM item_favorite_meta m
LEFT JOIN (
    SELECT item_id, COUNT(*) AS active_count FROM item_favorite
    WHERE is_favorite = TRUE GROUP BY item_id
) f ON f.item_id = m.item_id
WHERE m.like_count <> COALESCE(f.active_count, 0) OR m.like_count IS NULL;
-- Intentionally no COMMIT: the operator commits only after inspecting the result.
-- COMMIT;
-- ROLLBACK;
