package com.jinddung2.givemeticon.common.exception;

public class UnauthorizedUserException extends GiveMeTiConException {
    public UnauthorizedUserException() {
        super(ErrorCode.UNAUTHENTICATED);
    }
}
