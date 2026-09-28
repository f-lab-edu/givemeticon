-- 쿠폰 사용(redeem)과 사용에 따른 포인트 적립 원장이다. coupon_award.status를
-- ISSUED -> REDEEMED로 바꾸는 것과 같은 트랜잭션에서 처리한다(발급과는 별개의 업무 트랜잭션).
--
-- 레거시 CashPointService/CashPoint(user 테이블에 연결된 지갑)를 재사용하지 않았다: 레거시
-- cash_point_earn_history.coupon_id(INT, UNIQUE)는 레거시 coupon.id를 가리키는데,
-- coupon_award.id도 1부터 시작하는 별도 시퀀스라 같은 컬럼에 섞어 쓰면 서로 다른 원장의 값이
-- 우연히 같아져 중복 적립 방지/조회가 잘못될 위험이 있다. coupon_award 테이블을 레거시 coupon과
-- 별도로 둔 것과 같은 이유로, 이 새 원장도 member_id 하나로 자기완결적으로 분리한다
-- (coupon_application/coupon_award가 이미 그렇듯 user/cash_point 테이블에 의존하지 않는다).
CREATE TABLE member_point_balance (
    member_id  INT PRIMARY KEY,
    balance    INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_member_point_balance_nonnegative CHECK (balance >= 0)
);

-- amount는 coupon_award.points(고액 10,000/일반 5,000)를 그대로 옮겨 적은 값이다 - 별도
-- 고정 보상이나 적립 유효기간은 이번에 합의된 규칙이 아니라 두지 않았다(expired_at 없음).
CREATE TABLE coupon_award_earn_history (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_id       INT         NOT NULL,
    coupon_award_id BIGINT      NOT NULL,
    amount          INT         NOT NULL,
    earned_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_coupon_award_earn_history_award UNIQUE (coupon_award_id),
    CONSTRAINT fk_coupon_award_earn_history_award FOREIGN KEY (coupon_award_id) REFERENCES coupon_award(id),
    KEY idx_coupon_award_earn_history_member (member_id, earned_at)
);
