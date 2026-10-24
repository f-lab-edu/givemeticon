-- S4: the consumer's idempotency key is the message, not the seat (a Redis restart reuses seq).
-- Nullable: S0/S1/S3 write no message_id. Non-unique on purpose: this lab keeps "no UNIQUE keys" (S0 reproducibility).
ALTER TABLE coupon_issue
    ADD COLUMN message_id CHAR(36) NULL,
    ADD INDEX idx_coupon_issue_message_id (message_id);
