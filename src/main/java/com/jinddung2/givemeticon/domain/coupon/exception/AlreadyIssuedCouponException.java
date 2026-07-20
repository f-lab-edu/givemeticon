package com.jinddung2.givemeticon.domain.coupon.exception;

public class AlreadyIssuedCouponException extends CouponException {
    public AlreadyIssuedCouponException() {
        super(CouponErrorCode.COUPON_ALREADY_ISSUED);
    }
}
