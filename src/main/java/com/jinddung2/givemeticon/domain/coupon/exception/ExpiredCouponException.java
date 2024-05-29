package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class ExpiredCouponException extends CouponException {
    public ExpiredCouponException() {
        super(ErrorCode.COUPON_EXPIRED_DATE);
    }
}
