package com.jinddung2.givemeticon.domain.point.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundCashPoint extends CashPointException{
    public NotFoundCashPoint() {
        super(ErrorCode.NOT_FOUND_CASH_POINT);
    }
}
