package com.jinddung2.givemeticon.category.advice;

import com.jinddung2.givemeticon.category.exception.CategoryException;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class CategoryExceptionAdvice {

    @ExceptionHandler(CategoryException.class)
    public ResponseEntity<ErrorResult> handleUserException(CategoryException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("brand exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}
