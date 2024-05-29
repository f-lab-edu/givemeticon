package com.jinddung2.givemeticon.domain.point.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class CashPointException extends GiveMeTiConException {

    public CashPointException(ErrorCode errorCode) {
        super(errorCode);
    }
}
