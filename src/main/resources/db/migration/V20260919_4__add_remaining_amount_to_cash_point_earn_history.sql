-- 포인트 사용(차감) 로직이 "이 적립 건에서 아직 얼마가 남았는지"를 알아야 하므로,
-- 적립 건별 잔여 적립액을 추가한다. amount(최초 적립액)는 그대로 두고 remaining_amount만
-- 소진시켜, 조건부 UPDATE(affected-rows 체크)로 이중 소진 없이 원자적으로 차감한다.
ALTER TABLE cash_point_earn_history
    ADD COLUMN remaining_amount INT NOT NULL DEFAULT 0;

UPDATE cash_point_earn_history
SET remaining_amount = amount;

-- 사용 가능한(만료 전, 잔여 있는) 적립 건을 지갑 단위로 오래된 순(=먼저 만료되는 순)으로
-- 찾는 조회를 지원한다.
CREATE INDEX idx_cash_point_earn_history_wallet_earned
    ON cash_point_earn_history (cash_point_id, earned_date);
