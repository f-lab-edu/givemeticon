package com.jinddung2.givemeticon.domain.sale.exception;

public class ExpiredSaleException extends SaleException {

    public ExpiredSaleException() {
        super(SaleErrorCode.SALE_EXPIRED_DATE);
    }
}
