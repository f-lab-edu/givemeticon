package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponUserMismatchException extends CouponException {
    public CouponUserMismatchException() {
        super(CouponErrorCode.COUPON_USER_MISMATCH);
    }
}
