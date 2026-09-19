-- 쿠폰 사용으로 적립된 포인트 건별 기록. coupon_id 유니크 제약이 "쿠폰 1개당 적립 1회"를
-- 보장한다(INSERT IGNORE 기반 affected-rows 체크로 원자적으로 판단). expired_date는
-- 적립(=쿠폰 사용) 시점으로부터 1개월 뒤로, 이후 포인트 사용 로직이 유효기간을 판단할 때
-- 쓸 근거로 남겨둔다 - 포인트 사용(차감) 흐름 자체는 이번 범위에 포함하지 않았다.
CREATE TABLE cash_point_earn_history (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    cash_point_id INT NOT NULL,
    coupon_id     INT NOT NULL,
    amount        INT NOT NULL,
    earned_date   DATE NOT NULL,
    expired_date  DATE NOT NULL,
    UNIQUE KEY uk_cash_point_earn_history_coupon (coupon_id)
);
