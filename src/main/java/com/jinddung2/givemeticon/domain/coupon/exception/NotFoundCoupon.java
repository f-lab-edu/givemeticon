package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundCoupon extends CouponException{
    public NotFoundCoupon() {
        super(ErrorCode.NOT_FOUND_COUPON);
    }
}
