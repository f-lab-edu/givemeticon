package com.example.givemeticon.user.exception;

import com.example.givemeticon.common.exception.ErrorCode;
import com.example.givemeticon.common.exception.GiveMeTiConException;

public class UserException extends GiveMeTiConException {
    public UserException(ErrorCode errorCode) {
        super(errorCode);
    }
}
