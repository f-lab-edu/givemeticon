CREATE UNIQUE INDEX uk_coupon_user_stock
    ON coupon (user_id, stock_id);
