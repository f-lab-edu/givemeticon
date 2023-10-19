package com.jinddung2.givemeticon.account.advice;

import com.jinddung2.givemeticon.account.exception.AccountException;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class AccountExceptionAdvice {

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResult> handleUserException(AccountException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("brand exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}
