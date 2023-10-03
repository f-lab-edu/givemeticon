package com.jinddung2.givemeticon.brand.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class BrandException extends GiveMeTiConException {
    public BrandException(ErrorCode errorCode) {
        super(errorCode);
    }
}
