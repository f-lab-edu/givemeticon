package com.jinddung2.givemeticon.domain.coupon.exception;

public class NotFoundCoupon extends CouponException{
    public NotFoundCoupon() {
        super(CouponErrorCode.NOT_FOUND_COUPON);
    }
}
