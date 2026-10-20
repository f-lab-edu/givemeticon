CREATE TABLE coupon_mq_control (
    event_id BIGINT NOT NULL PRIMARY KEY,
    config_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_at DATETIME(6) NULL,
    ingest_paused BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_coupon_mq_control_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT chk_coupon_mq_control_proof CHECK (
        (open_token IS NULL AND verified_at IS NULL) OR
        (open_token IS NOT NULL AND verified_at IS NOT NULL))
);

CREATE TABLE coupon_mq_attempt (
    event_id BIGINT NOT NULL,
    member_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapped_member_id INT NULL,
    rejection_reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id, member_id, attempt_id),
    KEY idx_coupon_mq_attempt_mapping (event_id, mapped_member_id),
    CONSTRAINT fk_coupon_mq_attempt_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT fk_coupon_mq_attempt_application FOREIGN KEY (event_id, mapped_member_id)
        REFERENCES coupon_application(event_id, member_id),
    CONSTRAINT chk_coupon_mq_attempt_outcome CHECK (
        (outcome = 'MAPPED' AND mapped_member_id IS NOT NULL AND mapped_member_id = member_id
            AND open_token IS NOT NULL AND rejection_reason IS NULL) OR
        (outcome = 'REJECTED' AND mapped_member_id IS NULL AND open_token IS NULL
            AND rejection_reason IS NOT NULL AND rejection_reason = 'OPEN_PROOF_MISSING'))
);
