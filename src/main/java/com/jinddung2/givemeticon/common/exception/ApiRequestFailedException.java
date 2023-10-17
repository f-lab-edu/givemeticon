package com.jinddung2.givemeticon.common.exception;

public class ApiRequestFailedException extends GiveMeTiConException {
    public ApiRequestFailedException() {
        super(ErrorCode.OPENAPI_REAL_NAME_REQUEST_FAIL);
    }
}
