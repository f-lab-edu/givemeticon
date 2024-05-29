package com.jinddung2.givemeticon.domain.point.exception;

public class NotFoundCashPoint extends CashPointException{
    public NotFoundCashPoint() {
        super(CashPointErrorCode.NOT_FOUND_CASH_POINT);
    }
}
