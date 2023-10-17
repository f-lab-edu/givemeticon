package com.jinddung2.givemeticon.account.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class MistMatchBirthDay extends AccountException {
    public MistMatchBirthDay(ErrorCode errorCode) {
        super(errorCode);
    }
}
