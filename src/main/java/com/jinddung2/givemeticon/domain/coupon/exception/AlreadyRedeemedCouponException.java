package com.jinddung2.givemeticon.domain.coupon.exception;

public class AlreadyRedeemedCouponException extends CouponException {
    public AlreadyRedeemedCouponException() {
        super(CouponErrorCode.ALREADY_REDEEMED_COUPON);
    }
}
