package com.jinddung2.givemeticon.common.exception;

import lombok.Getter;

@Getter
public class GiveMeTiConException extends RuntimeException {

    private final ErrorCode errorCode;

    public GiveMeTiConException(final ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public GiveMeTiConException(final String message, final ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
