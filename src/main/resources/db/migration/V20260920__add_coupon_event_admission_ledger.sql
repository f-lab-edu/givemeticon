CREATE TABLE coupon_event (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id                VARCHAR(64)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    starts_at_utc            DATETIME(6)  NOT NULL,
    total_quantity           INT          NOT NULL,
    high_quantity            INT          NOT NULL,
    high_points              INT          NOT NULL,
    normal_points            INT          NOT NULL,
    next_acceptance_sequence BIGINT       NOT NULL DEFAULT 0,
    issued_quantity          INT          NOT NULL DEFAULT 0,
    settings_locked_at       DATETIME(6)  NULL,
    created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at                DATETIME(6)  NULL,
    CONSTRAINT uk_coupon_event_public_id UNIQUE (public_id),
    CONSTRAINT chk_coupon_event_quantity CHECK (total_quantity > 0),
    CONSTRAINT chk_coupon_event_high_quantity CHECK (high_quantity >= 0 AND high_quantity <= total_quantity),
    CONSTRAINT chk_coupon_event_points CHECK (high_points >= 0 AND normal_points >= 0),
    CONSTRAINT chk_coupon_event_issued_quantity CHECK (issued_quantity >= 0 AND issued_quantity <= total_quantity)
);

CREATE TABLE coupon_application (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_request_id   VARCHAR(64)  NOT NULL,
    event_id            BIGINT       NOT NULL,
    member_id           INT          NOT NULL,
    acceptance_sequence BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    accepted_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finalized_at        DATETIME(6)  NULL,
    failure_reason      VARCHAR(100) NULL,
    CONSTRAINT uk_coupon_application_public_request UNIQUE (public_request_id),
    CONSTRAINT uk_coupon_application_event_member UNIQUE (event_id, member_id),
    CONSTRAINT uk_coupon_application_event_sequence UNIQUE (event_id, acceptance_sequence),
    CONSTRAINT fk_coupon_application_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    KEY idx_coupon_application_event_status_sequence (event_id, status, acceptance_sequence),
    KEY idx_coupon_application_member_accepted (member_id, accepted_at)
);
