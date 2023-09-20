package com.jinddung2.givemeticon.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class MisMatchPasswordException extends UserException {
    public MisMatchPasswordException() {
        super(ErrorCode.INCORRECT_PASSWORD);
    }
}
