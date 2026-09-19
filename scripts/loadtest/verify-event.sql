-- mysql --batch --skip-column-names --database=givemeticon_loadtest -e "SET @stock_id=...; SOURCE ..."
SELECT 'stock', id, total, remain, total - remain AS decremented
  FROM coupon_stock WHERE id = @stock_id;
SELECT 'coupon_count', COUNT(*) FROM coupon WHERE stock_id = @stock_id;
SELECT 'duplicate_coupon_users', COUNT(*) FROM (
  SELECT user_id FROM coupon WHERE stock_id = @stock_id GROUP BY user_id HAVING COUNT(*) > 1
) duplicates;
SELECT 'request_status', status, COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id GROUP BY status ORDER BY status;
SELECT 'unresolved_pending', COUNT(*) FROM coupon_issue_request
  WHERE stock_id = @stock_id AND status = 'PENDING';
SELECT 'order_evidence', MIN(id), MAX(id), COUNT(*) FROM coupon_issue_request WHERE stock_id = @stock_id;
