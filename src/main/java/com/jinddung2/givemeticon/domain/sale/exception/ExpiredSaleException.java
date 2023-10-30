package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class ExpiredSaleException extends SaleException {

    public ExpiredSaleException() {
        super(ErrorCode.EXPIRED_DATE);
    }
}
