-- Stop legacy realtime-counter writers before deploying the snapshot publisher.
-- Existing duplicate/null meta keys must be reviewed, never silently deleted.
ALTER TABLE item_favorite_meta
    ADD CONSTRAINT chk_item_favorite_meta_item CHECK (item_id IS NOT NULL),
    ADD CONSTRAINT uk_item_favorite_meta_item UNIQUE (item_id);

-- Transaction-owned job mutex: NOWAIT skips overlaps; commit/rollback releases ownership.
CREATE TABLE item_favorite_aggregation_lock (
    id INT PRIMARY KEY,
    CONSTRAINT chk_item_favorite_aggregation_lock_id CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO item_favorite_aggregation_lock (id) VALUES (1);
