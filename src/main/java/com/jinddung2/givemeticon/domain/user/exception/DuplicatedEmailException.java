package com.jinddung2.givemeticon.domain.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedEmailException extends UserException {

    public DuplicatedEmailException() {
        super(ErrorCode.DUPLICATED_EMAIL);
    }
}
