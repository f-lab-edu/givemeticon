package com.jinddung2.givemeticon.user.advice;

import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.user.exception.UserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class UserExceptionAdvice {

    @ExceptionHandler(UserException.class)
    public ResponseEntity<ErrorResult> handleUserException(UserException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}