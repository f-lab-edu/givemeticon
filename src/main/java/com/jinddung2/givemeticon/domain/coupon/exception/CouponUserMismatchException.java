package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class CouponUserMismatchException extends CouponException {
    public CouponUserMismatchException() {
        super(ErrorCode.COUPON_USER_MISMATCH);
    }
}
