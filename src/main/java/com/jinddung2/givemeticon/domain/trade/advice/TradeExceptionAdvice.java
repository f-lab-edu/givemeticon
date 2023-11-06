package com.jinddung2.givemeticon.domain.trade.advice;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.trade.exception.TradeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class TradeExceptionAdvice {
    @ExceptionHandler(TradeException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleItemException(TradeException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("Trade exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}
