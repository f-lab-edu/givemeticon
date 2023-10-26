package com.jinddung2.givemeticon.domain.item.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class ExpiredItemVariantException extends ItemVariantException {

    public ExpiredItemVariantException() {
        super(ErrorCode.EXPIRED_DATE);
    }
}
