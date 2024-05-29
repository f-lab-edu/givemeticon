package com.jinddung2.givemeticon.domain.brand.advice;

import com.jinddung2.givemeticon.common.config.controller.response.ApiResponse;
import com.jinddung2.givemeticon.common.config.controller.response.ErrorResult;
import com.jinddung2.givemeticon.domain.brand.exception.BrandException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.jindding2.givemeticon.domain.brand")
public class BrandExceptionAdvice {

    @ExceptionHandler(BrandException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleUserException(BrandException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("brand exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}
