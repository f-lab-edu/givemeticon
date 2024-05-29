package com.jinddung2.givemeticon.domain.sale.exception;

public class NotRegistrSellerException extends SaleException {

    public NotRegistrSellerException() {
        super(SaleErrorCode.NOT_REGISTER_ACCOUNT);
    }
}
