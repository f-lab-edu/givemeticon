package com.jinddung2.givemeticon.domain.item.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class ItemVariantException extends GiveMeTiConException {
    public ItemVariantException(ErrorCode errorCode) {
        super(errorCode);
    }
}
