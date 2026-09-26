package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponEventNotOpenException extends CouponException {
    public CouponEventNotOpenException() {
        super(CouponErrorCode.COUPON_EVENT_NOT_OPEN);
    }
}
