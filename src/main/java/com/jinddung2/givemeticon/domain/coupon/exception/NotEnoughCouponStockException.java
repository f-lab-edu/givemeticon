package com.jinddung2.givemeticon.domain.coupon.exception;

public class NotEnoughCouponStockException extends CouponException {
    public NotEnoughCouponStockException() {
        super(CouponErrorCode.NOT_ENOUGH_COUPON_STOCK);
    }
}
