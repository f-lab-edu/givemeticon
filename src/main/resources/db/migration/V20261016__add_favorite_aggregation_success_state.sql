-- The publisher updates this row in the same transaction as the complete Meta snapshot.
-- NULL means no successful snapshot has been recorded yet; startup may publish immediately.
ALTER TABLE item_favorite_aggregation_lock
    ADD COLUMN last_snapshot_at DATETIME(6) NULL,
    ADD COLUMN last_success_at DATETIME(6) NULL,
    ADD COLUMN last_item_count BIGINT NOT NULL DEFAULT 0;
