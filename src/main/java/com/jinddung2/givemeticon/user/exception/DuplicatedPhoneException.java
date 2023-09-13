package com.jinddung2.givemeticon.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedPhoneException extends UserException {

    public DuplicatedPhoneException() {
        super(ErrorCode.DUPLICATED_PHONE);
    }
}
