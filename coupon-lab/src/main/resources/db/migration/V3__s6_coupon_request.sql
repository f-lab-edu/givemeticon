-- S6: one row per request, winners and losers alike. coupon_issue keeps its "no constraints" shape (S0 reproducibility);
-- the constraints S6 relies on live here. Other stages never touch this table.
-- issued_member_id is member_id only for ISSUED rows (NULLs do not collide in a UNIQUE index), so UNIQUE (event_id,
-- issued_member_id) is "one coupon per member per event" without a partial index.
CREATE TABLE coupon_request (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    request_id       CHAR(36)    NOT NULL,
    event_id         BIGINT      NOT NULL,
    member_id        BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    seq              INT         NULL,
    reason           VARCHAR(40) NULL,
    accepted_at      DATETIME(6) NOT NULL,
    processed_at     DATETIME(6) NOT NULL,
    issued_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ISSUED' THEN member_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupon_request_request_id (request_id),
    UNIQUE KEY uq_coupon_request_issued_member (event_id, issued_member_id)
);
