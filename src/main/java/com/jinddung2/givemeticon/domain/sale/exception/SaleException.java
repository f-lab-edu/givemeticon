package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class SaleException extends GiveMeTiConException {
    public SaleException(ErrorCode errorCode) {
        super(errorCode);
    }
}
