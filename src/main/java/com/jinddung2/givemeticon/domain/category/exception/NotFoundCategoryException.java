package com.jinddung2.givemeticon.domain.category.exception;

public class NotFoundCategoryException extends CategoryException {

    public NotFoundCategoryException() {
        super(CategoryErrorCode.NOT_FOUND_CATEGORY);
    }
}
