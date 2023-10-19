package com.jinddung2.givemeticon.domain.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundUserException extends UserException {
    public NotFoundUserException() {
        super(ErrorCode.NOT_FOUND_USER);
    }
}
