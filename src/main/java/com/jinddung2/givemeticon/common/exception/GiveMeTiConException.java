package com.jinddung2.givemeticon.common.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GiveMeTiConException extends RuntimeException {

    private final ErrorCode errorCode;

}
