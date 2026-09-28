-- 접수(coupon_application) 원장과 분리된 실제 발급 원장이다. 기존 레거시 `coupon` 테이블
-- (userId/stockId/couponNumber 등, coupon_issue_request 경로 전용)과 스키마가 전혀 달라 이름을
-- 구분했다: 이 테이블은 coupon_event/coupon_application 원장에서만 참조한다.
CREATE TABLE coupon_award (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    application_id BIGINT       NOT NULL,
    event_id       BIGINT       NOT NULL,
    member_id      INT          NOT NULL,
    tier           VARCHAR(20)  NOT NULL,
    points         INT          NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    issued_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    redeemed_at    DATETIME(6)  NULL,
    CONSTRAINT uk_coupon_award_application UNIQUE (application_id),
    CONSTRAINT uk_coupon_award_event_member UNIQUE (event_id, member_id),
    CONSTRAINT fk_coupon_award_application FOREIGN KEY (application_id) REFERENCES coupon_application(id),
    CONSTRAINT chk_coupon_award_points CHECK (points >= 0),
    KEY idx_coupon_award_event (event_id)
);
