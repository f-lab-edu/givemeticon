package com.jinddung2.givemeticon.domain.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundEmailException extends UserException {

    public NotFoundEmailException() {
        super(ErrorCode.NOT_FOUND_EMAIL);
    }
}
