package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class CouponException extends GiveMeTiConException {
    public CouponException(ErrorCode errorCode) {
        super(errorCode);
    }
}
