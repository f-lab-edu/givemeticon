package com.jinddung2.givemeticon.domain.category.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundCategoryException extends CategoryException {

    public NotFoundCategoryException() {
        super(ErrorCode.NOT_FOUND_CATEGORY);
    }
}
