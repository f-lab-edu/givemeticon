package com.jinddung2.givemeticon.common.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;

public class UnauthorizedUserException extends GiveMeTiConException {
    public UnauthorizedUserException() {
        super(ErrorCode.UNAUTHENTICATED);
    }
}
