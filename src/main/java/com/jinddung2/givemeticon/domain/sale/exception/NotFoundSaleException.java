package com.jinddung2.givemeticon.domain.sale.exception;

public class NotFoundSaleException extends SaleException {

    public NotFoundSaleException() {
        super(SaleErrorCode.NOT_FOUND_SALE);
    }
}
