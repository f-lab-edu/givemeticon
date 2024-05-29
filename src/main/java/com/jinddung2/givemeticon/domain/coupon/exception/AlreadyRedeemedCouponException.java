package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class AlreadyRedeemedCouponException extends CouponException {
    public AlreadyRedeemedCouponException() {
        super(ErrorCode.ALREADY_REDEEMED_COUPON);
    }
}
