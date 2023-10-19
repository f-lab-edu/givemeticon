package com.jinddung2.givemeticon.domain.account.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class AccountException extends GiveMeTiConException {
    public AccountException(ErrorCode errorCode) {
        super(errorCode);
    }
}
