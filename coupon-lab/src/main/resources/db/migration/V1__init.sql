-- S0 (read-then-decrement, no locks) deliberately has no UNIQUE keys and no FOREIGN KEYs.
-- Adding constraints is the decision of a later stage, not of this one.

CREATE TABLE coupon_event (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    name               VARCHAR(100) NOT NULL,
    total_quantity     INT          NOT NULL,
    remaining_quantity INT          NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE coupon_issue (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    member_id  BIGINT      NOT NULL,
    seq        INT         NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
