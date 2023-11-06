package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class InvalidDiscountRateException extends TradeException {

    public InvalidDiscountRateException() {
        super(ErrorCode.INVALID_DISCOUNT_RATE);
    }
}
