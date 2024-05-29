package com.jinddung2.givemeticon.domain.coupon.exception;

public class ExpiredCouponException extends CouponException {
    public ExpiredCouponException() {
        super(CouponErrorCode.COUPON_EXPIRED_DATE);
    }
}
