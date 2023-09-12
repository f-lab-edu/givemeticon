package com.example.givemeticon.user.exception;

import com.example.givemeticon.common.exception.ErrorCode;

public class DuplicatedPhoneException extends UserException {

    public DuplicatedPhoneException() {
        super(ErrorCode.DUPLICATED_PHONE);
    }
}
