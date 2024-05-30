package com.jinddung2.givemeticon.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ExternalApiRequestException extends RuntimeException{

    private final String statusCode;
    private final String message;

}
