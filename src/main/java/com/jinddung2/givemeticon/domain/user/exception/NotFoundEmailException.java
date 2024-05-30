package com.jinddung2.givemeticon.domain.user.exception;

public class NotFoundEmailException extends UserException {

    public NotFoundEmailException() {
        super(UserErrorCode.NOT_FOUND_EMAIL);
    }
}
