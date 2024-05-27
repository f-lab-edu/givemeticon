package com.jinddung2.givemeticon.common.exception;

public class ApiException extends RuntimeException{
    int status;
    String errorDetail;

    public ApiException(int status, String errorDetail) {
        super(errorDetail);
        this.status = status;
        this.errorDetail = errorDetail;
    }
}
