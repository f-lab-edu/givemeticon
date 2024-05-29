package com.jinddung2.givemeticon.common.config.controller.advice;

import com.jinddung2.givemeticon.common.config.controller.exception.ApiException;
import com.jinddung2.givemeticon.common.config.controller.response.ApiResponse;
import com.jinddung2.givemeticon.common.config.controller.response.ErrorResult;
import com.jinddung2.givemeticon.common.exception.UnauthorizedUserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(2)
@RestControllerAdvice
public class CommonExceptionAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String className = e.getParameter().getMethod().getDeclaringClass().getSimpleName();
        String methodName = e.getParameter().getMethod().getName();
        String defaultMessage = e.getFieldError().getDefaultMessage();

        ErrorResult errorResult = new ErrorResult(defaultMessage);
        log.debug("Exception Occurred in {}.{}(). Error message: {}", className, methodName, defaultMessage);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UnauthorizedUserException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleUnauthorizedUserException(UnauthorizedUserException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("UnauthorizedUserException Occurred. error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleOAuthException(ApiException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }

}
