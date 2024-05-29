package com.jinddung2.givemeticon.domain.item.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class NotFoundItemException extends ItemException {
    public NotFoundItemException() {
        super(ErrorCode.NOT_FOUND_ITEM);
    }
}
