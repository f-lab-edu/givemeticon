-- 장애 복구가 PENDING으로 멈춘 접수를 재실행할 때 필요한 쿠폰 발급 파라미터를 원장에
-- 함께 저장한다. 접수 시점의 요청 값을 그대로 보존해야, 복구가 원래 요청과 동일한
-- 쿠폰(이름/유형/가격)을 발급할 수 있다.
ALTER TABLE coupon_issue_request
    ADD COLUMN coupon_name VARCHAR(255) NOT NULL AFTER user_id,
    ADD COLUMN coupon_type VARCHAR(50)  NOT NULL AFTER coupon_name,
    ADD COLUMN price       INT          NOT NULL AFTER coupon_type;

-- 장애 복구 배치가 "오래된 PENDING" 행을 스캔할 때 사용하는 인덱스.
CREATE INDEX idx_coupon_issue_request_status_updated
    ON coupon_issue_request (status, updated_date);
