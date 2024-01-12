package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotEnoughCouponStockException extends CouponException {
    public NotEnoughCouponStockException() {
        super(ErrorCode.NOT_ENOUGH_COUPON_STOCK);
    }
}
