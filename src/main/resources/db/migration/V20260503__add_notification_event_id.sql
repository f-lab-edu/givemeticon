ALTER TABLE notification
    ADD COLUMN event_id VARCHAR(64) NULL;

UPDATE notification
SET event_id = CONCAT('legacy:', id)
WHERE event_id IS NULL;

ALTER TABLE notification
    MODIFY event_id VARCHAR(64) NOT NULL;

CREATE UNIQUE INDEX uk_notification_event_id
    ON notification (event_id);
