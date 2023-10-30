package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotRegistrSellerException extends SaleException {

    public NotRegistrSellerException() {
        super(ErrorCode.NOT_REGISTER_ACCOUNT);
    }
}
