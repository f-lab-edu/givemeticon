package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundCouponStock extends CouponException{
    public NotFoundCouponStock() {
        super(ErrorCode.NOT_FOUND_COUPON_STOCK);
    }
}
