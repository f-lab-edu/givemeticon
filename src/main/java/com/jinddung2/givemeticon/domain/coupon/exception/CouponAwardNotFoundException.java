package com.jinddung2.givemeticon.domain.coupon.exception;

public class CouponAwardNotFoundException extends CouponException {
    public CouponAwardNotFoundException() {
        super(CouponErrorCode.COUPON_AWARD_NOT_FOUND);
    }
}
