package com.jinddung2.givemeticon.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedEmailException extends UserException {

    public DuplicatedEmailException() {
        super(ErrorCode.DUPLICATED_EMAIL);
    }
}
