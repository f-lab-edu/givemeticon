package com.jinddung2.givemeticon.domain.user.exception;

public class DuplicatedEmailException extends UserException {

    public DuplicatedEmailException() {
        super(UserErrorCode.DUPLICATED_EMAIL);
    }
}
