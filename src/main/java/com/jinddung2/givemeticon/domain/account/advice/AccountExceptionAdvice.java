package com.jinddung2.givemeticon.domain.account.advice;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.account.exception.AccountException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class AccountExceptionAdvice {

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleUserException(AccountException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("account exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}
