package com.jinddung2.givemeticon.domain.sale.advice;

import com.jinddung2.givemeticon.common.config.controller.response.ApiResponse;
import com.jinddung2.givemeticon.common.config.controller.response.ErrorResult;
import com.jinddung2.givemeticon.domain.sale.exception.SaleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.jindding2.givemeticon.domain.sale")
public class SaleExceptionAdvice {

    @ExceptionHandler(SaleException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleItemException(SaleException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("Sale exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), e.getErrorCode().getStatus());
    }

}
