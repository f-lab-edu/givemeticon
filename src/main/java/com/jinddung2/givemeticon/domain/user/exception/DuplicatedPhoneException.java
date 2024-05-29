package com.jinddung2.givemeticon.domain.user.exception;

public class DuplicatedPhoneException extends UserException {

    public DuplicatedPhoneException() {
        super(UserErrorCode.DUPLICATED_PHONE);
    }
}
