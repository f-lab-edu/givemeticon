package com.jinddung2.givemeticon.domain.item.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotRegistrSellerException extends ItemVariantException {

    public NotRegistrSellerException() {
        super(ErrorCode.NOT_REGISTER_ACCOUNT);
    }
}
