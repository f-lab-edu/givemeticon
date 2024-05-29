package com.jinddung2.givemeticon.domain.coupon.exception;

public class NotFoundCouponStock extends CouponException{
    public NotFoundCouponStock() {
        super(CouponErrorCode.NOT_FOUND_COUPON_STOCK);
    }
}
