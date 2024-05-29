package com.jinddung2.givemeticon.domain.category.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class CategoryException extends GiveMeTiConException {

    public CategoryException(ErrorCode errorCode) {
        super(errorCode);
    }
}
