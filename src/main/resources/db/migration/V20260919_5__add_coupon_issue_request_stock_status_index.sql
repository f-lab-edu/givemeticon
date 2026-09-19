-- 비동기 발급 워커가 "이 재고(stock_id)에서 가장 오래된 PENDING 접수 1건"을 반복
-- 조회한다(WHERE stock_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1). 기존
-- idx_coupon_issue_request_status_updated(status, updated_date)는 stock_id로 좁히지
-- 못해 이 조회에 맞지 않는다.
CREATE INDEX idx_coupon_issue_request_stock_status_id
    ON coupon_issue_request (stock_id, status, id);
