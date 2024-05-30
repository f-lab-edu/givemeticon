package com.jinddung2.givemeticon.domain.trade.exception;

public class InvalidDiscountRateException extends TradeException {

    public InvalidDiscountRateException() {
        super(TradeErrorCode.INVALID_DISCOUNT_RATE);
    }
}
