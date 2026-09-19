-- Test-only schema for CouponIssuanceMySQLIntegrationTest.
-- Mirrors src/main/resources/mapper/{CouponMapper,CouponStockMapper}.xml column usage,
-- with the unique index from src/main/resources/db/migration/V20260602__add_coupon_user_stock_unique_key.sql
-- applied. Nothing here is executed against the app's real schema/migration path; there is
-- no Flyway/Liquibase in this project (see build.gradle), so whether uk_coupon_user_stock
-- actually exists in a given deployed environment depends entirely on someone having run
-- that migration file by hand.

CREATE TABLE IF NOT EXISTS coupon_stock (
    id     INT PRIMARY KEY AUTO_INCREMENT,
    total  INT NOT NULL,
    remain INT NOT NULL
);

CREATE TABLE IF NOT EXISTS coupon (
    id            INT PRIMARY KEY AUTO_INCREMENT,
    user_id       INT         NOT NULL,
    stock_id      INT         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    coupon_type   VARCHAR(50)  NOT NULL,
    coupon_number VARCHAR(64)  NOT NULL,
    price         INT         NOT NULL,
    is_used       TINYINT(1)  NOT NULL DEFAULT 0,
    created_date  DATE        NOT NULL,
    expired_date  DATE        NOT NULL,
    UNIQUE KEY uk_coupon_user_stock (user_id, stock_id)
);

-- src/main/resources/db/migration/V20260919__add_coupon_issue_request.sql
-- + V20260919_2__add_coupon_details_and_stale_index_to_issue_request.sql
CREATE TABLE IF NOT EXISTS coupon_issue_request (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_id     INT          NOT NULL,
    user_id      INT          NOT NULL,
    coupon_name  VARCHAR(255) NOT NULL,
    coupon_type  VARCHAR(50)  NOT NULL,
    price        INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    coupon_id    INT          NULL,
    reason       VARCHAR(255) NULL,
    created_date DATETIME(6)  NOT NULL,
    updated_date DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_coupon_issue_request_user_stock (user_id, stock_id),
    KEY idx_coupon_issue_request_status_updated (status, updated_date)
);
