package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundSaleException extends SaleException {

    public NotFoundSaleException() {
        super(ErrorCode.NOT_FOUND_SALE);
    }
}
