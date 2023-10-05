package com.jinddung2.givemeticon.category.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class CategoryException extends GiveMeTiConException {

    public CategoryException(ErrorCode errorCode) {
        super(errorCode);
    }
}
