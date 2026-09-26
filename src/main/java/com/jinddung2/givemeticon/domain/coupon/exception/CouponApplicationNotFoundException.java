package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponApplicationNotFoundException extends CouponException {
    public CouponApplicationNotFoundException() {
        super(CouponErrorCode.COUPON_APPLICATION_NOT_FOUND);
    }
}
