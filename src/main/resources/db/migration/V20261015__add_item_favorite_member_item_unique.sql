-- Issue #184: run only after stopping all favorite writers and reviewing existing data.
-- Reject duplicate/null logical keys rather than silently deleting a user's state.
-- The legacy tables must already exist (as for the earlier coupon migrations).
ALTER TABLE item_favorite
    ADD CONSTRAINT chk_item_favorite_logical_key CHECK (user_id IS NOT NULL AND item_id IS NOT NULL),
    ADD CONSTRAINT uk_item_favorite_user_item UNIQUE (user_id, item_id);
