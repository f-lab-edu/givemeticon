package com.jinddung2.givemeticon.domain.point.exception;

public class NotEnoughCashPointException extends CashPointException {
    public NotEnoughCashPointException() {
        super(CashPointErrorCode.NOT_ENOUGH_CASH_POINT);
    }
}
