package com.jinddung2.givemeticon.domain.brand.advice;

import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.brand.exception.BrandException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class BrandExceptionAdvice {

    @ExceptionHandler(BrandException.class)
    public ResponseEntity<ErrorResult> handleUserException(BrandException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("brand exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}
