package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponRequestNotFoundException extends CouponException {
    public CouponRequestNotFoundException() {
        super(CouponErrorCode.COUPON_REQUEST_NOT_FOUND);
    }
}
