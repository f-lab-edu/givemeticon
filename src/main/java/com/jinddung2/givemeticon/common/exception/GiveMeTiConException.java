package com.jinddung2.givemeticon.common.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;

@Getter
public class GiveMeTiConException extends RuntimeException {

    private final ErrorCode errorCode;

    public GiveMeTiConException(ErrorCode errorCode) {
        super(errorCode.getErrorDetail());
        this.errorCode = errorCode;
    }
}
