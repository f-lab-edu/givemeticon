package com.example.givemeticon.user.exception;

import com.example.givemeticon.common.exception.ErrorCode;

public class DuplicatedEmailException extends UserException {

    public DuplicatedEmailException() {
        super(ErrorCode.DUPLICATED_EMAIL);
    }
}
