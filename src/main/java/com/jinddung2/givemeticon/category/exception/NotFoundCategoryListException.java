package com.jinddung2.givemeticon.category.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundCategoryListException extends CategoryException {

    public NotFoundCategoryListException() {
        super(ErrorCode.EMPTY_CATEGORY_LIST);
    }
}
