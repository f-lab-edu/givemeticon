package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponEventNotFoundException extends CouponException {
    public CouponEventNotFoundException() {
        super(CouponErrorCode.COUPON_EVENT_NOT_FOUND);
    }
}
