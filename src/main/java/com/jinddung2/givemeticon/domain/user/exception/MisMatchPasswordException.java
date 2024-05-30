package com.jinddung2.givemeticon.domain.user.exception;

public class MisMatchPasswordException extends UserException {
    public MisMatchPasswordException() {
        super(UserErrorCode.INCORRECT_PASSWORD);
    }
}
