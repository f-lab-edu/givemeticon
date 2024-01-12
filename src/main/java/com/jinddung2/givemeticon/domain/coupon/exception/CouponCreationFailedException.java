package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class CouponCreationFailedException extends CouponException {
    public CouponCreationFailedException() {
        super(ErrorCode.ALREADY_BOUGHT_SALE);
    }
}
