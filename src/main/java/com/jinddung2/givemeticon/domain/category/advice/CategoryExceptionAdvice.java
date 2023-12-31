package com.jinddung2.givemeticon.domain.category.advice;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.category.exception.CategoryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class CategoryExceptionAdvice {

    @ExceptionHandler(CategoryException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleUserException(CategoryException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("category exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}
