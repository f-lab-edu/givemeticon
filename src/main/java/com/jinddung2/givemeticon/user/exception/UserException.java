package com.jinddung2.givemeticon.user.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class UserException extends GiveMeTiConException {
    public UserException(ErrorCode errorCode) {
        super(errorCode);
    }
}
