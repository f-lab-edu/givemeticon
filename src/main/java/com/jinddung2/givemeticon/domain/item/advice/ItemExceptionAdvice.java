package com.jinddung2.givemeticon.domain.item.advice;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.item.exception.ItemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ItemExceptionAdvice {

    @ExceptionHandler(ItemException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleItemException(ItemException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("item exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}