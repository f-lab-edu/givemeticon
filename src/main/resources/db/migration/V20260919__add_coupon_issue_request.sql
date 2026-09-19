-- 쿠폰 접수 순서/재시도 멱등성/장애 복구의 기반이 되는 접수 원장(ledger).
-- id(AUTO_INCREMENT)의 삽입 순서가 서비스가 실제로 확정한 접수 순서다.
-- (user_id, stock_id) 유니크 제약이 동일 요청의 중복 접수를 막아 재시도를 멱등하게 만든다.
CREATE TABLE coupon_issue_request (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_id     INT          NOT NULL,
    user_id      INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    coupon_id    INT          NULL,
    reason       VARCHAR(255) NULL,
    created_date DATETIME(6)  NOT NULL,
    updated_date DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_coupon_issue_request_user_stock (user_id, stock_id)
);
