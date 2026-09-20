-- mysql --batch --skip-column-names --database=givemeticon_loadtest -e "SET @stock_id=...; SET @stock_total=...; SOURCE ..."
-- verify-event.sql의 상위 호환: 같은 기본 지표에 "접수/발급 분리 + 묶음 차감" 전용 확인
-- (SOLD_OUT 포함 처리 완료 여부, 선착순 경계 위반)을 더한다.

-- 발급 수 = stock_total, 회원별 중복 0, 재고 잔여 0.
SELECT 'stock', id, total, remain, total - remain AS decremented
  FROM coupon_stock WHERE id = @stock_id;
SELECT 'coupon_count', COUNT(*) FROM coupon WHERE stock_id = @stock_id;
SELECT 'duplicate_coupon_users', COUNT(*) FROM (
  SELECT user_id FROM coupon WHERE stock_id = @stock_id GROUP BY user_id HAVING COUNT(*) > 1
) duplicates;

-- 상태 분포(ISSUED/SOLD_OUT/PENDING/REJECTED). 정상 종료라면 PENDING은 0이어야 한다.
SELECT 'request_status', status, COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id GROUP BY status ORDER BY status;
SELECT 'unresolved_pending', COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status = 'PENDING';
SELECT 'order_evidence', MIN(id), MAX(id), COUNT(*) FROM coupon_issue_request WHERE stock_id = @stock_id;

-- 선착순 위반: "발급된 신청 집합"이 정확히 "접수번호 기준 앞 stock_total건"과 일치하는지.
-- boundary_id = 그 재고에서 stock_total번째로 작은 접수 id(총 접수 건수가 stock_total
-- 이상일 때만 의미가 있다 - 미만이면 전원이 ISSUED이어야 정상이므로 이 값 자체가 NULL이다).
-- MySQL의 LIMIT/OFFSET은 리터럴만 받고 사용자 변수(@stock_total 등)를 허용하지 않으므로,
-- ROW_NUMBER() 윈도우 함수로 "stock_total번째로 작은 접수 id"를 구한다.
SET @boundary_id = (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id ASC) AS rn
    FROM coupon_issue_request
    WHERE stock_id = @stock_id
  ) ranked
  WHERE rn = @stock_total
);
SELECT 'boundary_request_id', @boundary_id;
-- 위반 A: 경계보다 뒤(늦게 접수)인데 ISSUED된 건 - 있으면 안 됨.
SELECT 'fcfs_violation_issued_after_boundary', COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status = 'ISSUED' AND id > @boundary_id;
-- 위반 B: 경계 이내(먼저 접수)인데 ISSUED가 아닌 건 - 있으면 안 됨(재고 소진 전에 밀렸다는 뜻).
SELECT 'fcfs_violation_not_issued_within_boundary', COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status != 'ISSUED' AND id <= @boundary_id;

-- "쿠폰 생성 시각"의 근사치: 발급 트랜잭션이 markIssued까지 커밋한 시각(updated_date, 마이크로초
-- 단위). coupon.created_date는 DATE(일 단위)라 정밀 측정에 쓸 수 없어 이 컬럼을 대신 쓴다.
-- updated_date는 DB 세션 타임존(SYSTEM=KST)이라 run.env의 started_at_utc(UTC)와 그대로
-- 뺄 수 없다 - db_utc_now를 같은 순간 함께 찍어 report 생성기가 오프셋을 보정하게 한다.
SELECT 'last_issued_at', MAX(updated_date) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status = 'ISSUED';
SELECT 'last_sold_out_at', MAX(updated_date) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status = 'SOLD_OUT';
SELECT 'db_utc_now', UTC_TIMESTAMP(6);
