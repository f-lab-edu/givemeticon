package com.jinddung2.givemeticon.domain.user.exception;

public class NotFoundUserException extends UserException {
    public NotFoundUserException() {
        super(UserErrorCode.NOT_FOUND_USER);
    }
}
